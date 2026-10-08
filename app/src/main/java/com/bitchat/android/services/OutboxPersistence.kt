package com.bitchat.android.services

import android.content.Context

/**
 * Durable-outbox port used by [MessageRouter] (P2-PR8). Every call is made from the router's single
 * ordered writer, so per-message operations are applied in submission order. The production
 * implementation delegates to the [ConversationRepository] suspend wrappers, which confine the
 * underlying [OutboxStore] to the repository's single-thread dispatcher.
 */
internal interface OutboxPersistence {
    suspend fun enqueue(entry: OutboxEntry): OutboxEnqueueResult
    suspend fun markSent(messageId: String, nextAttemptAt: Long): Boolean
    suspend fun remove(messageId: String): Boolean
    suspend fun loadAll(): OutboxLoadResult
    /** See ConversationDatabase.reconcileOutbox; [sendingBeforeMs] is the orphan watermark. */
    suspend fun reconcile(sendingBeforeMs: Long): OutboxReconcileResult
    /** Re-key conversation and/or fill the recipient fingerprint; null leaves a field as is. */
    suspend fun updateRecipient(messageId: String, conversationId: String?, recipientFingerprint: String?): Boolean
}

/**
 * Obtains the repository exactly like [AppStateStore.initializeConversationPersistence]:
 * `ConversationRepository.getInstance(applicationContext)` (process-wide singleton). Resolved
 * lazily so constructing the router never opens the database on the caller's thread.
 */
internal class RepositoryOutboxPersistence(context: Context) : OutboxPersistence {
    private val appContext = context.applicationContext
    private val repository by lazy { ConversationRepository.getInstance(appContext) }

    override suspend fun enqueue(entry: OutboxEntry) = repository.outboxEnqueue(entry)
    override suspend fun markSent(messageId: String, nextAttemptAt: Long) =
        repository.outboxMarkSent(messageId, nextAttemptAt)
    override suspend fun remove(messageId: String) = repository.outboxRemove(messageId)
    override suspend fun loadAll() = repository.outboxLoadAll()
    override suspend fun reconcile(sendingBeforeMs: Long) = repository.outboxReconcile(sendingBeforeMs)
    override suspend fun updateRecipient(messageId: String, conversationId: String?, recipientFingerprint: String?) =
        repository.outboxUpdateRecipient(messageId, conversationId, recipientFingerprint)
}
