package com.bitchat.android.services

import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * In-memory [OutboxPersistence] with the same D2 admission (QUEUED rows only) and conflict
 * semantics as [OutboxStore]. It survives a simulated process restart (a new router over it).
 */
internal class FakeOutboxPersistence(
    private val perConversationLimit: Int = AppConstants.Router.OUTBOX_PER_PEER_LIMIT,
    private val globalLimit: Int = AppConstants.Router.OUTBOX_GLOBAL_LIMIT,
    /** Suspends inside enqueue to prove ordering does not depend on fast writes. */
    private val enqueueDelayMs: Long = 0L
) : OutboxPersistence {
    val rows = LinkedHashMap<String, OutboxEntry>()
    val ops = mutableListOf<String>()
    val corrupt = mutableListOf<CorruptOutboxRow>()
    /** Own, private, text history rows still Sending: messageID -> sent_at. */
    val sendingHistory = mutableMapOf<String, Long>()
    var lastWatermark: Long? = null
        private set
    var loadCount = 0
        private set
    /** When set, loadAll completes [loadEntered] and then suspends until the gate completes. */
    var loadGate: CompletableDeferred<Unit>? = null
    val loadEntered = CompletableDeferred<Unit>()
    var failNextLoad = false

    override suspend fun enqueue(entry: OutboxEntry): OutboxEnqueueResult {
        if (enqueueDelayMs > 0) delay(enqueueDelayMs)
        synchronized(this) {
            ops += "enqueue:${entry.messageId}"
            if (entry.state == OutboxState.QUEUED) {
                val queued = rows.values.filter { it.state == OutboxState.QUEUED }
                val key = entry.conversationId.lowercase()
                if (queued.count { it.conversationId.lowercase() == key } >= perConversationLimit) {
                    return OutboxEnqueueResult.Overflow(perConversationLimit)
                }
                if (queued.size >= globalLimit) return OutboxEnqueueResult.Overflow(globalLimit)
            }
            if (rows.containsKey(entry.messageId)) return OutboxEnqueueResult.AlreadyQueued
            rows[entry.messageId] = entry
            return OutboxEnqueueResult.Enqueued
        }
    }

    override suspend fun markSent(messageId: String, nextAttemptAt: Long): Boolean = synchronized(this) {
        ops += "markSent:$messageId"
        val row = rows[messageId] ?: return false
        rows[messageId] = row.copy(state = OutboxState.SENT, nextAttemptAt = nextAttemptAt)
        true
    }

    override suspend fun markQueued(messageId: String, nextAttemptAt: Long): Boolean = synchronized(this) {
        ops += "markQueued:$messageId"
        val row = rows[messageId] ?: return false
        rows[messageId] = row.copy(
            state = OutboxState.QUEUED,
            attempts = 0,
            nextAttemptAt = nextAttemptAt,
            lastError = OutboxError.NO_SESSION
        )
        true
    }

    override suspend fun recordAttempt(
        messageId: String,
        attempts: Int,
        nextAttemptAt: Long,
        lastError: OutboxError
    ): Boolean = synchronized(this) {
        ops += "recordAttempt:$messageId"
        val row = rows[messageId] ?: return false
        rows[messageId] = row.copy(attempts = attempts, nextAttemptAt = nextAttemptAt, lastError = lastError)
        true
    }

    override suspend fun remove(messageId: String): Boolean = synchronized(this) {
        ops += "remove:$messageId"
        rows.remove(messageId) != null
    }

    override suspend fun loadAll(): OutboxLoadResult {
        loadEntered.complete(Unit)
        loadGate?.await()
        synchronized(this) {
            loadCount++
            if (failNextLoad) {
                failNextLoad = false
                throw IllegalStateException("simulated load failure")
            }
            val reported = corrupt.toList()
            corrupt.clear()
            return OutboxLoadResult(rows.values.sortedBy { it.createdAt }, reported)
        }
    }

    override suspend fun reconcile(sendingBeforeMs: Long): OutboxReconcileResult = synchronized(this) {
        lastWatermark = sendingBeforeMs
        OutboxReconcileResult(
            sendingHistory.filter { (id, sentAt) -> sentAt < sendingBeforeMs && !rows.containsKey(id) }.keys.toList(),
            emptyList()
        )
    }

    override suspend fun updateRecipient(
        messageId: String,
        conversationId: String?,
        recipientFingerprint: String?
    ): Boolean = synchronized(this) {
        ops += "updateRecipient:$messageId"
        val row = rows[messageId] ?: return false
        rows[messageId] = row.copy(
            conversationId = conversationId ?: row.conversationId,
            toPeerId = conversationId ?: row.toPeerId,
            recipientFingerprint = recipientFingerprint ?: row.recipientFingerprint
        )
        true
    }

    fun opsFor(messageId: String): List<String> = synchronized(this) {
        ops.filter { it.endsWith(":$messageId") }.map { it.substringBefore(':') }
    }
}
