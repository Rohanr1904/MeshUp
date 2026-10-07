package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.Redact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Routes messages between local mesh transports and Nostr, matching iOS behavior.
 *
 * MeshUp P2-PR8 durable outbox (Decision 015 + amendment, R-1). Text DMs that are queued or sent
 * over the mesh are mirrored into the encrypted `outbox` table through [OutboxPersistence]:
 * - Threading: RAM state (queue, durable-row mirror, retry state) is guarded by this object's
 *   monitor. Every DB write is a job on ONE single-consumer channel ([writes]) running on
 *   Dispatchers.IO, so enqueue -> markSent -> remove apply in submission order and the caller's
 *   thread never waits on I/O. Each job carries the [generation] it was submitted under and is
 *   skipped once panic ([clearAllAndAwait]) has advanced it. Status updates ([statusSink] ->
 *   AppStateStore) are always issued outside the monitor (AppStateStore calls back on ACKs).
 * - MESH: row persisted as SENT; removed by a matching DELIVERED/READ ACK, or silently (status
 *   kept) after OUTBOX_EXPIRY_MS from creation, or by the per-peer SENT cap (oldest first).
 * - QUEUED: row persisted as QUEUED; expires to Failed after OUTBOX_EXPIRY_MS from creation (D1).
 * - NOSTR routes and Nostr-alias conversations are never persisted (D4); a queued row that is
 *   flushed via Nostr is removed.
 * - Limits (D2): 200 QUEUED per conversation, 2000 QUEUED global; overflow is Failed("queue full").
 * - [rehydrate] reloads rows after a restart. It is called once per process by the mesh owner
 *   (MeshServiceHolder.getUnifiedOrCreate), never by the UI, and retried if the load fails.
 */
