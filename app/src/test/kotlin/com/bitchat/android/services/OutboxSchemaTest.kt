package com.bitchat.android.services

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID

/** Schema, v1..v4 -> v5 migration/backfill, and [OutboxStore] behaviour (P2-PR7). */
@RunWith(RobolectricTestRunner::class)
class OutboxSchemaTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var cipher: InMemoryConversationStorageCipher
    private var database: ConversationDatabase? = null
    private val now = 10_000_000_000L
    private val expiry = AppConstants.Router.OUTBOX_EXPIRY_MS

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "outbox-test-${UUID.randomUUID()}.db"
        cipher = InMemoryConversationStorageCipher()
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    private fun open(storageCipher: ConversationStorageCipher = cipher): ConversationDatabase =
        ConversationDatabase(context, databaseName, storageCipher = storageCipher, clock = { now })
            .also { database = it }

    private fun reopenAfterLegacy(): ConversationDatabase = open()

    // ---- legacy fixtures (hard-coded DDL, like ConversationDatabaseTest) ----

    private data class LegacyRow(
        val id: String,
        val sentAt: Long,
        val deliveryType: Int = 1,
        val type: BitchatMessageType = BitchatMessageType.Message,
        val isRelay: Boolean = false,
        val conversation: String = "contact_alice",
        val emptyPayload: Boolean = false
    ) {
        val content get() = "content-$id"
    }

    private fun payloadBlob(row: LegacyRow): ByteArray = cipher.encrypt(
        JSONObject().apply {
            put("sender", "me")
            put("content", row.content)
            put("recipient_nickname", "alice")
            put("sender_peer_id", "0123456789abcdef")
        }.toString().toByteArray(),
        "message:${row.id}".toByteArray()
    )

    private fun createLegacy(version: Int, rows: List<LegacyRow>) {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            val fk = version >= 4
            db.execSQL(
                "CREATE TABLE conversations (" +
                    "conversation_id TEXT COLLATE NOCASE PRIMARY KEY NOT NULL, display_name TEXT, " +
                    (if (version >= 2) "display_name_ciphertext BLOB, " else "") +
                    "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE conversation_aliases (" +
                    "alias TEXT COLLATE NOCASE PRIMARY KEY NOT NULL, " +
                    "conversation_id TEXT COLLATE NOCASE NOT NULL" +
                    (if (fk) ", FOREIGN KEY(conversation_id) REFERENCES conversations(conversation_id) " +
                        "ON DELETE CASCADE ON UPDATE CASCADE" else "") + ")"
            )
            db.execSQL(
                "CREATE TABLE private_messages (" +
                    "arrival_sequence INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "message_id TEXT UNIQUE NOT NULL, " +
                    "conversation_id TEXT COLLATE NOCASE NOT NULL, " +
                    "sender TEXT NOT NULL, content TEXT NOT NULL, message_type INTEGER NOT NULL, " +
                    "sent_at INTEGER NOT NULL, " +
                    (if (version >= 3) "received_at INTEGER NOT NULL DEFAULT 0, " else "") +
                    "is_relay INTEGER NOT NULL, original_sender TEXT, is_private INTEGER NOT NULL, " +
                    "recipient_nickname TEXT, sender_peer_id TEXT, mentions_json TEXT, " +
                    "channel_name TEXT, encrypted_content BLOB, is_encrypted INTEGER NOT NULL, " +
                    "delivery_type INTEGER NOT NULL, delivery_text TEXT, delivery_at INTEGER, " +
                    "delivery_reached INTEGER, delivery_total INTEGER, sender_nostr_pubkey TEXT, " +
                    (if (version >= 2) "payload_ciphertext BLOB" +
                        (if (version >= 4) " NOT NULL" else "") + ", " else "") +
                    "is_read INTEGER NOT NULL DEFAULT 0" +
                    (if (fk) ", FOREIGN KEY(conversation_id) REFERENCES conversations(conversation_id) " +
                        "ON DELETE CASCADE ON UPDATE CASCADE" else "") + ")"
            )
            db.execSQL(
                "CREATE TABLE deleted_private_messages (" +
                    "message_id TEXT PRIMARY KEY NOT NULL, deleted_at INTEGER NOT NULL)"
            )
            if (version >= 4) {
                db.execSQL(
                    "CREATE TABLE message_attachments (message_id TEXT PRIMARY KEY NOT NULL, " +
                        "path_hash BLOB NOT NULL, path_ciphertext BLOB NOT NULL, " +
                        "byte_size INTEGER NOT NULL, FOREIGN KEY(message_id) " +
                        "REFERENCES private_messages(message_id) ON DELETE CASCADE ON UPDATE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX idx_message_attachments_path_hash ON message_attachments(path_hash)"
                )
                db.execSQL(
                    "CREATE INDEX idx_private_messages_conversation_arrival " +
                        "ON private_messages(conversation_id, arrival_sequence)"
                )
                db.execSQL(
                    "CREATE INDEX idx_private_messages_read_arrival " +
                        "ON private_messages(is_read, arrival_sequence)"
                )
                db.execSQL(
                    "CREATE INDEX idx_conversation_aliases_conversation " +
                        "ON conversation_aliases(conversation_id)"
                )
                db.execSQL(
                    "CREATE INDEX idx_deleted_private_messages_time " +
                        "ON deleted_private_messages(deleted_at)"
                )
            }
            db.beginTransaction()
            try {
                rows.map { it.conversation }.distinct().forEach { conv ->
                    db.insertOrThrow("conversations", null, ContentValues().apply {
                        put("conversation_id", conv)
                        put("display_name", "alice")
                        put("created_at", 1L)
                        put("updated_at", 1L)
                    })
                    db.insertOrThrow("conversation_aliases", null, ContentValues().apply {
                        put("alias", conv)
                        put("conversation_id", conv)
                    })
                }
                rows.forEach { insertLegacyRow(db, version, it) }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.version = version
        }
    }

    private fun insertLegacyRow(db: SQLiteDatabase, version: Int, r: LegacyRow) {
        db.insertOrThrow("private_messages", null, ContentValues().apply {
            put("message_id", r.id)
            put("conversation_id", r.conversation)
            // v1 stored everything in clear columns; v2+ scrubs them into the encrypted payload.
            put("sender", if (version == 1) "me" else "")
            put("content", if (version == 1) r.content else "")
            put("message_type", r.type.ordinal)
            put("sent_at", r.sentAt)
            if (version >= 3) put("received_at", r.sentAt)
            put("is_relay", if (r.isRelay) 1 else 0)
            put("is_private", 1)
            if (version == 1) put("recipient_nickname", "alice")
            put("is_encrypted", 0)
            put("delivery_type", r.deliveryType)
            put("is_read", 1)
            if (version >= 2) {
                put("payload_ciphertext", if (r.emptyPayload) ByteArray(0) else payloadBlob(r))
            }
        })
    }

    private fun entry(
        id: String,
        conversation: String = "contact_alice",
        createdAt: Long = 1_000L,
        nextAttemptAt: Long = 1_000L,
        state: OutboxState = OutboxState.QUEUED,
        fingerprint: String? = null
    ) = OutboxEntry(
        messageId = id,
        conversationId = conversation,
        toPeerId = conversation,
        content = "secret-$id",
        recipientNickname = "alice",
        originalTimestampMs = createdAt,
        recipientFingerprint = fingerprint,
        state = state,
        createdAt = createdAt,
        nextAttemptAt = nextAttemptAt
    )

    private fun message(id: String, status: DeliveryStatus? = DeliveryStatus.Sending) = BitchatMessage(
        id = id,
        sender = "me",
        content = "content-$id",
        timestamp = Date(5L),
        isPrivate = true,
        recipientNickname = "alice",
        senderPeerID = "0123456789abcdef",
        deliveryStatus = status
    )

    private fun names(db: ConversationDatabase, type: String): Set<String> =
        db.readableDatabase.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = ?", arrayOf(type)
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun deliveryType(db: ConversationDatabase, id: String): Int =
        db.readableDatabase.rawQuery(
            "SELECT delivery_type FROM private_messages WHERE message_id = ?", arrayOf(id)
        ).use { assertTrue(it.moveToFirst()); it.getInt(0) }

    private fun status(db: ConversationDatabase, conv: String, id: String): DeliveryStatus? =
        db.loadSnapshot().chats.getValue(conv).first { it.id == id }.deliveryStatus

    private fun queuedIds(db: ConversationDatabase) = db.outbox.loadAll().entries.map { it.messageId }

    // ---- schema ----

    @Test
    fun `fresh install creates outbox table and index`() {
        val db = open()
        assertTrue("outbox" in names(db, "table"))
        assertTrue("idx_outbox_conversation_created" in names(db, "index"))
    }

    // ---- v4 -> v5 ----

    @Test
    fun `v4 upgrade backfills recent outgoing, fails old ones, ignores incoming`() {
        createLegacy(
            4,
            listOf(
                LegacyRow("recent", now - 60_000L),
                LegacyRow("old", now - expiry - 1L),
                LegacyRow("image", now - 1_000L, type = BitchatMessageType.Image),
                LegacyRow("incoming", now - 1_000L, deliveryType = 0),
                LegacyRow("delivered", now - 1_000L, deliveryType = 3),
                LegacyRow("sent", now - 1_000L, deliveryType = 2),
                LegacyRow("relayed", now - 1_000L, isRelay = true)
            )
        )
        val db = reopenAfterLegacy()
        val loaded = db.outbox.loadAll()
        assertEquals(listOf("recent"), loaded.entries.map { it.messageId })
        val e = loaded.entries.single()
        assertEquals("content-recent", e.content)
        assertEquals("alice", e.recipientNickname)
        assertEquals("contact_alice", e.toPeerId)
        assertEquals(now - 60_000L, e.originalTimestampMs)
        assertEquals(OutboxState.QUEUED, e.state)
        assertEquals(0, e.attempts)
        assertEquals(now, e.nextAttemptAt)
        assertNull(e.recipientFingerprint)
        assertEquals(OutboxError.NONE, e.lastError)

        assertEquals(DeliveryStatus.Sending, status(db, "contact_alice", "recent"))
        assertEquals(DeliveryStatus.Failed("Not delivered"), status(db, "contact_alice", "old"))
        assertEquals(DeliveryStatus.Failed("Not delivered"), status(db, "contact_alice", "image"))
        assertNull(status(db, "contact_alice", "incoming"))
        assertTrue(status(db, "contact_alice", "delivered") is DeliveryStatus.Delivered)
        assertEquals(DeliveryStatus.Sent, status(db, "contact_alice", "sent"))
        // Untouched rows keep their content.
        assertEquals(
            "content-incoming",
            db.loadSnapshot().chats.getValue("contact_alice").first { it.id == "incoming" }.content
        )
    }

    @Test
    fun `cutoff boundary is inclusive`() {
        createLegacy(
            4,
            listOf(
                LegacyRow("exact", now - expiry),
                LegacyRow("just-over", now - expiry - 1L)
            )
        )
        val db = reopenAfterLegacy()
        assertEquals(listOf("exact"), queuedIds(db))
        assertEquals(5, deliveryType(db, "just-over"))
    }

    @Test
    fun `chained upgrades from v1 v2 and v3 reach v5 and queue the recent row`() {
        for (version in 1..3) {
            database?.close()
            context.deleteDatabase(databaseName)
            createLegacy(
                version,
                listOf(
                    LegacyRow("recent", now - 1_000L),
                    LegacyRow("done", now - 5_000L, deliveryType = 2)
                )
            )
            val db = reopenAfterLegacy()
            assertEquals("v$version", listOf("recent"), queuedIds(db))
            assertEquals("v$version", DeliveryStatus.Sending, status(db, "contact_alice", "recent"))
            val done = db.loadSnapshot().chats.getValue("contact_alice").first { it.id == "done" }
            assertEquals("v$version", "content-done", done.content)
            assertEquals("v$version", DeliveryStatus.Sent, done.deliveryStatus)
            assertEquals("v$version", "content-recent", db.outbox.loadAll().entries.single().content)
        }
    }

    @Test
    fun `migration is idempotent when re-run`() {
        createLegacy(4, listOf(LegacyRow("recent", now - 1_000L)))
        var db = reopenAfterLegacy()
        assertEquals(listOf("recent"), queuedIds(db))
        db.writableDatabase.execSQL("PRAGMA user_version = 4")
        db.close()
        db = reopenAfterLegacy()
        assertEquals(listOf("recent"), queuedIds(db))
        assertEquals(DeliveryStatus.Sending, status(db, "contact_alice", "recent"))
    }

    @Test
    fun `undecryptable Sending row becomes Failed without crashing`() {
        createLegacy(4, listOf(LegacyRow("recent", now - 1_000L)))
        cipher.destroyKey()
        val db = reopenAfterLegacy()
        assertTrue(db.outbox.loadAll().entries.isEmpty())
        assertEquals(5, deliveryType(db, "recent"))
    }

    @Test
    fun `Sending row with empty payload becomes Failed without crashing`() {
        createLegacy(
            4,
            listOf(
                LegacyRow("empty", now - 1_000L, emptyPayload = true),
                LegacyRow("fine", now - 1_000L)
            )
        )
        val db = reopenAfterLegacy()
        assertEquals(listOf("fine"), queuedIds(db))
        assertEquals(5, deliveryType(db, "empty"))
    }

    @Test
    fun `backfill respects the per conversation limit`() {
        val limit = AppConstants.Router.OUTBOX_PER_PEER_LIMIT
        createLegacy(4, (0..limit).map { LegacyRow("m$it", now - 10_000L + it) })
        val db = reopenAfterLegacy()
        assertEquals(limit, db.outbox.countFor("contact_alice"))
        assertEquals(5, deliveryType(db, "m$limit"))
        assertEquals(1, deliveryType(db, "m0"))
    }

    @Test
    fun `backfill respects the global limit`() {
        val perConv = AppConstants.Router.OUTBOX_PER_PEER_LIMIT
        val total = AppConstants.Router.OUTBOX_GLOBAL_LIMIT
        val convs = total / perConv
        val rows = (0 until convs).flatMap { c ->
            (0 until perConv).map { LegacyRow("c${c}m$it", now - 10_000L + it, conversation = "conv$c") }
        } + LegacyRow("extra", now - 1_000L, conversation = "convExtra")
        createLegacy(4, rows)
        val db = reopenAfterLegacy()
        assertEquals(total, db.outbox.loadAll().entries.size)
        assertEquals(5, deliveryType(db, "extra"))
    }

    @Test
    fun `backfill never queues nostr or geohash conversations`() {
        createLegacy(
            4,
            listOf(
                LegacyRow("n1", now - 1_000L, conversation = "nostr_abcdef0123456789"),
                LegacyRow("n2", now - 1_000L, conversation = "nostr:abcdef0123456789"),
                LegacyRow("mesh", now - 1_000L)
            )
        )
        val db = reopenAfterLegacy()
        assertEquals(listOf("mesh"), queuedIds(db))
        assertEquals(5, deliveryType(db, "n1"))
        assertEquals(5, deliveryType(db, "n2"))
    }

    // ---- store ----

    @Test
    fun `store enqueue load markSent recordAttempt remove`() {
        val store = open().outbox
        assertEquals(OutboxEnqueueResult.Enqueued, store.enqueue(entry("b", createdAt = 2_000L)))
        assertEquals(OutboxEnqueueResult.Enqueued, store.enqueue(entry("a", createdAt = 1_000L)))
        assertEquals(OutboxEnqueueResult.AlreadyQueued, store.enqueue(entry("a")))
        assertEquals(listOf("a", "b"), store.loadAll().entries.map { it.messageId })
        assertEquals("secret-a", store.loadAll().entries.first().content)
        assertEquals(2, store.countFor("CONTACT_ALICE"))

        assertTrue(store.markSent("a", 5_000L))
        assertTrue(store.recordAttempt("a", 2, 9_000L, OutboxError.NO_ACK))
        val a = store.loadAll().entries.first { it.messageId == "a" }
        assertEquals(OutboxState.SENT, a.state)
        assertEquals(2, a.attempts)
        assertEquals(9_000L, a.nextAttemptAt)
        assertEquals(OutboxError.NO_ACK, a.lastError)

        assertTrue(store.remove("a"))
        assertEquals(listOf("b"), store.loadAll().entries.map { it.messageId })
    }

    @Test
    fun `per conversation limit returns overflow without evicting`() {
        val store = open().outbox
        val limit = AppConstants.Router.OUTBOX_PER_PEER_LIMIT
        assertEquals(200, limit)
        repeat(limit) {
            assertEquals(OutboxEnqueueResult.Enqueued, store.enqueue(entry("m$it", createdAt = it.toLong())))
        }
        assertEquals(OutboxEnqueueResult.Overflow(limit), store.enqueue(entry("extra")))
        assertEquals(limit, store.countFor("contact_alice"))
        assertEquals(
            OutboxEnqueueResult.Enqueued,
            store.enqueue(entry("other", conversation = "contact_bob"))
        )
        assertEquals("m0", store.loadAll().entries.first().messageId)
    }

    @Test
    fun `global limit returns overflow`() {
        assertEquals(2000, AppConstants.Router.OUTBOX_GLOBAL_LIMIT)
        val store = OutboxStore(open(), cipher, perConversationLimit = 200, globalLimit = 3)
        repeat(3) {
            assertEquals(OutboxEnqueueResult.Enqueued, store.enqueue(entry("g$it", conversation = "conv$it")))
        }
        assertEquals(OutboxEnqueueResult.Overflow(3), store.enqueue(entry("g3", conversation = "conv9")))
        assertEquals(3, store.loadAll().entries.size)
    }

    @Test
    fun `loadDue honours next_attempt_at`() {
        val store = open().outbox
        store.enqueue(entry("due", createdAt = 100L, nextAttemptAt = 500L))
        store.enqueue(entry("later", createdAt = 200L, nextAttemptAt = 900L))
        assertEquals(listOf("due"), store.loadDue(500L).entries.map { it.messageId })
        assertTrue(store.loadDue(499L).entries.isEmpty())
        assertEquals(listOf("due", "later"), store.loadDue(900L).entries.map { it.messageId })
    }

    @Test
    fun `expiredBefore only considers QUEUED rows`() {
        val store = open().outbox
        store.enqueue(entry("queued", createdAt = 100L))
        store.enqueue(entry("sent", createdAt = 100L, state = OutboxState.SENT))
        assertEquals(listOf("queued"), store.expiredBefore(200L).entries.map { it.messageId })
        assertTrue(store.expiredBefore(100L).entries.isEmpty())
        assertEquals(2, store.loadAll().entries.size)
    }

    @Test
    fun `recipient fingerprint round trips and v1 payload is still readable`() {
        val db = open()
        val store = db.outbox
        store.enqueue(entry("fp", fingerprint = "ab".repeat(32)))
        store.enqueue(entry("nofp"))
        val byId = store.loadAll().entries.associateBy { it.messageId }
        assertEquals("ab".repeat(32), byId.getValue("fp").recipientFingerprint)
        assertNull(byId.getValue("nofp").recipientFingerprint)

        val v1 = cipher.encrypt(
            JSONObject().apply {
                put("v", 1)
                put("content", "old")
                put("recipient_nickname", "alice")
                put("to_peer_id", "contact_alice")
                put("timestamp_ms", 7L)
            }.toString().toByteArray(),
            "outbox:v1row".toByteArray()
        )
        db.writableDatabase.insertOrThrow("outbox", null, ContentValues().apply {
            put("message_id", "v1row")
            put("conversation_id", "contact_alice")
            put("payload", v1)
            put("state", 0)
            put("created_at", 7L)
            put("next_attempt_at", 7L)
        })
        val old = store.loadAll().entries.first { it.messageId == "v1row" }
        assertEquals("old", old.content)
        assertNull(old.recipientFingerprint)
    }

    @Test
    fun `payload uses outbox AAD and a wrong id is corrupt and dropped`() {
        val db = open()
        val store = db.outbox
        store.enqueue(entry("x"))
        val blob = db.readableDatabase.rawQuery(
            "SELECT payload FROM outbox WHERE message_id = 'x'", null
        ).use { it.moveToFirst(); it.getBlob(0) }
        assertNotNull(cipher.decrypt(blob, "outbox:x".toByteArray()))
        assertNull(OutboxStore.decodePayload(cipher, "y", blob))
        assertNotNull(OutboxStore.decodePayload(cipher, "x", blob))

        db.writableDatabase.execSQL("UPDATE outbox SET message_id = 'z' WHERE message_id = 'x'")
        val result = store.loadAll()
        assertTrue(result.entries.isEmpty())
        assertEquals(listOf(CorruptOutboxRow("z", "contact_alice")), result.corrupt)
        assertEquals(0, store.countFor("contact_alice"))
    }

    @Test
    fun `message payload copied into outbox is corrupt and deleted`() {
        val db = open()
        db.upsertMessage("contact_alice", setOf("contact_alice"), "alice", message("same"), true)
        val messagePayload = db.readableDatabase.rawQuery(
            "SELECT payload_ciphertext FROM private_messages WHERE message_id = 'same'", null
        ).use { it.moveToFirst(); it.getBlob(0) }
        db.writableDatabase.insertOrThrow("outbox", null, ContentValues().apply {
            put("message_id", "same")
            put("conversation_id", "contact_alice")
            put("payload", messagePayload)
            put("state", 0)
            put("created_at", 1L)
            put("next_attempt_at", 1L)
        })
        val result = db.outbox.loadAll()
        assertTrue(result.entries.isEmpty())
        assertEquals(listOf("same"), result.corrupt.map { it.messageId })
        assertEquals(0, db.outbox.countFor("contact_alice"))
    }

    private class FlakyCipher(private val delegate: ConversationStorageCipher) : ConversationStorageCipher {
        var failure: Exception? = null
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) =
            delegate.encrypt(plaintext, associatedData)
        override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
            failure?.let { throw it }
            return delegate.decrypt(envelope, associatedData)
        }
        override fun destroyKey() = delegate.destroyKey()
    }

    @Test
    fun `transient cipher failure keeps the row, definitive failure deletes it`() {
        val flaky = FlakyCipher(cipher)
        val db = open()
        val store = OutboxStore(db, flaky)
        store.enqueue(entry("t"))

        flaky.failure = IllegalStateException("keystore unavailable")
        var r = store.loadAll()
        assertTrue(r.entries.isEmpty())
        assertTrue(r.corrupt.isEmpty())
        assertEquals(1, store.countFor("contact_alice"))

        flaky.failure = null
        assertEquals(listOf("t"), store.loadAll().entries.map { it.messageId })

        flaky.failure = javax.crypto.AEADBadTagException("tag mismatch")
        r = store.loadAll()
        assertEquals(listOf("t"), r.corrupt.map { it.messageId })
        assertEquals(0, store.countFor("contact_alice"))
    }

    @Test
    fun `tombstoned message is never returned and its row is removed`() {
        val db = open()
        db.outbox.enqueue(entry("dead"))
        db.outbox.enqueue(entry("alive"))
        db.writableDatabase.insertOrThrow("deleted_private_messages", null, ContentValues().apply {
            put("message_id", "dead")
            put("deleted_at", 1L)
        })
        assertEquals(listOf("alive"), queuedIds(db))
        assertEquals(1, db.outbox.countFor("contact_alice"))
    }

    // ---- maintenance on user actions ----

    @Test
    fun `deleteMessage removes backfilled and enqueued outbox rows`() {
        createLegacy(4, listOf(LegacyRow("recent", now - 1_000L)))
        val db = reopenAfterLegacy()
        assertEquals(1, db.outbox.countFor("contact_alice"))
        db.deleteMessage("recent")
        assertEquals(0, db.outbox.countFor("contact_alice"))

        db.upsertMessage("contact_alice", setOf("contact_alice"), "alice", message("m2"), true)
        db.outbox.enqueue(entry("m2"))
        db.deleteMessage("m2")
        assertEquals(0, db.outbox.countFor("contact_alice"))
    }

    @Test
    fun `deleteConversation removes the conversation's outbox rows`() {
        val db = open()
        db.upsertMessage("contact_alice", setOf("contact_alice"), "alice", message("m1"), true)
        db.upsertMessage("contact_bob", setOf("contact_bob"), "bob", message("m3"), true)
        db.outbox.enqueue(entry("m1"))
        db.outbox.enqueue(entry("orphan"))
        db.outbox.enqueue(entry("bobs", conversation = "contact_bob"))
        db.deleteConversation("contact_alice", setOf("contact_alice"))
        assertEquals(0, db.outbox.countFor("contact_alice"))
        assertEquals(1, db.outbox.countFor("contact_bob"))
    }

    @Test
    fun `mergeAliases re-keys outbox rows`() {
        val db = open()
        db.upsertMessage("peer_old", setOf("peer_old"), "alice", message("m1"), true)
        db.upsertMessage("contact_alice", setOf("contact_alice"), "alice", message("m2"), true)
        db.outbox.enqueue(entry("q1", conversation = "peer_old"))
        db.mergeAliases("contact_alice", setOf("peer_old"))
        assertEquals(0, db.outbox.countFor("peer_old"))
        assertEquals(1, db.outbox.countFor("contact_alice"))
        assertEquals(listOf("contact_alice"), db.outbox.loadAll().entries.map { it.conversationId })
    }

    @Test
    fun `clearAll wipes outbox and retention pruning leaves it intact`() {
        val db = ConversationDatabase(
            context, databaseName, maxMessagesPerConversation = 2,
            storageCipher = cipher, clock = { now }
        ).also { database = it }
        db.outbox.enqueue(entry("keep"))
        repeat(6) {
            db.upsertMessage(
                "contact_alice", setOf("contact_alice"), "alice",
                message("p$it", DeliveryStatus.Sent), true
            )
        }
        db.pruneToRetentionLimits()
        assertEquals(1, db.outbox.countFor("contact_alice"))

        db.clearAll()
        assertEquals(0, db.outbox.countFor("contact_alice"))
        assertTrue(db.outbox.loadAll().entries.isEmpty())
    }

    // ---- repository wrappers ----

    @Test
    fun `repository exposes outbox through suspend wrappers`() = runBlocking {
        val repo = ConversationRepository(
            context, Dispatchers.Unconfined, databaseName, cipher
        )
        assertEquals(OutboxEnqueueResult.Enqueued, repo.outboxEnqueue(entry("r1", createdAt = 1L, nextAttemptAt = 5L)))
        assertEquals(listOf("r1"), repo.outboxLoadAll().entries.map { it.messageId })
        assertEquals(listOf("r1"), repo.outboxLoadDue(5L).entries.map { it.messageId })
        assertEquals(listOf("r1"), repo.outboxExpiredBefore(10L).entries.map { it.messageId })
        assertTrue(repo.outboxMarkSent("r1", 50L))
        assertTrue(repo.outboxRecordAttempt("r1", 1, 60L, OutboxError.NO_SESSION))
        assertTrue(repo.outboxLoadDue(5L).entries.isEmpty())
        assertTrue(repo.outboxRemove("r1"))
        assertTrue(repo.outboxLoadAll().entries.isEmpty())
    }
}
