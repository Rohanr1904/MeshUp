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
 * - MESH: row persisted as SENT; removed by a matching DELIVERED/READ ACK, as
 *   Failed("No delivery confirmation") after OUTBOX_EXPIRY_MS from creation (amendment 3), or
 *   silently (status kept) by the per-peer SENT cap (oldest first).
 * - QUEUED: row persisted as QUEUED; expires to Failed after OUTBOX_EXPIRY_MS from creation (D1).
 * - NOSTR routes and Nostr-alias conversations are never persisted (D4); a queued row that is
 *   flushed via Nostr is removed.
 * - Limits (D2): 200 QUEUED per conversation, 2000 QUEUED global; overflow is Failed("queue full").
 * - [rehydrate] reloads rows after a restart. It is called once per process by the mesh owner
 *   (MeshServiceHolder.getUnifiedOrCreate), never by the UI, and retried if the load fails.
 *
 * P2-PR9 (Decision 015 D3/D5 + amendment, R-1):
 * - Transmission: mesh sends are collected under the monitor and handed to
 *   [MeshService.sendPrivateMessageReporting] after it is released (the transport may take Noise
 *   locks whose holders call back into the router). The row is advanced optimistically (SENT,
 *   attempt counted) before the send, so an ACK can always bind to it. If the transport reports
 *   that no session was established (the session race), the row is put back exactly as it was:
 *   a first transmission returns to QUEUED, a resend keeps SENT with the attempt not consumed,
 *   and a handshake is kicked through the normal backoff.
 * - D3 resend: a SENT row with no ACK is resent with the SAME message ID (re-encrypted under the
 *   current session) when `next_attempt_at` is due. Counting the first transmission as attempt 1,
 *   the wait after transmission n is OUTBOX_RESEND_BACKOFF_MS[min(n, size) - 1]: resends at
 *   +30 s, then +1 min, +2 min and +2 min after the previous one (the last resend is about
 *   5.5 min after the first send). After the last resend the row gets one final wait (the last
 *   backoff value, 2 min); with still no ACK it becomes Failed("No delivery confirmation") and
 *   is removed. A due resend is only made when the peer is ready, unblocked and its authenticated
 *   identity still matches; an unreachable peer consumes no attempt (the row waits for the next
 *   tick on which it is ready).
 * - D3 vs the 1 h bound: expiry is checked first on every tick. A SENT row older than
 *   OUTBOX_EXPIRY_MS is removed and becomes Failed("No delivery confirmation"), even when attempts
 *   remain (e.g. a restored row, or a peer that stayed unreachable), so Retry (D5) is offered
 *   (Decision 015 amendment 3; it replaced the silent drop). The D3 Failed for a row that used
 *   up its schedule inside the hour remains the normal case (about 7.5 min).
 * - D5 [retry]: a Failed own private text message still in history is queued again with the
 *   same ID and its expected recipient identity, then flushed (limits, identity and block checks
 *   apply). A retry restarts both the 1 h bound and the D3 schedule. Refused after "Recipient
 *   changed", for blocked peers, Nostr/geohash aliases (they keep their own path) and while a
 *   panic wipe is in progress.
 * - Status: a first mesh transmission the transport accepted moves Sending to Sent (monotonic).
 * - Lock order: no transport/Noise operation (send, handshake) runs under this monitor; they are
 *   collected and drained after release. Read-only peer lookups (getPeerInfo,
 *   hasEstablishedSession, getPeerFingerprint) are lock-free map reads in the transports.
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
        /** Restored from a SENT row: expiry fails it as "No delivery confirmation" (amendment 3). */
        val sentBefore: Boolean = false
    )

    /** RAM mirror of one durable row. */
    private data class DurableRow(
        val conversationKey: String,
        var state: OutboxState,
        val createdAt: Long,
        var recipientFingerprint: String?,
        /** Conversation the message is routed through (P2-PR9 resend target). */
        val conversationID: String,
        /** What to resend (P2-PR9, D3). */
        val message: QueuedMessage,
        /** Transmissions so far (D3); 0 while QUEUED. */
        var attempts: Int = 0,
        var nextAttemptAt: Long = 0L
    )

    /**
     * One mesh transmission collected under the monitor and performed after it is released.
     * The `prev*` snapshot restores the row if the transport reports no session (R-1).
     */
    private data class Transmit(
        val messageID: String,
        val conversationID: String,
        val meshTarget: String,
        val message: QueuedMessage,
        val generation: Long,
        /** Put back into the RAM queue on refusal (first transmission or restored row). */
        val requeue: Boolean,
        /** Durable state before the send; null for RAM-only (non-persistable) entries. */
        val prevState: OutboxState?,
        val prevAttempts: Int,
        val prevNextAttemptAt: Long
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
        internal const val REASON_NO_CONFIRMATION = "No delivery confirmation"

        /** D3: wait after the [attempts]-th transmission (the last value is the final ACK wait). */
        internal fun resendDelayAfter(attempts: Int): Long =
            RESEND_BACKOFF_MS[(attempts - 1).coerceIn(0, RESEND_BACKOFF_MS.size - 1)]

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

    // Recipient fingerprint of rows dropped in this process (bounded): the D5 retry identity.
    private val lastFingerprints = LinkedHashMap<String, String>()

    // Test hook: runs between collecting sends (under the monitor) and transmitting them.
    internal var beforeTransmitForTesting: (() -> Unit)? = null

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

    // Mesh transmissions collected under the monitor, performed after releasing it (P2-PR9).
    private val pendingTransmits = ArrayList<Transmit>()

    // Handshake kicks collected under the monitor, started after releasing it (P2-PR9).
    private val pendingHandshakes = LinkedHashSet<String>()

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
            pendingTransmits.clear()
            pendingHandshakes.clear()
            lastFingerprints.clear()
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
            val fingerprint = authenticatedFingerprint(meshTarget) ?: currentFingerprint(resolution, meshTarget)
            val entry = QueuedMessage(content, recipientNickname, messageID, clock(), fingerprint)
            synchronized(this) {
                if (persistable) persistSent(conversationID, entry)
                // A refused send (no session) goes back to QUEUED with no attempt used.
                pendingTransmits += Transmit(
                    messageID, conversationID, meshTarget, entry, generation,
                    requeue = true,
                    prevState = if (persistable) OutboxState.QUEUED else null,
                    prevAttempts = 0,
                    prevNextAttemptAt = entry.enqueuedAtMs
                )
            }
            drainTransmits()
            dispatchEffects()
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
            dispatchEffects()
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
        drainTransmits()
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
                iterator.remove()
                // The row stays (SENT) until a matching ACK, D3 Failed, its 1 h lifetime, or the
                // SENT cap. It is advanced before the send so an ACK always finds it, and put back
                // by [onTransmitRefused] if the transport had no session after all.
                val row = durableRows[entry.messageID]
                pendingTransmits += Transmit(
                    entry.messageID, key, meshTarget!!, entry, generation,
                    requeue = true,
                    prevState = row?.state,
                    prevAttempts = row?.attempts ?: 0,
                    prevNextAttemptAt = row?.nextAttemptAt ?: 0L
                )
                if (row != null) {
                    val id = entry.messageID
                    // A restored SENT row is a D3 resend; a QUEUED row's first send is attempt 1.
                    val attempts = if (row.state == OutboxState.SENT) row.attempts + 1 else 1
                    val nextAttemptAt = clock() + resendDelayAfter(attempts)
                    if (row.state == OutboxState.SENT) {
                        submit { persistence.recordAttempt(id, attempts, nextAttemptAt, OutboxError.NO_ACK) }
                    } else {
                        row.state = OutboxState.SENT
                        submit { persistence.markSent(id, nextAttemptAt) }
                    }
                    row.attempts = attempts
                    row.nextAttemptAt = nextAttemptAt
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
            durableRows[entry.messageID] = DurableRow(
                key, OutboxState.QUEUED, entry.enqueuedAtMs, entry.recipientFingerprint,
                conversationID, entry, attempts = 0, nextAttemptAt = entry.enqueuedAtMs
            )
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
        val nextAttemptAt = entry.enqueuedAtMs + resendDelayAfter(1)
        durableRows[entry.messageID] = DurableRow(
            key, OutboxState.SENT, entry.enqueuedAtMs, entry.recipientFingerprint,
            conversationID, entry, attempts = 1, nextAttemptAt = nextAttemptAt
        )
        val row = entry.toOutboxEntry(
            conversationID,
            OutboxState.SENT,
            attempts = 1,
            nextAttemptAt = nextAttemptAt
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
        // Remember the identity it was bound to, so a D5 retry is checked against it.
        durableRows.remove(messageID)?.recipientFingerprint?.let { fingerprint ->
            lastFingerprints[messageID] = fingerprint
            if (lastFingerprints.size > ID_MEMORY_CAP) lastFingerprints.remove(lastFingerprints.keys.first())
        }
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
                if (isBlocked(row.recipientFingerprint, ContactIdentityResolver.fingerprintFromContactConversationId(conversationKey))) {
                    toRemove += id
                    rememberRemovedLocked(id)
                    failed[id] = REASON_BLOCKED
                    continue
                }
                // Amendment 3: an expired SENT row is removed and fails, so Retry is offered.
                if (row.state == OutboxState.SENT && now - row.createdAt > OUTBOX_EXPIRY_MS) {
                    toRemove += id
                    failed[id] = REASON_NO_CONFIRMATION
                    // Keep the identity it was bound to, so a D5 retry is checked against it.
                    row.recipientFingerprint?.let { fingerprint ->
                        lastFingerprints[id] = fingerprint
                        if (lastFingerprints.size > ID_MEMORY_CAP) lastFingerprints.remove(lastFingerprints.keys.first())
                    }
                    continue
                }
                if (!conversationID.equals(row.conversationId, ignoreCase = true)) reKeyed += id to conversationID
                val sent = row.state == OutboxState.SENT
                val message = QueuedMessage(
                    content = row.content,
                    nickname = row.recipientNickname,
                    messageID = id,
                    enqueuedAtMs = row.createdAt,
                    recipientFingerprint = row.recipientFingerprint,
                    sentBefore = sent
                )
                // A SENT row was transmitted at least once (P2-PR8 markSent left attempts at 0).
                val attempts = if (sent) maxOf(1, row.attempts) else 0
                durableRows[id] = DurableRow(
                    conversationKey, row.state, row.createdAt, row.recipientFingerprint,
                    conversationID, message, attempts, row.nextAttemptAt
                )
                // SENT rows are queued again: we cannot know whether the ACK arrived, and a
                // resend with the same message ID is deduplicated by the receiver. A SENT row
                // whose D3 schedule is used up is not resent; the tick fails it when due.
                if (!sent || attempts <= RESEND_BACKOFF_MS.size) {
                    outbox.getOrPut(conversationID) { mutableListOf() }.add(message)
                }
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

    /**
     * Delivers what was collected under the monitor: handshake kicks first, then status/expiry
     * effects. Must never be called while holding the monitor (lock order: the Noise session
     * manager calls back into the router while holding its own lock).
     */
    private fun dispatchEffects() {
        drainHandshakes()
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
        // Backoff bookkeeping stays under the monitor; the handshake itself is started by
        // [drainHandshakes] after the monitor is released (P2-PR9 lock-order fix).
        pendingHandshakes += meshTarget
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
        drainTransmits()
        dispatchEffects()
    }

    private fun tickLocked(nowMs: Long) {
        // Order matters: the 1 h bound (amendment 3) wins over a D3 resend.
        expireSentRowsLocked(nowMs)
        resendDueLocked(nowMs)
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

    /** Amendment 3: SENT rows still awaiting an ACK 1 h after creation are removed and fail. */
    private fun expireSentRowsLocked(nowMs: Long) {
        durableRows.entries
            .filter { it.value.state == OutboxState.SENT && nowMs - it.value.createdAt > OUTBOX_EXPIRY_MS }
            .map { it.key }
            .filterNot { isQueuedLocked(it) } // rehydrated SENT entries expire via the queue below
            .forEach { id ->
                Log.w(TAG, "No delivery confirmation within 1 h msg_id=${Redact.id(id)}")
                dropDurableLocked(id)
                pendingEffects += Effect.Status(id, DeliveryStatus.Failed(REASON_NO_CONFIRMATION))
            }
    }

    /**
     * D3: resend due SENT rows that are not waiting in the RAM queue (restored rows are resent by
     * the flush). Mesh only (D4: Nostr routes never get a SENT row). Runs after
     * [expireSentRowsLocked], so rows past the 1 h bound never reach this point.
     */
    private fun resendDueLocked(nowMs: Long) {
        val due = durableRows.entries
            .filter { (id, row) ->
                row.state == OutboxState.SENT && row.nextAttemptAt <= nowMs && !isQueuedLocked(id)
            }
            .map { it.key }
        for (id in due) {
            val row = durableRows[id] ?: continue
            if (row.attempts > RESEND_BACKOFF_MS.size) {
                // Schedule used up and the final wait elapsed without an ACK (D3).
                Log.w(TAG, "No delivery confirmation after ${row.attempts} sends msg_id=${Redact.id(id)}")
                dropDurableLocked(id)
                pendingEffects += Effect.Status(id, DeliveryStatus.Failed(REASON_NO_CONFIRMATION))
                continue
            }
            val resolution = ContactDirectory.resolve(row.conversationID)
            val meshTarget = resolution.meshPeerID
            if (meshTarget == null || !isReady(mesh, meshTarget)) {
                // Unreachable: no attempt is consumed; still bounded by the 1 h expiry.
                if (meshTarget != null && isConnected(mesh, meshTarget)) {
                    kickHandshake(ContactDirectory.canonicalConversationId(row.conversationID), meshTarget, immediate = false)
                }
                continue
            }
            val sessionFingerprint = authenticatedFingerprint(meshTarget)
            if (isBlocked(
                    sessionFingerprint,
                    row.recipientFingerprint,
                    currentFingerprint(resolution, meshTarget),
                    ContactIdentityResolver.fingerprintFromContactConversationId(row.conversationKey)
                )
            ) {
                Log.w(TAG, "Recipient blocked; not resending msg_id=${Redact.id(id)}")
                rememberRemovedLocked(id)
                dropDurableLocked(id)
                pendingEffects += Effect.Status(id, DeliveryStatus.Failed(REASON_BLOCKED))
                continue
            }
            val expected = row.recipientFingerprint
            if (expected != null) {
                if (sessionFingerprint == null) continue // identity unknown: keep waiting
                if (!expected.equals(sessionFingerprint, ignoreCase = true)) {
                    Log.w(TAG, "Recipient identity changed; not resending msg_id=${Redact.id(id)}")
                    dropDurableLocked(id)
                    pendingEffects += Effect.Status(id, DeliveryStatus.Failed(REASON_RECIPIENT_CHANGED))
                    continue
                }
            } else if (sessionFingerprint != null) {
                row.recipientFingerprint = sessionFingerprint
                submit { persistence.updateRecipient(id, null, sessionFingerprint) }
            }
            pendingTransmits += Transmit(
                id, row.conversationID, meshTarget, row.message, generation,
                requeue = false,
                prevState = OutboxState.SENT,
                prevAttempts = row.attempts,
                prevNextAttemptAt = row.nextAttemptAt
            )
            val attempts = row.attempts + 1
            val nextAttemptAt = nowMs + resendDelayAfter(attempts)
            row.attempts = attempts
            row.nextAttemptAt = nextAttemptAt
            Log.d(TAG, "D3 resend $attempts for msg_id=${Redact.id(id)}")
            submit { persistence.recordAttempt(id, attempts, nextAttemptAt, OutboxError.NO_ACK) }
        }
    }

    /**
     * Hands the collected transmissions to the transport. Never called under the monitor: the
     * transport may report synchronously, and its session locks are taken by threads that call
     * back into the router (onSessionEstablished).
     *
     * Each send is re-validated right before it goes out (P2-PR9 review): the panic generation,
     * a delete/block since collection, the durable row still awaiting it, the block list and the
     * authenticated recipient identity. Peer reads happen outside the monitor, the decision is
     * taken under it, and the send itself happens outside it again.
     */
    private fun drainTransmits() {
        val batch = synchronized(this) {
            if (pendingTransmits.isEmpty()) return
            ArrayList(pendingTransmits).also { pendingTransmits.clear() }
        }
        beforeTransmitForTesting?.invoke()
        val service = mesh
        batch.forEach { transmit ->
            val sessionFingerprint = authenticatedFingerprint(transmit.meshTarget)
            val contactFingerprint = ContactIdentityResolver.fingerprintFromContactConversationId(
                ContactDirectory.canonicalConversationId(transmit.conversationID)
            )
            if (!stillSendable(transmit, sessionFingerprint, contactFingerprint)) return@forEach
            try {
                service.sendPrivateMessageReporting(
                    transmit.message.content,
                    transmit.meshTarget,
                    transmit.message.nickname,
                    transmit.messageID
                ) { sent -> if (sent) onTransmitted(transmit) else onTransmitRefused(transmit) }
            } catch (e: Exception) {
                // Kept as sent: D3 resends (or the 1 h bound) take it from here.
                Log.w(TAG, "Mesh send failed: ${e.javaClass.simpleName} msg_id=${Redact.id(transmit.messageID)}")
            }
        }
        dispatchEffects()
    }

    /** Drain-time re-check (see [drainTransmits]); drops the send and its row when stale. */
    private fun stillSendable(
        transmit: Transmit,
        sessionFingerprint: String?,
        contactFingerprint: String?
    ): Boolean = synchronized(this) {
        val id = transmit.messageID
        if (transmit.generation != generation || id in removedIds) return false
        val row = durableRows[id]
        // A durable send must still be awaited by its (optimistically SENT) row: an ACK, delete,
        // expiry or the SENT cap since collection cancels it.
        if (transmit.prevState != null && (row == null || row.state != OutboxState.SENT)) return false
        val expected = row?.recipientFingerprint ?: transmit.message.recipientFingerprint
        if (isBlocked(sessionFingerprint, expected, contactFingerprint)) {
            Log.w(TAG, "Recipient blocked before send; dropping msg_id=${Redact.id(id)}")
            rememberRemovedLocked(id)
            removeFromQueueLocked(id)
            if (row != null) dropDurableLocked(id)
            pendingEffects += Effect.Status(id, DeliveryStatus.Failed(REASON_BLOCKED))
            return false
        }
        if (expected != null && sessionFingerprint != null && !expected.equals(sessionFingerprint, ignoreCase = true)) {
            Log.w(TAG, "Recipient identity changed before send; dropping msg_id=${Redact.id(id)}")
            removeFromQueueLocked(id)
            if (row != null) dropDurableLocked(id)
            pendingEffects += Effect.Status(id, DeliveryStatus.Failed(REASON_RECIPIENT_CHANGED))
            return false
        }
        true
    }

    /**
     * The transport accepted the message. A first transmission moves the status to Sent (monotonic
     * in AppStateStore: Delivered/Read are never downgraded). Not emitted when the row has gone
     * meanwhile (ACK, delete, Failed), so a late callback cannot overwrite a newer state.
     */
    private fun onTransmitted(transmit: Transmit) {
        if (transmit.prevState == OutboxState.SENT) return // D3 resend: status unchanged
        val emit = synchronized(this) {
            if (transmit.generation != generation || transmit.messageID in removedIds) return
            transmit.prevState == null || durableRows[transmit.messageID]?.state == OutboxState.SENT
        }
        if (emit) try { statusSink(transmit.messageID, DeliveryStatus.Sent) } catch (_: Exception) { }
    }

    /**
     * R-1 session race: the transport had no established session, so nothing went out. Restore
     * the row to its pre-send state (no D3 attempt consumed) and kick the handshake the same way
     * the queue path does; the entry is sent once the session is established. A refusal that
     * arrives after an ACK, delete, block or panic restores nothing.
     */
    private fun onTransmitRefused(transmit: Transmit) {
        val connected = isConnected(mesh, transmit.meshTarget) // peer read outside the monitor
        synchronized(this) {
            val id = transmit.messageID
            if (transmit.generation != generation || id in removedIds) return
            Log.d(TAG, "No session at send time; re-queueing msg_id=${Redact.id(id)}")
            if (transmit.prevState != null) {
                // Acked, deleted, expired or capped meanwhile: nothing to restore.
                val row = durableRows[id] ?: return
                row.attempts = transmit.prevAttempts
                row.nextAttemptAt = transmit.prevNextAttemptAt
                val attempts = transmit.prevAttempts
                val nextAttemptAt = transmit.prevNextAttemptAt
                if (transmit.prevState == OutboxState.QUEUED) {
                    row.state = OutboxState.QUEUED
                    submit { persistence.markQueued(id, nextAttemptAt) }
                } else {
                    submit { persistence.recordAttempt(id, attempts, nextAttemptAt, OutboxError.NO_SESSION) }
                }
            }
            if (transmit.requeue && !isQueuedLocked(id)) {
                outbox.getOrPut(transmit.conversationID) { mutableListOf() }.apply {
                    add(transmit.message)
                    sortBy { it.enqueuedAtMs }
                }
            }
            if (connected) {
                kickHandshake(
                    ContactDirectory.canonicalConversationId(transmit.conversationID),
                    transmit.meshTarget,
                    immediate = true
                )
            }
        }
        dispatchEffects()
    }

    /**
     * D5: re-send a Failed own private text message with the SAME message ID. Receivers dedupe by
     * ID, so a retry of a message that was delivered but never acknowledged is harmless.
     *
     * The retry is a new delivery cycle: it is queued with a fresh creation time, so the 1 h
     * bound (D1) and the D3 schedule both restart, and it is then flushed through the normal
     * path. The queued entry carries the expected recipient identity (the fingerprint remembered
     * from the failed row, else the one embedded in a contact_ conversation ID, else the currently
     * authenticated one, which then gets bound), so the flush applies the same identity check as
     * the first send and D3 (mismatch: Failed("Recipient changed"); unknown: keep waiting).
     *
     * Returns QUEUED when accepted (the flush may already have sent it), FAILED when the queue is
     * full (D2), or null when refused: not in loaded history, not Failed, not an own private text
     * message, failed because the recipient changed, a blocked peer, a Nostr/geohash alias
     * conversation (its Failed stays), or a panic wipe in progress.
     *
     * A Failed message whose row is still in the outbox (the transport marked it Failed, e.g. an
     * encryption error, while the row awaited D3) is retryable: the stale row is replaced.
     *     */
    fun retry(messageID: String): RouteResult? {
        if (admissionPaused) {
            Log.w(TAG, "Retry rejected during panic wipe")
            return null
        }
        val myPeerID = (try { mesh.myPeerID } catch (_: Exception) { null }) ?: return null
        // Peer and identity reads happen before the monitor is taken.
        val conversationKey = AppStateStore.privateMessages.value.entries
            .firstOrNull { (_, list) -> list.any { it.id == messageID } }
            ?.key
            ?: return null
        if (com.bitchat.android.nostr.GeohashAliasRegistry.contains(conversationKey) ||
            ContactIdentityResolver.isNostrAlias(conversationKey)
        ) return null
        val resolution = ContactDirectory.resolve(conversationKey)
        val conversationID = resolution.conversationID
        if (ContactIdentityResolver.isNostrAlias(conversationID)) return null
        val meshTarget = resolution.meshPeerID
        val sessionFingerprint = meshTarget?.let { authenticatedFingerprint(it) }
        val contactFingerprint = ContactIdentityResolver.fingerprintFromContactConversationId(conversationID)
        val announcedFingerprint = currentFingerprint(resolution, meshTarget)
        val hasMesh = meshTarget?.let { isConnected(mesh, it) } == true
        val knownNickname = try { mesh.getPeerNicknames()[conversationKey] } catch (_: Exception) { null }

        val accepted = synchronized(this) {
            // History lookup and the delete/ACK-memory reset in one step (retry vs delete race).
            val message = AppStateStore.privateMessages.value[conversationKey]?.firstOrNull { it.id == messageID }
                ?: return null
            val status = message.deliveryStatus
            if (status !is DeliveryStatus.Failed ||
                status.reason == REASON_RECIPIENT_CHANGED ||
                !message.isPrivate ||
                message.type != com.bitchat.android.model.BitchatMessageType.Message ||
                message.senderPeerID != myPeerID
            ) return null
            val expected = (durableRows[messageID]?.recipientFingerprint ?: lastFingerprints[messageID])
                ?: contactFingerprint
                ?: sessionFingerprint
            if (isBlocked(expected, sessionFingerprint, announcedFingerprint, contactFingerprint)) {
                Log.w(TAG, "Retry refused for a blocked peer msg_id=${Redact.id(messageID)}")
                return null
            }
            // Replace a stale row or queue entry left behind by a transport-side Failed.
            removeFromQueueLocked(messageID)
            if (durableRows.containsKey(messageID)) dropDurableLocked(messageID)
            removedIds.remove(messageID)
            recentlyAcked.remove(messageID)
            val nickname = message.recipientNickname ?: knownNickname ?: conversationKey
            val entry = QueuedMessage(message.content, nickname, messageID, clock(), expected?.lowercase())
            enqueue(conversationID, entry, persistable = true)
        }
        if (!accepted) {
            statusSink(messageID, DeliveryStatus.Failed(REASON_QUEUE_FULL))
            return RouteResult.FAILED
        }
        Log.d(TAG, "Retrying msg_id=${Redact.id(messageID)}")
        statusSink(messageID, DeliveryStatus.Sending)
        flushOutboxFor(conversationID)
        if (hasMesh && meshTarget != null) {
            synchronized(this) {
                if (isQueuedLocked(messageID)) kickHandshake(conversationID, meshTarget, immediate = true)
            }
            dispatchEffects()
        }
        return RouteResult.QUEUED
    }

    /**
     * D1: queued messages older than OUTBOX_EXPIRY_MS (from creation) become Failed("Not
     * delivered"). Entries restored from SENT rows were already handed to a transport, so they
     * become Failed("No delivery confirmation") instead (amendment 3).
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
                } else {
                    // Restored SENT entry (amendment 3): transmitted before, never acknowledged.
                    pendingEffects += Effect.Status(entry.messageID, DeliveryStatus.Failed(REASON_NO_CONFIRMATION))
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
            flushOutboxFor(pid) // also drains the kicked handshake
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

    /** Starts the handshakes collected by [kickHandshake]; never under the monitor. */
    private fun drainHandshakes() {
        val targets = synchronized(this) {
            if (pendingHandshakes.isEmpty()) return
            ArrayList(pendingHandshakes).also { pendingHandshakes.clear() }
        }
        val service = mesh
        targets.forEach { target -> try { service.initiateNoiseHandshake(target) } catch (_: Exception) { } }
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
