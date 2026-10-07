package com.bitchat.android.services

import android.content.ContentValues
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.bitchat.android.util.AppConstants
import org.json.JSONException
import org.json.JSONObject
import javax.crypto.AEADBadTagException

/** Persisted delivery state of an outbox row. Terminal states delete the row. */
internal enum class OutboxState(val code: Int) {
    QUEUED(0),
    SENT(1);

    companion object {
        fun fromCode(code: Int): OutboxState? = entries.firstOrNull { it.code == code }
    }
}

/** Coarse failure reason; an integer in the `last_error_code` column so no free text is stored. */
internal enum class OutboxError(val code: Int) {
    NONE(0),
    NO_ACK(1),
    NO_SESSION(2),
    SIGNING_FAILED(3),
    QUEUE_FULL(4),
    EXPIRED(5),
    UNKNOWN(6);

    companion object {
        fun fromCode(code: Int): OutboxError = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * A text DM awaiting delivery. [content], [recipientNickname], [toPeerId] and
 * [originalTimestampMs] and [recipientFingerprint] are stored AES-GCM encrypted (AAD `outbox:<messageId>`); the remaining
 * fields are scheduling metadata kept in clear columns.
 *
 * [toPeerId] is the value to pass as `toPeerID` to `MessageRouter.sendPrivate`.
 */
internal data class OutboxEntry(
    val messageId: String,
    val conversationId: String,
    val toPeerId: String,
    val content: String,
    val recipientNickname: String,
    val originalTimestampMs: Long,
    /** Identity the message was addressed to (null when unknown, e.g. backfilled rows). */
    val recipientFingerprint: String? = null,
    val state: OutboxState = OutboxState.QUEUED,
    val attempts: Int = 0,
    val createdAt: Long,
    val nextAttemptAt: Long,
    val lastError: OutboxError = OutboxError.NONE
)

/** A row whose payload could not be decrypted/parsed. It has already been deleted. */
internal data class CorruptOutboxRow(val messageId: String, val conversationId: String)

internal data class OutboxLoadResult(
    val entries: List<OutboxEntry>,
    val corrupt: List<CorruptOutboxRow> = emptyList()
)

internal sealed interface OutboxEnqueueResult {
    data object Enqueued : OutboxEnqueueResult
    /** Not written: the conversation (or the whole outbox) already holds [limit] rows. */
    data class Overflow(val limit: Int) : OutboxEnqueueResult
    /** A row with this message ID already exists; it was left untouched. */
    data object AlreadyQueued : OutboxEnqueueResult
}

/**
 * Durable outbox on the conversation database (table `outbox`, no FK to `private_messages`, so
 * retention pruning never cascades into it). Note: pruning tombstones the history rows it removes,
 * and loads purge tombstoned IDs, so a queued message whose history row was pruned is dropped. In
 * practice that needs >1000 newer messages in one conversation within the 1 h outbox window.
 *
 * Threading: shares the [helper] connection with the conversation repository. Callers must run on
 * the repository's single-thread executor, like every other ConversationDatabase operation.
 *
 * Corrupt-row policy: a row is deleted, logged with Log.w (no content) and reported in
 * [OutboxLoadResult.corrupt] only on a DEFINITIVE failure (AEAD tag mismatch / wrong AAD, malformed
 * envelope, JSON parse error, unknown state). Any other exception (Keystore/provider trouble) is
 * treated as transient: the row is kept, skipped for this load and logged.
 * Rows whose message_id is tombstoned in deleted_private_messages are deleted and never returned.
 */
internal class OutboxStore(
    private val helper: SQLiteOpenHelper,
    private val cipher: ConversationStorageCipher,
    private val perConversationLimit: Int = AppConstants.Router.OUTBOX_PER_PEER_LIMIT,
    private val globalLimit: Int = AppConstants.Router.OUTBOX_GLOBAL_LIMIT
) {
    fun enqueue(entry: OutboxEntry): OutboxEnqueueResult {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            if (countForLocked(db, entry.conversationId) >= perConversationLimit) {
                return OutboxEnqueueResult.Overflow(perConversationLimit)
            }
            if (DatabaseUtils.queryNumEntries(db, TABLE) >= globalLimit) {
                return OutboxEnqueueResult.Overflow(globalLimit)
            }
            val rowId = db.insertWithOnConflict(
                TABLE,
                null,
                ContentValues().apply {
                    put("message_id", entry.messageId)
                    put("conversation_id", entry.conversationId)
                    put("payload", encodePayload(cipher, entry))
                    put("state", entry.state.code)
                    put("attempts", entry.attempts)
                    put("created_at", entry.createdAt)
                    put("next_attempt_at", entry.nextAttemptAt)
                    put("last_error_code", entry.lastError.code)
                },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            db.setTransactionSuccessful()
            return if (rowId == -1L) OutboxEnqueueResult.AlreadyQueued else OutboxEnqueueResult.Enqueued
        } finally {
            db.endTransaction()
        }
    }

    fun markSent(messageId: String, nextAttemptAt: Long): Boolean =
        update(messageId, ContentValues().apply {
            put("state", OutboxState.SENT.code)
            put("next_attempt_at", nextAttemptAt)
        })

    fun recordAttempt(
        messageId: String,
        attempts: Int,
        nextAttemptAt: Long,
        lastError: OutboxError
    ): Boolean = update(messageId, ContentValues().apply {
        put("attempts", attempts)
        put("next_attempt_at", nextAttemptAt)
        put("last_error_code", lastError.code)
    })

    fun remove(messageId: String): Boolean =
        helper.writableDatabase.delete(TABLE, "message_id = ?", arrayOf(messageId)) > 0

    /** All rows, oldest first. */
    fun loadAll(): OutboxLoadResult = load(null, null)

    /** Rows with `next_attempt_at <= now`, oldest first. */
    fun loadDue(now: Long): OutboxLoadResult =
        load("next_attempt_at <= ?", arrayOf(now.toString()))

    /**
     * QUEUED rows with `created_at < cutoff`, oldest first. SENT rows are excluded: once handed to
     * a transport they are bounded by the resend attempts (D3), not by the 1 h expiry.
     */
    fun expiredBefore(cutoff: Long): OutboxLoadResult =
        load("state = ${OutboxState.QUEUED.code} AND created_at < ?", arrayOf(cutoff.toString()))

    fun countFor(conversationId: String): Int =
        countForLocked(helper.readableDatabase, conversationId)

    private fun countForLocked(db: SQLiteDatabase, conversationId: String): Int =
        db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE WHERE conversation_id = ? COLLATE NOCASE",
            arrayOf(conversationId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun update(messageId: String, values: ContentValues): Boolean =
        helper.writableDatabase.update(TABLE, values, "message_id = ?", arrayOf(messageId)) > 0

    private fun load(selection: String?, args: Array<String>?): OutboxLoadResult {
        val db = helper.writableDatabase
        // Tombstoned (user-deleted) messages must never be resent.
        db.delete(
            TABLE,
            "message_id IN (SELECT message_id FROM deleted_private_messages)",
            null
        )
        val entries = ArrayList<OutboxEntry>()
        val corrupt = ArrayList<CorruptOutboxRow>()
        db.query(
            TABLE,
            COLUMNS,
            selection,
            args,
            null,
            null,
            "created_at ASC, rowid ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val messageId = c.getString(0)
                val conversationId = c.getString(1)
                val state = OutboxState.fromCode(c.getInt(3))
                if (state == null) {
                    corrupt += CorruptOutboxRow(messageId, conversationId)
                    continue
                }
                when (val decoded = decodeResult(cipher, messageId, c.getBlob(2))) {
                    is DecodeResult.Corrupt ->
                        corrupt += CorruptOutboxRow(messageId, conversationId)
                    is DecodeResult.Transient ->
                        Log.w(TAG, "Outbox row id=${messageId.take(8)} unreadable for now " +
                            "(${decoded.error.javaClass.simpleName}); keeping it")
                    is DecodeResult.Ok -> entries += OutboxEntry(
                        messageId = messageId,
                        conversationId = conversationId,
                        toPeerId = decoded.payload.toPeerId,
                        content = decoded.payload.content,
                        recipientNickname = decoded.payload.recipientNickname,
                        originalTimestampMs = decoded.payload.timestampMs,
                        recipientFingerprint = decoded.payload.recipientFingerprint,
                        state = state,
                        attempts = c.getInt(4),
                        createdAt = c.getLong(5),
                        nextAttemptAt = c.getLong(6),
                        lastError = OutboxError.fromCode(c.getInt(7))
                    )
                }
            }
        }
        if (corrupt.isNotEmpty()) {
            db.beginTransaction()
            try {
                corrupt.forEach {
                    Log.w(TAG, "Dropping undecryptable outbox row id=${it.messageId.take(8)}")
                    db.delete(TABLE, "message_id = ?", arrayOf(it.messageId))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        return OutboxLoadResult(entries, corrupt)
    }

    internal data class Payload(
        val content: String,
        val recipientNickname: String,
        val toPeerId: String,
        val timestampMs: Long,
        val recipientFingerprint: String? = null
    )

    internal sealed interface DecodeResult {
        data class Ok(val payload: Payload) : DecodeResult
        /** Definitive: wrong AAD/tampered/malformed/unparseable. */
        data object Corrupt : DecodeResult
        /** Not provably corrupt (Keystore/provider error); caller must keep the row. */
        data class Transient(val error: Exception) : DecodeResult
    }

    companion object {
        private const val TAG = "OutboxStore"
        const val TABLE = "outbox"
        const val INDEX_CONVERSATION_CREATED = "idx_outbox_conversation_created"
        private const val PAYLOAD_VERSION = 2 // v1 (no recipient_fingerprint) is still readable
        private val COLUMNS = arrayOf(
            "message_id", "conversation_id", "payload", "state",
            "attempts", "created_at", "next_attempt_at", "last_error_code"
        )

        fun aad(messageId: String): ByteArray = "outbox:$messageId".toByteArray(Charsets.UTF_8)

        fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS outbox (
                    message_id TEXT PRIMARY KEY NOT NULL,
                    conversation_id TEXT COLLATE NOCASE NOT NULL,
                    payload BLOB NOT NULL,
                    state INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    next_attempt_at INTEGER NOT NULL,
                    last_error_code INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS $INDEX_CONVERSATION_CREATED " +
                    "ON outbox(conversation_id, created_at)"
            )
        }

        fun encodePayload(
            cipher: ConversationStorageCipher,
            entry: OutboxEntry
        ): ByteArray = encodePayload(
            cipher,
            entry.messageId,
            Payload(
                entry.content,
                entry.recipientNickname,
                entry.toPeerId,
                entry.originalTimestampMs,
                entry.recipientFingerprint
            )
        )

        fun encodePayload(
            cipher: ConversationStorageCipher,
            messageId: String,
            payload: Payload
        ): ByteArray {
            val json = JSONObject().apply {
                put("v", PAYLOAD_VERSION)
                put("content", payload.content)
                put("recipient_nickname", payload.recipientNickname)
                put("to_peer_id", payload.toPeerId)
                put("timestamp_ms", payload.timestampMs)
                put("recipient_fingerprint", payload.recipientFingerprint ?: JSONObject.NULL)
            }
            return cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8), aad(messageId))
        }

        /** Never throws; see [DecodeResult] for which failures are definitive. */
        fun decodeResult(
            cipher: ConversationStorageCipher,
            messageId: String,
            envelope: ByteArray?
        ): DecodeResult {
            if (envelope == null) return DecodeResult.Corrupt
            val plaintext = try {
                cipher.decrypt(envelope, aad(messageId))
            } catch (_: AEADBadTagException) {
                return DecodeResult.Corrupt
            } catch (_: IllegalArgumentException) {
                return DecodeResult.Corrupt // malformed/truncated envelope
            } catch (e: Exception) {
                return DecodeResult.Transient(e)
            }
            return try {
                val json = JSONObject(plaintext.toString(Charsets.UTF_8))
                DecodeResult.Ok(
                    Payload(
                        content = json.getString("content"),
                        recipientNickname = json.getString("recipient_nickname"),
                        toPeerId = json.getString("to_peer_id"),
                        timestampMs = json.getLong("timestamp_ms"),
                        recipientFingerprint =
                            if (json.isNull("recipient_fingerprint")) null
                            else json.getString("recipient_fingerprint")
                    )
                )
            } catch (_: JSONException) {
                DecodeResult.Corrupt
            }
        }

        /** Null for any non-Ok result. */
        fun decodePayload(
            cipher: ConversationStorageCipher,
            messageId: String,
            envelope: ByteArray?
        ): Payload? = (decodeResult(cipher, messageId, envelope) as? DecodeResult.Ok)?.payload
    }
}