class MessageRouter internal constructor(
    private val context: Context,
    private var mesh: MeshService,
    private val nostr: NostrTransport,
    private val persistence: OutboxPersistence = RepositoryOutboxPersistence(context),
    private val statusSink: (String, DeliveryStatus) -> Unit = AppStateStore::updatePrivateMessageStatus,
    // MeshUp: Internet opt-in gate (Decision 013) - injectable so the route table is unit-testable
    private val internetEnabled: () -> Boolean = { com.bitchat.android.meshup.settings.InternetGate.isEnabled() }
) {
    enum class RouteResult {
        MESH,
        NOSTR,
        QUEUED,
        /** Not routable (e.g. geohash DM while Internet is off); the message was marked Failed. */
        DROPPED,
        /** Not queued (outbox limit reached); the message was marked Failed("queue full"). */
        FAILED
    }

    private data class QueuedMessage(
        val content: String,
        val nickname: String,
        val messageID: String,
        val enqueuedAtMs: Long,
        /** Identity the message was addressed to; a resend is refused if it changes. */
        val recipientFingerprint: String? = null,
        /** Restored from a SENT row: expiry drops it silently instead of failing it. */
        val sentBefore: Boolean = false
    )

    /** RAM mirror of one durable row. */
    private data class DurableRow(
        val conversationKey: String,
        var state: OutboxState,
        val createdAt: Long,
        var recipientFingerprint: String?
    )

    private data class ConversationRetry(
        val handshakeAttempts: Int,
        val nextHandshakeAttemptAtMs: Long
    )

    private sealed interface Effect {
        data class Status(val messageID: String, val status: DeliveryStatus) : Effect
        data class Expired(val messageID: String) : Effect
    }

    companion object {
        private const val TAG = "MessageRouter"
        private const val OUTBOX_TICK_MS = AppConstants.Router.OUTBOX_TICK_MS
        private const val OUTBOX_EXPIRY_MS = AppConstants.Router.OUTBOX_EXPIRY_MS
        private const val PER_PEER_LIMIT = AppConstants.Router.OUTBOX_PER_PEER_LIMIT
        private const val GLOBAL_LIMIT = AppConstants.Router.OUTBOX_GLOBAL_LIMIT
        // Decision 015 amendment: SENT rows have their own per-peer cap (oldest evicted silently).
        private const val SENT_PER_PEER_LIMIT = AppConstants.Router.OUTBOX_PER_PEER_LIMIT
        private val RESEND_BACKOFF_MS = AppConstants.Router.OUTBOX_RESEND_BACKOFF_MS
        private val HANDSHAKE_RETRY_BACKOFF_MS = AppConstants.Router.HANDSHAKE_RETRY_BACKOFF_MS
        // Bounded memory of acked/removed IDs; sized to the D2 global cap.
        private const val ID_MEMORY_CAP = AppConstants.Router.OUTBOX_GLOBAL_LIMIT

        internal const val REASON_QUEUE_FULL = "queue full"
        internal const val REASON_NOT_DELIVERED = "Not delivered"
        internal const val REASON_RECIPIENT_CHANGED = "Recipient changed"
        internal const val REASON_BLOCKED = "Recipient blocked"

        @Volatile private var INSTANCE: MessageRouter? = null
        internal var disableSchedulerForTesting = false
        internal var persistenceOverrideForTesting: OutboxPersistence? = null
        internal var statusSinkOverrideForTesting: ((String, DeliveryStatus) -> Unit)? = null
        fun tryGetInstance(): MessageRouter? = INSTANCE
        fun getInstance(context: Context, mesh: MeshService): MessageRouter {
            val instance = INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val nostr = NostrTransport.getInstance(context)
                    val appContext = context.applicationContext
                    MessageRouter(
                        appContext,
                        mesh,
                        nostr,
                        persistenceOverrideForTesting ?: RepositoryOutboxPersistence(appContext),
                        statusSinkOverrideForTesting ?: AppStateStore::updatePrivateMessageStatus
                    ).also { instance ->
                        // Register for favorites changes to flush outbox
                        try {
                            com.bitchat.android.favorites.FavoritesPersistenceService.shared.addListener(instance.favoriteListener)
                        } catch (_: Exception) {}
                        // Single ACK/delete hook for the durable outbox
                        AppStateStore.outboxListener = instance.appStateListener
                        INSTANCE = instance
                    }
                }
            }
            // Always update mesh reference and sync peer ID, and make sure the retry
            // scheduler is running (it is stopped together with MeshForegroundService).
            instance.mesh = mesh
            instance.nostr.senderPeerID = mesh.myPeerID
            instance.startOutboxScheduler()
            instance.retryRehydrateIfNeeded()
            return instance
        }

        internal fun resetForTesting() {
            INSTANCE?.let {
                it.schedulerScope.cancel()
                it.writerScope.cancel()
                if (AppStateStore.outboxListener === it.appStateListener) {
                    AppStateStore.outboxListener = null
                }
            }
            INSTANCE = null
        }
    }

    // Outbox: conversationID -> queued messages, oldest first
    private val outbox = ConcurrentHashMap<String, MutableList<QueuedMessage>>()

    // Mirror of the rows believed to be in the durable outbox (QUEUED and SENT), by messageID.
    // Guarded by the monitor; drives the D2 limits, SENT expiry/cap and ACK binding.
    private val durableRows = LinkedHashMap<String, DurableRow>()

    // ACKs (messageID -> acker) seen before/while the startup load runs, so a rehydrate racing an
    // ACK neither resurrects nor resends the row. Bounded to the D2 global cap.
    private val recentlyAcked = LinkedHashMap<String, String>()

    // IDs the user deleted or that were dropped for a block in this process; never restored.
    private val removedIds = LinkedHashSet<String>()

    // Bumped by panic; jobs and a rehydrate from an older generation are discarded.
    @Volatile private var generation = 0L

    private val rehydrateStarted = AtomicBoolean(false)
    private var rehydrateRequested = false
    private var reconcileDone = false
    // Orphan watermark: only Sending rows sent before this instant can be reconcile-failed.
    private var rehydrateWatermarkMs = 0L

    // Per-conversation handshake retry state for queued messages
    private val retryState = ConcurrentHashMap<String, ConversationRetry>()

    // Status/expiry notifications collected under the monitor, delivered after releasing it.
    private val pendingEffects = ArrayList<Effect>()

    private val schedulerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var schedulerJob: kotlinx.coroutines.Job? = null

    // Ordered single-consumer writer for all outbox DB operations.
    private val writerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    // Injectable clock for tests
    internal var clock: () -> Long = { System.currentTimeMillis() }

    // Block-list check by fingerprint (DataManager's persisted set); injectable for tests.
    internal var isFingerprintBlocked: (String) -> Boolean = { fingerprint ->
        com.bitchat.android.ui.DataManager.isFingerprintBlocked(context, fingerprint)
    }

    /**
     * Compatibility callback, invoked after the router itself has marked an expired queued message
     * Failed("Not delivered"). Nothing needs to be registered for expiry to take effect.
     */
    var onMessageExpired: ((String) -> Unit)? = null

    private val appStateListener = object : PrivateMessageOutboxListener {
        override fun onPrivateMessageAcknowledged(messageID: String, acknowledgedBy: String) =
            this@MessageRouter.onMessageAcknowledged(messageID, acknowledgedBy)

        override fun onPrivateMessagesRemoved(conversationID: String?, messageIDs: Collection<String>) =
            this@MessageRouter.onMessagesRemoved(conversationID, messageIDs)
    }

    init {
        writerScope.launch {
            for (job in writes) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Outbox write failed: ${e.javaClass.simpleName}")
                }
            }
        }
        startOutboxScheduler()
    }

    /** Queue a DB job; it is skipped if panic advances [generation] before it runs. */
    private fun submit(job: suspend () -> Unit) {
        val submittedAt = generation
        writes.trySend { if (submittedAt == generation) job() }
    }

    /** Suspends until every outbox write submitted before this call has been applied (or skipped). */
    internal suspend fun awaitOutboxWrites() {
        val done = CompletableDeferred<Unit>()
        writes.trySend { done.complete(Unit) } // never generation-gated
        done.await()
    }

    fun clearAll() {
        synchronized(this) {
            generation++
            outbox.clear()
            durableRows.clear()
            recentlyAcked.clear()
            retryState.clear()
            pendingEffects.clear()
        }
        Log.d(TAG, "Cleared all MessageRouter outbox messages and retry state")
    }

    /**
     * Panic: invalidate every queued DB job and in-flight rehydrate, clear RAM, then wait until
     * the writer has drained, so no outbox write can land after the caller's database wipe.
     */
    suspend fun clearAllAndAwait() {
        // Refuse new private messages until panic completes (see [resumeAdmissionAfterPanic]), so a
        // send racing the wipe cannot leave a row that would later go out under the new identity.
        admissionPaused = true
        clearAll()
        awaitOutboxWrites()
    }

    /** Re-enable private-message admission once the panic wipe has fully completed. */
    fun resumeAdmissionAfterPanic() {
        admissionPaused = false
    }

    @Volatile private var admissionPaused = false

    // Listener for favorites changes to flush outbox when npub mapping appears/changes
    private val favoriteListener = object: com.bitchat.android.favorites.FavoritesChangeListener {

        override fun onFavoriteChanged(noiseKeyHex: String) {
            flushOutboxFor(noiseKeyHex)
            ContactIdentityResolver.peerIdForNoiseKeyHex(noiseKeyHex)?.let { flushOutboxFor(it) }
        }
        override fun onAllCleared() {
        }
    }

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String): RouteResult {
        if (admissionPaused) {
            Log.w(TAG, "Private message rejected during panic wipe")
            return RouteResult.DROPPED
        }
        val resolution = ContactDirectory.resolve(toPeerID)
        val conversationID = resolution.conversationID
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        val nostrTarget = resolution.noiseKeyHex ?: toPeerID

        if (com.bitchat.android.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            // MeshUp: Internet opt-in gate (Decision 013) - geohash DMs are Nostr-only; drop while OFF
            if (!internetEnabled()) return dropped(messageID)
            Log.d(TAG, "Routing PM via Nostr (geohash) to alias ${Redact.id(toPeerID)} id=${Redact.id(messageID)}")
            val recipientHex = com.bitchat.android.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                val sourceGeohash = com.bitchat.android.nostr.GeohashConversationRegistry.get(toPeerID)
                nostr.sendPrivateMessageGeohash(content, recipientHex, messageID, sourceGeohash)
                statusSink(messageID, DeliveryStatus.Sent)
                return RouteResult.NOSTR
            }
            return dropped(messageID)
        }

        // Nostr-alias conversations keep their RAM-only behaviour and are never persisted.
        val persistable = !ContactIdentityResolver.isNostrAlias(conversationID) &&
            !ContactIdentityResolver.isNostrAlias(toPeerID)
        val hasMesh = meshTarget?.let { isConnected(mesh, it) } == true
        if (meshTarget != null && isReady(mesh, meshTarget)) {
            Log.d(TAG, "Routing PM via mesh to ${Redact.id(meshTarget)} msg_id=${Redact.id(messageID)}")
            if (persistable) {
                val fingerprint = authenticatedFingerprint(meshTarget) ?: currentFingerprint(resolution, meshTarget)
                persistSent(conversationID, QueuedMessage(content, recipientNickname, messageID, clock(), fingerprint))
            }
            mesh.sendPrivateMessage(content, meshTarget, recipientNickname, messageID)
            return RouteResult.MESH
        } else if (canSendViaNostr(nostrTarget)) {
            Log.d(TAG, "Routing PM via Nostr to ${Redact.id(conversationID)} msg_id=${Redact.id(messageID)}")
            nostr.sendPrivateMessage(content, nostrTarget, recipientNickname, messageID)
            statusSink(messageID, DeliveryStatus.Sent)
            return RouteResult.NOSTR
        } else {
            val entry = QueuedMessage(
                content,
                recipientNickname,
                messageID,
                clock(),
                currentFingerprint(resolution, meshTarget)
            )
            if (!enqueue(conversationID, entry, persistable)) {
                Log.w(TAG, "Outbox full for ${Redact.id(conversationID)}; rejecting msg_id=${Redact.id(messageID)}")
                statusSink(messageID, DeliveryStatus.Failed(REASON_QUEUE_FULL))
                return RouteResult.FAILED
            }
            Log.d(TAG, "Queued PM for ${Redact.id(conversationID)} (no mesh, no Nostr mapping) msg_id=${Redact.id(messageID)}")
            if (hasMesh) meshTarget?.let { kickHandshake(conversationID, it, immediate = true) }
            return RouteResult.QUEUED
        }
    }

    private fun dropped(messageID: String): RouteResult {
        statusSink(messageID, DeliveryStatus.Failed(REASON_NOT_DELIVERED))
        return RouteResult.DROPPED
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        val nostrTarget = resolution.noiseKeyHex ?: toPeerID
        if (meshTarget != null && isReady(mesh, meshTarget)) {
            Log.d(TAG, "Routing READ via mesh to ${Redact.id(meshTarget)} id=${Redact.id(receipt.originalMessageID)}")
            mesh.sendReadReceipt(receipt.originalMessageID, meshTarget, mesh.getPeerNicknames()[meshTarget] ?: mesh.myPeerID)
        } else if (internetEnabled()) { // MeshUp: Internet opt-in gate (Decision 013)
            Log.d(TAG, "Routing READ via Nostr to ${Redact.id(toPeerID)} id=${Redact.id(receipt.originalMessageID)}")
            nostr.sendReadReceipt(receipt, nostrTarget)
        }
    }

    fun sendDeliveryAck(messageID: String, toPeerID: String) {
        // Mesh delivery ACKs are sent by the receiver automatically.
        // Only route via Nostr when mesh path isn't available or when this is a geohash alias
        // MeshUp: Internet opt-in gate (Decision 013) - every remaining path here is Nostr-only
        if (com.bitchat.android.nostr.GeohashAliasRegistry.contains(toPeerID)) {
            if (!internetEnabled()) return
            val recipientHex = com.bitchat.android.nostr.GeohashAliasRegistry.get(toPeerID)
            if (recipientHex != null) {
                nostr.sendDeliveryAckGeohash(messageID, recipientHex, try { com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)!! } catch (_: Exception) { return })
                return
            }
        }
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        if (internetEnabled() && !(meshTarget != null && (mesh.getPeerInfo(meshTarget)?.isConnected == true) && mesh.hasEstablishedSession(meshTarget))) {
            nostr.sendDeliveryAck(messageID, resolution.noiseKeyHex ?: toPeerID)
        }
    }

    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) {
        val resolution = ContactDirectory.resolve(toPeerID)
        val meshTarget = resolution.meshPeerID ?: toPeerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }
        if (meshTarget != null && mesh.getPeerInfo(meshTarget)?.isConnected == true && mesh.hasEstablishedSession(meshTarget)) {
            val myNpub = try { com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(context)?.npub } catch (_: Exception) { null }
            val content = FavoriteControlMessage.encode(isFavorite, myNpub)
            val nickname = mesh.getPeerNicknames()[meshTarget] ?: meshTarget
            mesh.sendPrivateMessage(content, meshTarget, nickname, null)
        } else if (internetEnabled()) { // MeshUp: Internet opt-in gate (Decision 013)
            nostr.sendFavoriteNotification(resolution.noiseKeyHex ?: toPeerID, isFavorite)
        }
    }

    // Flush any queued messages for a specific peerID.
    // All outbox mutations happen under the router monitor so a concurrent enqueue cannot
    // be lost between the empty check and the map removal.
    fun flushOutboxFor(peerID: String) {
        synchronized(this) { flushLocked(peerID) }
        dispatchEffects()
    }

    private fun flushLocked(peerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val key = if (outbox.containsKey(conversationID)) conversationID else peerID
        val queued = outbox[key] ?: return
        if (queued.isEmpty()) return
        val resolution = ContactDirectory.resolve(conversationID)
        val meshTarget = resolution.meshPeerID
        val nostrTarget = resolution.noiseKeyHex ?: conversationID
        val viaMesh = meshTarget != null && isReady(mesh, meshTarget)
        if (!viaMesh && !canSendViaNostr(nostrTarget)) return
        Log.d(TAG, "Flushing outbox for ${Redact.id(conversationID)} count=${queued.size}")
        // Over an established Noise session the identity is the session's authenticated remote
        // static key, never the announced one. Without a session (Nostr) use the resolved contact.
        val sessionFingerprint = if (viaMesh) authenticatedFingerprint(meshTarget!!) else null
        val compareFingerprint = if (viaMesh) sessionFingerprint else currentFingerprint(resolution, null)
        val peerBlocked = isBlocked(
            sessionFingerprint,
            currentFingerprint(resolution, meshTarget),
            ContactIdentityResolver.fingerprintFromContactConversationId(conversationID)
        )
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (peerBlocked || isBlocked(entry.recipientFingerprint)) {
                Log.w(TAG, "Recipient blocked; not sending msg_id=${Redact.id(entry.messageID)}")
                iterator.remove()
                rememberRemovedLocked(entry.messageID)
                dropDurableLocked(entry.messageID)
                pendingEffects += Effect.Status(entry.messageID, DeliveryStatus.Failed(REASON_BLOCKED))
                continue
            }
            // Security finding 3: never resend to a different identity than the one addressed.
            val expected = entry.recipientFingerprint
            if (expected != null) {
                if (compareFingerprint == null) continue // identity unknown: keep waiting
                if (!expected.equals(compareFingerprint, ignoreCase = true)) {
                    Log.w(TAG, "Recipient identity changed; not resending msg_id=${Redact.id(entry.messageID)}")
                    iterator.remove()
                    dropDurableLocked(entry.messageID)
                    pendingEffects += Effect.Status(entry.messageID, DeliveryStatus.Failed(REASON_RECIPIENT_CHANGED))
                    continue
                }
            }
            if (viaMesh) {
                mesh.sendPrivateMessage(entry.content, meshTarget!!, entry.nickname, entry.messageID)
                iterator.remove()
                // The row stays (SENT) until a matching ACK, its 1 h lifetime, or the SENT cap.
                val row = durableRows[entry.messageID]
                if (row != null) {
                    row.state = OutboxState.SENT
                    val id = entry.messageID
                    val nextAttemptAt = clock() + RESEND_BACKOFF_MS[0]
                    submit { persistence.markSent(id, nextAttemptAt) }
                    if (row.recipientFingerprint == null && sessionFingerprint != null) {
                        // Security finding 5: bind backfilled/unknown rows to the authenticated peer.
                        row.recipientFingerprint = sessionFingerprint
                        submit { persistence.updateRecipient(id, null, sessionFingerprint) }
                    }
                    enforceSentCapLocked(row.conversationKey)
                }
            } else {
                nostr.sendPrivateMessage(entry.content, nostrTarget, entry.nickname, entry.messageID)
                iterator.remove()
                // D4: Nostr routes are not resent, so the durable row goes away.
                dropDurableLocked(entry.messageID)
                pendingEffects += Effect.Status(entry.messageID, DeliveryStatus.Sent)
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(conversationID, queued)
            outbox.remove(peerID, queued)
            retryState.remove(conversationID)
            retryState.remove(peerID)
        }
    }

    // Flush everything (rarely used)
    fun flushAllOutbox() {
        outbox.keys.toList().forEach { flushOutboxFor(it) }
    }

    /**
     * Adds [entry] to the RAM queue and, when [persistable], submits its durable QUEUED row.
     * Returns false (nothing queued, nothing evicted) when a D2 limit is reached. D2 counts
     * QUEUED rows only.
     */
    @Synchronized
    private fun enqueue(conversationID: String, entry: QueuedMessage, persistable: Boolean): Boolean {
        val queue = outbox[conversationID]
        if ((queue?.size ?: 0) >= PER_PEER_LIMIT) return false
        if (persistable) {
            val key = conversationID.lowercase()
            val queuedRows = durableRows.values.filter { it.state == OutboxState.QUEUED }
            if (queuedRows.size >= GLOBAL_LIMIT) return false
            if (queuedRows.count { it.conversationKey == key } >= PER_PEER_LIMIT) return false
            durableRows[entry.messageID] =
                DurableRow(key, OutboxState.QUEUED, entry.enqueuedAtMs, entry.recipientFingerprint)
            val row = entry.toOutboxEntry(conversationID, OutboxState.QUEUED, attempts = 0, nextAttemptAt = entry.enqueuedAtMs)
            submit { onPersisted(row, persistence.enqueue(row)) }
        }
        outbox.getOrPut(conversationID) { mutableListOf() }.add(entry)
        return true
    }

    /** MESH route: persist the SENT row (written before the transport send in queue order). */
    @Synchronized
    private fun persistSent(conversationID: String, entry: QueuedMessage) {
        val key = conversationID.lowercase()
        durableRows[entry.messageID] =
            DurableRow(key, OutboxState.SENT, entry.enqueuedAtMs, entry.recipientFingerprint)
        val row = entry.toOutboxEntry(
            conversationID,
            OutboxState.SENT,
            attempts = 1,
            nextAttemptAt = entry.enqueuedAtMs + RESEND_BACKOFF_MS[0]
        )
        submit { onPersisted(row, persistence.enqueue(row)) }
        enforceSentCapLocked(key)
    }

    /** SENT rows: own per-peer cap; the oldest is dropped silently (its status is kept). */
    private fun enforceSentCapLocked(conversationKey: String) {
        val sent = durableRows.entries
            .filter { it.value.conversationKey == conversationKey && it.value.state == OutboxState.SENT }
        if (sent.size <= SENT_PER_PEER_LIMIT) return
        sent.sortedBy { it.value.createdAt }
            .take(sent.size - SENT_PER_PEER_LIMIT)
            .map { it.key }
            .forEach { id ->
                Log.d(TAG, "SENT cap reached; dropping oldest row msg_id=${Redact.id(id)}")
                dropDurableLocked(id)
            }
    }

    private fun QueuedMessage.toOutboxEntry(
        conversationID: String,
        state: OutboxState,
        attempts: Int,
        nextAttemptAt: Long
    ) = OutboxEntry(
        messageId = messageID,
        conversationId = conversationID,
        toPeerId = conversationID,
        content = content,
        recipientNickname = nickname,
        originalTimestampMs = enqueuedAtMs,
        recipientFingerprint = recipientFingerprint,
        state = state,
        attempts = attempts,
        createdAt = enqueuedAtMs,
        nextAttemptAt = nextAttemptAt
    )

    /** Runs on the writer. The DB is authoritative for D2: undo the RAM entry on overflow. */
    private fun onPersisted(row: OutboxEntry, result: OutboxEnqueueResult) {
        if (result !is OutboxEnqueueResult.Overflow) return
        val failQueued = synchronized(this) {
            durableRows.remove(row.messageId)
            row.state == OutboxState.QUEUED && removeFromQueueLocked(row.messageId)
        }
        Log.w(TAG, "Outbox overflow (limit ${result.limit}) for msg_id=${Redact.id(row.messageId)}")
        if (failQueued) statusSink(row.messageId, DeliveryStatus.Failed(REASON_QUEUE_FULL))
    }

    private fun removeFromQueueLocked(messageID: String): Boolean {
        var removed = false
        outbox.entries.toList().forEach { (key, list) ->
            if (list.removeAll { it.messageID == messageID }) removed = true
            if (list.isEmpty()) {
                outbox.remove(key, list)
                retryState.remove(key)
            }
        }
        return removed
    }

    private fun isQueuedLocked(messageID: String): Boolean =
        outbox.values.any { list -> list.any { it.messageID == messageID } }

    /** Forget a durable row and submit its deletion (ordered after any earlier write). */
    private fun dropDurableLocked(messageID: String) {
        durableRows.remove(messageID)
        submit { persistence.remove(messageID) }
    }

    private fun rememberRemovedLocked(messageID: String) {
        removedIds.add(messageID)
        if (removedIds.size > ID_MEMORY_CAP) removedIds.remove(removedIds.first())
    }

    /**
     * DELIVERED/READ ACK (via AppStateStore). Security finding 4: the row is only removed when
     * [acknowledgedBy] maps to the row's conversation or recipient fingerprint. Before the startup
     * load the row is unknown, so the ACK is remembered (bounded) and checked by [rehydrate].
     */
    fun onMessageAcknowledged(messageID: String, acknowledgedBy: String) {
        synchronized(this) {
            val row = durableRows[messageID]
            when {
                row != null -> {
                    if (ackMatches(row.conversationKey, row.recipientFingerprint, acknowledgedBy)) {
                        removeFromQueueLocked(messageID)
                        dropDurableLocked(messageID)
                    } else {
                        Log.w(TAG, "Ignoring ACK from a different peer for msg_id=${Redact.id(messageID)}")
                    }
                }
                !reconcileDone -> {
                    recentlyAcked[messageID] = acknowledgedBy
                    if (recentlyAcked.size > ID_MEMORY_CAP) recentlyAcked.remove(recentlyAcked.keys.first())
                }
                // Not durable (e.g. a Nostr-alias RAM-only entry): nothing to bind against.
                else -> removeFromQueueLocked(messageID)
            }
        }
    }

    private fun ackMatches(conversationKey: String, recipientFingerprint: String?, acknowledgedBy: String): Boolean {
        val canonical = ContactDirectory.canonicalConversationId(acknowledgedBy).lowercase()
        if (canonical == conversationKey || acknowledgedBy.lowercase() == conversationKey) return true
        val ackerFingerprint = (try { mesh.getPeerFingerprint(acknowledgedBy) } catch (_: Exception) { null })
            ?: ContactIdentityResolver.fingerprintFromContactConversationId(canonical)
            ?: return false
        if (recipientFingerprint != null && ackerFingerprint.equals(recipientFingerprint, ignoreCase = true)) return true
        return ContactIdentityResolver.contactConversationIdForFingerprint(ackerFingerprint) == conversationKey
    }

    /** User deleted messages or a whole conversation. The DB rows are already deleted with them. */
    fun onMessagesRemoved(conversationID: String?, messageIDs: Collection<String>) {
        synchronized(this) {
            val ids = LinkedHashSet(messageIDs)
            if (conversationID != null) ids += conversationEntryIdsLocked(conversationID)
            ids.forEach { id ->
                rememberRemovedLocked(id)
                removeFromQueueLocked(id)
                if (durableRows.containsKey(id)) dropDurableLocked(id)
            }
        }
    }

    /**
     * The peer was blocked: drop every queued or awaiting-ACK message for that conversation and
     * mark it Failed. Hooked from PrivateChatManager.blockPeer.
     */
    fun dropConversation(peerOrConversationID: String, reason: String = REASON_BLOCKED) {
        synchronized(this) {
            conversationEntryIdsLocked(peerOrConversationID).forEach { id ->
                rememberRemovedLocked(id)
                removeFromQueueLocked(id)
                dropDurableLocked(id)
                pendingEffects += Effect.Status(id, DeliveryStatus.Failed(reason))
            }
        }
        dispatchEffects()
    }

    private fun conversationEntryIdsLocked(peerOrConversationID: String): Set<String> {
        val canonical = ContactDirectory.canonicalConversationId(peerOrConversationID).lowercase()
        val raw = peerOrConversationID.lowercase()
        val ids = LinkedHashSet<String>()
        outbox.forEach { (key, list) ->
            if (key.lowercase() == canonical || key.lowercase() == raw) list.forEach { ids += it.messageID }
        }
        durableRows.forEach { (id, row) -> if (row.conversationKey == canonical || row.conversationKey == raw) ids += id }
        return ids
    }

    /**
     * Restores the durable outbox after a process restart. Idempotent; the work runs on the
     * ordered writer and its repository calls queue on the repository's single-thread dispatcher
     * behind the startup history load (FIFO), so it never depends on storeState == Ready.
     * If the load fails it is retried on the next getInstance or scheduler tick.
     */
    fun rehydrate() {
        synchronized(this) {
            rehydrateRequested = true
            if (rehydrateWatermarkMs == 0L) rehydrateWatermarkMs = clock()
        }
        if (!rehydrateStarted.compareAndSet(false, true)) return
        submit { rehydrateNow() }
    }

    private fun retryRehydrateIfNeeded() {
        val retry = synchronized(this) { rehydrateRequested && !reconcileDone }
        if (retry && !rehydrateStarted.get()) rehydrate()
    }

    private suspend fun rehydrateNow() {
        val startGeneration = generation
        val watermark = synchronized(this) { rehydrateWatermarkMs }
        // Reconcile first: drop rows whose message is gone or already acknowledged, and find
        // Sending rows (older than the watermark) that never got an outbox row.
        val reconcile = try {
            persistence.reconcile(watermark)
        } catch (e: Exception) {
            Log.w(TAG, "Outbox reconcile failed: ${e.javaClass.simpleName}")
            null
        }
        val load = try {
            persistence.loadAll()
        } catch (e: Exception) {
            Log.w(TAG, "Outbox load failed: ${e.javaClass.simpleName}; will retry")
            rehydrateStarted.set(false)
            return
        }
        val failed = LinkedHashMap<String, String>()
        val toRemove = LinkedHashSet<String>()
        val reKeyed = ArrayList<Pair<String, String>>()
        var restored = 0
        val now = clock()
        synchronized(this) {
            // Panic raced the load: restore nothing.
            if (startGeneration != generation) return
            // The store has already deleted corrupt rows; remove again in case of another backend.
            load.corrupt.forEach {
                toRemove += it.messageId
                failed[it.messageId] = REASON_NOT_DELIVERED
            }
            for (row in load.entries) {
                val id = row.messageId
                if (ContactIdentityResolver.isNostrAlias(row.conversationId) ||
                    ContactIdentityResolver.isNostrAlias(row.toPeerId)
                ) {
                    toRemove += id
                    failed[id] = REASON_NOT_DELIVERED
                    continue
                }
                // Deleted/blocked in this process while the load ran: never resurrect.
                if (id in removedIds) {
                    toRemove += id
                    continue
                }
                val conversationID = ContactDirectory.canonicalConversationId(row.conversationId)
                val conversationKey = conversationID.lowercase()
                val ackedBy = recentlyAcked[id]
                if (ackedBy != null && ackMatches(conversationKey, row.recipientFingerprint, ackedBy)) {
                    toRemove += id
                    continue
                }
                // Already handled in this process (queued or sent since start).
                if (durableRows.containsKey(id) || isQueuedLocked(id)) continue
                // D1 amendment: an expired SENT row is dropped silently; its status is kept.
                if (row.state == OutboxState.SENT && now - row.createdAt > OUTBOX_EXPIRY_MS) {
                    toRemove += id
                    continue
                }
                if (isBlocked(row.recipientFingerprint, ContactIdentityResolver.fingerprintFromContactConversationId(conversationKey))) {
                    toRemove += id
                    rememberRemovedLocked(id)
                    failed[id] = REASON_BLOCKED
                    continue
                }
                if (!conversationID.equals(row.conversationId, ignoreCase = true)) reKeyed += id to conversationID
                durableRows[id] = DurableRow(conversationKey, row.state, row.createdAt, row.recipientFingerprint)
                // SENT rows are queued again: we cannot know whether the ACK arrived, and a
                // resend with the same message ID is deduplicated by the receiver.
                outbox.getOrPut(conversationID) { mutableListOf() }.add(
                    QueuedMessage(
                        content = row.content,
                        nickname = row.recipientNickname,
                        messageID = id,
                        enqueuedAtMs = row.createdAt,
                        recipientFingerprint = row.recipientFingerprint,
                        sentBefore = row.state == OutboxState.SENT
                    )
                )
                restored++
            }
            outbox.values.forEach { list -> list.sortBy { it.enqueuedAtMs } }
            reconcile?.orphanSendingMessageIds
                ?.filterNot { durableRows.containsKey(it) || isQueuedLocked(it) }
                ?.forEach { failed.putIfAbsent(it, REASON_NOT_DELIVERED) }
            reconcileDone = true
        }
        toRemove.forEach { persistence.remove(it) }
        reKeyed.forEach { (id, conversationID) -> persistence.updateRecipient(id, conversationID, null) }
        Log.i(
            TAG,
            "Outbox rehydrated: restored=$restored failed=${failed.size} " +
                "dropped=${(reconcile?.droppedOutboxMessageIds?.size ?: 0) + toRemove.size}"
        )
        failed.forEach { (id, reason) -> statusSink(id, DeliveryStatus.Failed(reason)) }
    }

    private fun dispatchEffects() {
        val effects = synchronized(this) {
            if (pendingEffects.isEmpty()) return
            ArrayList(pendingEffects).also { pendingEffects.clear() }
        }
        effects.forEach { effect ->
            when (effect) {
                is Effect.Status -> try { statusSink(effect.messageID, effect.status) } catch (_: Exception) { }
                is Effect.Expired -> try { onMessageExpired?.invoke(effect.messageID) } catch (_: Exception) { }
            }
        }
    }

    /**
     * Initiate a Noise handshake for a conversation with queued messages, applying
     * exponential backoff between attempts. [immediate] resets the backoff (peer just
     * appeared or a new message was queued). Kicks are suppressed while a previous
     * attempt is still inside its backoff window, so alias duplicates and frequent
     * peer-list updates cannot spam handshakes.
     */
    @Synchronized
    private fun kickHandshake(conversationID: String, meshTarget: String, immediate: Boolean) {
        val now = clock()
        val current = retryState[conversationID]
        if (current != null && now < current.nextHandshakeAttemptAtMs) return
        val attempts = if (immediate) 0 else (current?.handshakeAttempts ?: 0)
        try { mesh.initiateNoiseHandshake(meshTarget) } catch (_: Exception) { }
        val backoff = HANDSHAKE_RETRY_BACKOFF_MS[attempts.coerceAtMost(HANDSHAKE_RETRY_BACKOFF_MS.size - 1)]
        retryState[conversationID] = ConversationRetry(
            handshakeAttempts = attempts + 1,
            nextHandshakeAttemptAtMs = now + backoff
        )
        Log.d(TAG, "Handshake attempt ${attempts + 1} for ${Redact.id(conversationID)}, next retry in ${backoff}ms")
    }

    @Synchronized
    private fun startOutboxScheduler() {
        if (disableSchedulerForTesting) return
        if (schedulerJob?.isActive == true) return
        schedulerJob = schedulerScope.launch {
            while (isActive) {
                delay(OUTBOX_TICK_MS)
                try { tickOutbox() } catch (e: Exception) {
                    Log.w(TAG, "Outbox scheduler tick failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop retrying while the mesh transports are down. Persistent network work must
     * follow the MeshForegroundService lifecycle; getInstance restarts the scheduler
     * and rebinds the mesh reference when the service comes back.
     */
    fun stopOutboxScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    internal val isSchedulerRunning: Boolean get() = schedulerJob?.isActive == true

    /**
     * One scheduler pass over the outbox: expire old entries and SENT rows, flush what can be
     * sent, and re-initiate handshakes (with backoff) for peers that are connected but have no
     * established session yet. Also retries a failed startup load.
     */
    internal fun tickOutbox(nowMs: Long = clock()) {
        retryRehydrateIfNeeded()
        synchronized(this) { tickLocked(nowMs) }
        dispatchEffects()
    }

    private fun tickLocked(nowMs: Long) {
        expireSentRowsLocked(nowMs)
        outbox.keys.toList().forEach { conversationID ->
            expireOldEntriesLocked(conversationID, nowMs)
            val queued = outbox[conversationID] ?: return@forEach
            if (queued.isEmpty()) return@forEach

            val resolution = ContactDirectory.resolve(conversationID)
            val meshTarget = resolution.meshPeerID

            if (meshTarget != null && isReady(mesh, meshTarget)) {
                flushLocked(conversationID)
                return@forEach
            }
            if (canSendViaNostr(resolution.noiseKeyHex ?: conversationID)) {
                flushLocked(conversationID)
                return@forEach
            }
            // Peer visible but no session: retry the handshake with backoff.
            if (meshTarget != null && isConnected(mesh, meshTarget)) {
                kickHandshake(conversationID, meshTarget, immediate = false)
            }
        }
    }

    /** D1 amendment: SENT rows awaiting an ACK are dropped silently 1 h after creation. */
    private fun expireSentRowsLocked(nowMs: Long) {
        durableRows.entries
            .filter { it.value.state == OutboxState.SENT && nowMs - it.value.createdAt > OUTBOX_EXPIRY_MS }
            .map { it.key }
            .filterNot { isQueuedLocked(it) } // rehydrated SENT entries expire via the queue below
            .forEach { dropDurableLocked(it) }
    }

    /**
     * D1: queued messages older than OUTBOX_EXPIRY_MS (from creation) become Failed. Entries
     * restored from SENT rows were already handed to a transport, so they are dropped silently.
     */
    private fun expireOldEntriesLocked(conversationID: String, nowMs: Long) {
        val queued = outbox[conversationID] ?: return
        val iterator = queued.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.enqueuedAtMs > OUTBOX_EXPIRY_MS) {
                Log.w(TAG, "Expiring queued PM for ${Redact.id(conversationID)} msg_id=${Redact.id(entry.messageID)}")
                iterator.remove()
                dropDurableLocked(entry.messageID)
                if (!entry.sentBefore) {
                    pendingEffects += Effect.Status(entry.messageID, DeliveryStatus.Failed(REASON_NOT_DELIVERED))
                    pendingEffects += Effect.Expired(entry.messageID)
                }
            }
        }
        if (queued.isEmpty()) {
            outbox.remove(conversationID, queued)
            retryState.remove(conversationID)
        }
    }

    /** Fingerprint bound by a completed Noise handshake (PeerFingerprintManager), if any. */
    private fun authenticatedFingerprint(meshPeerID: String): String? =
        try { mesh.getPeerFingerprint(meshPeerID)?.lowercase() } catch (_: Exception) { null }

    /**
     * Fingerprint of the identity currently behind [resolution]: the live mesh peer's announced
     * Noise key, then the resolved favourite/contact key, then the fingerprint embedded in a
     * contact_ ID. Null when unknown. Used to address queued messages, not to authorize a resend
     * over a mesh session (that uses [authenticatedFingerprint]).
     */
    private fun currentFingerprint(resolution: ContactDirectory.ContactResolution, meshTarget: String?): String? {
        val liveKey = meshTarget?.let {
            try { mesh.getPeerInfo(it)?.noisePublicKey } catch (_: Exception) { null }
        }
        val key = liveKey ?: resolution.noisePublicKey
        if (key != null) return ContactIdentityResolver.fingerprintHex(key).lowercase()
        return ContactIdentityResolver.fingerprintFromContactConversationId(resolution.conversationID)
    }

    private fun isBlocked(vararg fingerprints: String?): Boolean =
        fingerprints.any { fingerprint ->
            fingerprint != null && try { isFingerprintBlocked(fingerprint) } catch (_: Exception) { false }
        }

    private fun canSendViaNostr(peerID: String): Boolean {
        // MeshUp: Internet opt-in gate (Decision 013) - OFF means offline favourites take the QUEUED path
        if (!internetEnabled()) return false
        return try {
            val resolution = ContactDirectory.resolve(peerID)
            if (resolution.isMutualFavorite && resolution.nostrPubkey != null) return true
            val target = resolution.noiseKeyHex ?: peerID
            if (ContactIdentityResolver.isNoiseKeyHex(target)) {
                val noiseKey = ContactIdentityResolver.bytesFromHex(target) ?: return false
                val fav = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else if (ContactIdentityResolver.isMeshPeerId(target)) {
                val fav = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(target)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            } else {
                false
            }
        } catch (_: Exception) { false }
    }

    private fun isConnected(service: MeshService, peerID: String): Boolean {
        return try {
            service.getPeerInfo(peerID)?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    private fun isReady(service: MeshService, peerID: String): Boolean {
        return try {
            service.getPeerInfo(peerID)?.isConnected == true &&
                service.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    // Called when mesh peer list changes; attempt to flush any matching outbox entries
    fun onPeersUpdated(peers: List<String>) {
        peers.forEach { pid ->
            kickHandshakeIfPending(pid)
            flushOutboxFor(pid)
            val noiseHex = try {
                mesh.getPeerInfo(pid)?.noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
            } catch (_: Exception) { null }
            noiseHex?.let {
                kickHandshakeIfPending(it)
                flushOutboxFor(it)
            }
        }
    }

    // Called when a Noise session becomes established; flush both the mesh peerID and its noiseHex alias
    fun onSessionEstablished(peerID: String) {
        resetRetry(peerID)
        flushOutboxFor(peerID)
        val noiseHex = try {
            mesh.getPeerInfo(peerID)?.noisePublicKey?.let { ContactIdentityResolver.noiseKeyHex(it) }
        } catch (_: Exception) { null }
        noiseHex?.let {
            resetRetry(it)
            flushOutboxFor(it)
        }
    }

    /** Reset handshake backoff for a conversation whose session just came up. */
    private fun resetRetry(peerID: String) {
        retryState.remove(ContactDirectory.canonicalConversationId(peerID))
        retryState.remove(peerID)
    }

    /**
     * A peer (re)appeared: if we still owe them queued messages and there is no working
     * session yet, restart the handshake immediately instead of waiting for the backoff.
     */
    @Synchronized
    private fun kickHandshakeIfPending(peerID: String) {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val queued = outbox[conversationID] ?: outbox[peerID] ?: return
        if (queued.isEmpty()) return
        val resolution = ContactDirectory.resolve(conversationID)
        val meshTarget = resolution.meshPeerID ?: return
        if (isReady(mesh, meshTarget)) return
        if (!isConnected(mesh, meshTarget)) return
        Log.d(TAG, "Peer ${Redact.id(meshTarget)} reappeared with ${queued.size} queued PM(s); re-initiating handshake")
        kickHandshake(conversationID, meshTarget, immediate = true)
    }
}
