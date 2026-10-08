package com.bitchat.android.services

import android.content.Context
import android.os.Build
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.Date
import java.util.UUID

/** P2-PR8: MessageRouter over the durable outbox (Decision 015). Synthetic IDs only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class MessageRouterDurableOutboxTest {

    private val myPeerID = "1111222233334444"
    private val peerID = "aaaabbbbccccdddd"
    private val noiseKey = ByteArray(32) { 0x0B }
    private val fingerprint = ContactIdentityResolver.fingerprintHex(noiseKey)

    private lateinit var context: Context
    private lateinit var mesh: MeshService
    private lateinit var store: FakeOutboxPersistence
    private val statuses = Collections.synchronizedList(mutableListOf<Pair<String, DeliveryStatus>>())
    private var fakeTime = 10_000_000L

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("router-durable-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val identityManager = SecureIdentityStateManager(prefs, testOnly = true)
        ContactDirectory.identityManagerProvider = { identityManager }

        mesh = mock()
        // P2-PR9: the router sends through the reporting API; run its default (session check +
        // sendPrivateMessage) on the mock so existing sendPrivateMessage verifications still apply.
        org.mockito.kotlin.doCallRealMethod().whenever(mesh).sendPrivateMessageReporting(
            org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(),
            org.mockito.kotlin.any(), org.mockito.kotlin.any()
        )
        whenever(mesh.myPeerID).thenReturn(myPeerID)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "peer"))
        ContactDirectory.initialize(context) { mesh }

        store = FakeOutboxPersistence()
        statuses.clear()
        fakeTime = 10_000_000L
        MessageRouter.disableSchedulerForTesting = true
        MessageRouter.persistenceOverrideForTesting = store
        MessageRouter.statusSinkOverrideForTesting = { id, status -> statuses += id to status }
        MessageRouter.resetForTesting()
    }

    @After
    fun tearDown() {
        MessageRouter.resetForTesting()
        MessageRouter.disableSchedulerForTesting = false
        MessageRouter.persistenceOverrideForTesting = null
        MessageRouter.statusSinkOverrideForTesting = null
        AppStateStore.clear()
        ContactDirectory.identityManagerProvider = { SecureIdentityStateManager(it) }
    }

    private fun newRouter(): MessageRouter =
        MessageRouter.getInstance(context, mesh).also { it.clock = { fakeTime } }

    /** Simulated process death: pending writes land, then the instance and its RAM are gone. */
    private fun restart(old: MessageRouter): MessageRouter {
        await(old)
        MessageRouter.resetForTesting()
        return newRouter()
    }

    private fun await(router: MessageRouter) = runBlocking {
        withTimeout(10_000) { router.awaitOutboxWrites() }
    }

    private fun failedReason(id: String): String? =
        statuses.lastOrNull { it.first == id }?.second?.let { (it as? DeliveryStatus.Failed)?.reason }

    private fun failedCount(id: String): Int =
        statuses.count { it.first == id && it.second is DeliveryStatus.Failed }

    @Test
    fun `mesh send persists a SENT row and a delivered ACK removes it`() {
        peerReady()
        val router = newRouter()
        assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("hi", peerID, "peer", "m1"))
        await(router)
        val row = store.rows["m1"]!!
        assertEquals(OutboxState.SENT, row.state)
        assertEquals(1, row.attempts)
        assertEquals(fakeTime + AppConstants.Router.OUTBOX_RESEND_BACKOFF_MS[0], row.nextAttemptAt)
        assertEquals(ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKey), row.toPeerId)
        assertEquals(fingerprint, row.recipientFingerprint)

        // The real ACK path: BluetoothMeshService -> AppStateStore -> router listener.
        AppStateStore.updatePrivateMessageStatus("m1", DeliveryStatus.Delivered(peerID, Date()))
        await(router)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `read ACK also removes the row`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        AppStateStore.updatePrivateMessageStatus("m1", DeliveryStatus.Read(peerID, Date()))
        await(router)
        assertTrue(store.rows.isEmpty())
        assertEquals(listOf("enqueue", "remove"), store.opsFor("m1"))
    }

    @Test
    fun `restart before the ACK resends once with the same message ID`() {
        peerReady()
        val old = newRouter()
        old.sendPrivate("hi", peerID, "peer", "m1")
        val router = restart(old)
        router.rehydrate()
        await(router)
        router.onSessionEstablished(peerID)
        router.tickOutbox()
        verify(mesh, times(2)).sendPrivateMessage("hi", peerID, "peer", "m1")
        await(router)
        assertEquals(OutboxState.SENT, store.rows["m1"]!!.state)
    }

    @Test
    fun `201st queued message for one peer fails with queue full and nothing is evicted`() {
        peerOffline()
        val router = newRouter()
        repeat(200) { i ->
            assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("c$i", peerID, "peer", "m$i"))
        }
        assertEquals(MessageRouter.RouteResult.FAILED, router.sendPrivate("c200", peerID, "peer", "m200"))
        assertEquals(MessageRouter.REASON_QUEUE_FULL, failedReason("m200"))
        assertEquals(1, statuses.size)
        await(router)
        assertEquals(200, store.rows.size)
        assertFalse(store.rows.containsKey("m200"))

        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, times(200)).sendPrivateMessage(any(), eq(peerID), any(), any())
        verify(mesh, times(1)).sendPrivateMessage("c0", peerID, "peer", "m0")
        verify(mesh, never()).sendPrivateMessage(eq("c200"), any(), any(), anyOrNull())
    }

    @Test
    fun `global limit of 2000 rejects the next message`() {
        val router = newRouter()
        // 10 offline peers x 200 messages (unknown peers: getPeerInfo returns null).
        val peers = (0 until 10).map { "%016x".format(0x1000L + it) }
        peers.forEach { p ->
            repeat(200) { i -> assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("x", p, "p", "$p-$i")) }
        }
        val result = router.sendPrivate("x", "00000000000fffff", "p", "overflow")
        assertEquals(MessageRouter.RouteResult.FAILED, result)
        assertEquals(MessageRouter.REASON_QUEUE_FULL, failedReason("overflow"))
        await(router)
        assertEquals(2000, store.rows.size)
    }

    @Test
    fun `queued message expires after one hour without any ViewModel callback`() {
        peerOffline()
        val router = newRouter()
        assertNull(router.onMessageExpired)
        router.sendPrivate("old", peerID, "peer", "m1")

        fakeTime += AppConstants.Router.OUTBOX_EXPIRY_MS - 1
        router.tickOutbox()
        assertTrue(statuses.isEmpty())

        fakeTime += 2
        router.tickOutbox()
        assertEquals(MessageRouter.REASON_NOT_DELIVERED, failedReason("m1"))
        await(router)
        assertTrue(store.rows.isEmpty())

        peerReady()
        router.tickOutbox()
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `expiry still reports through the compatibility callback`() {
        peerOffline()
        val router = newRouter()
        val expired = mutableListOf<String>()
        router.onMessageExpired = { expired += it }
        router.sendPrivate("old", peerID, "peer", "m1")
        fakeTime += AppConstants.Router.OUTBOX_EXPIRY_MS + 1
        router.tickOutbox()
        assertEquals(listOf("m1"), expired)
    }

    @Test
    fun `enqueue markSent and remove apply in order under rapid calls`() {
        store = FakeOutboxPersistence(enqueueDelayMs = 2)
        MessageRouter.persistenceOverrideForTesting = store
        peerOffline()
        val router = newRouter()
        val ids = (0 until 40).map { "r$it" }
        ids.forEach { router.sendPrivate("c", peerID, "peer", it) }
        peerReady()
        router.onSessionEstablished(peerID)
        ids.forEach { router.onMessageAcknowledged(it, peerID) }
        await(router)
        ids.forEach { assertEquals(listOf("enqueue", "markSent", "remove"), store.opsFor(it)) }
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `rehydrate drops and fails Nostr alias rows`() {
        val alias = "nostr_0123456789abcdef"
        store.rows["n1"] = row("n1", conversationId = alias)
        val router = newRouter()
        router.rehydrate()
        await(router)
        await(router)
        assertEquals(MessageRouter.REASON_NOT_DELIVERED, failedReason("n1"))
        assertTrue(store.rows.isEmpty())
        router.tickOutbox()
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `rehydrate marks corrupt rows failed`() {
        store.corrupt += CorruptOutboxRow("bad-1", peerID)
        val router = newRouter()
        router.rehydrate()
        await(router)
        assertEquals(MessageRouter.REASON_NOT_DELIVERED, failedReason("bad-1"))
    }

    @Test
    fun `reconcile fails an orphan Sending row but not a queued one`() {
        store.rows["q1"] = row("q1")
        store.sendingHistory["q1"] = 0L
        store.sendingHistory["orphan-1"] = 0L
        val router = newRouter()
        router.rehydrate()
        await(router)
        assertEquals(MessageRouter.REASON_NOT_DELIVERED, failedReason("orphan-1"))
        assertNull(failedReason("q1"))
    }

    @Test
    fun `reconcile only fails Sending rows older than the rehydrate watermark`() {
        // A send at/after the watermark may still be waiting for its outbox write: never failed.
        store.sendingHistory["live-1"] = fakeTime
        store.sendingHistory["old-1"] = fakeTime - 1
        val router = newRouter()
        router.rehydrate()
        await(router)
        assertEquals(fakeTime, store.lastWatermark)
        assertNull(failedReason("live-1"))
        assertEquals(MessageRouter.REASON_NOT_DELIVERED, failedReason("old-1"))
    }

    @Test
    fun `rehydrate is idempotent`() {
        store.rows["q1"] = row("q1")
        val router = newRouter()
        router.rehydrate()
        router.rehydrate()
        await(router)
        router.rehydrate()
        await(router)
        assertEquals(1, store.loadCount)
        peerReady()
        router.onSessionEstablished(peerID)
        router.flushAllOutbox()
        verify(mesh, times(1)).sendPrivateMessage("content", peerID, "peer", "q1")
    }

    @Test
    fun `changed recipient fingerprint is not resent and fails`() {
        store.rows["q1"] = row("q1", recipientFingerprint = "ff".repeat(32))
        val router = newRouter()
        router.rehydrate()
        await(router)
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        assertEquals(MessageRouter.REASON_RECIPIENT_CHANGED, failedReason("q1"))
        await(router)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `unknown recipient fingerprint keeps waiting`() {
        store.rows["q1"] = row("q1", recipientFingerprint = fingerprint)
        val router = newRouter()
        router.rehydrate()
        await(router)
        // Session up but no authenticated fingerprint bound yet: hold the message, even though
        // an announced Noise key is present (announcements are not authentication).
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = true))
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(true)
        whenever(mesh.getPeerFingerprint(peerID)).thenReturn(null)
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        assertTrue(statuses.isEmpty())

        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, times(1)).sendPrivateMessage("content", peerID, "peer", "q1")
    }

    @Test
    fun `deleting a queued message drops it from the RAM queue`() {
        peerOffline()
        val router = newRouter()
        router.sendPrivate("x", peerID, "peer", "m1")
        AppStateStore.outboxListener!!.onPrivateMessagesRemoved(null, listOf("m1"))
        await(router)
        assertTrue(store.rows.isEmpty())
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `blocking a peer drops and fails its queued messages`() {
        peerOffline()
        val router = newRouter()
        router.sendPrivate("x", peerID, "peer", "m1")
        router.dropConversation(peerID)
        assertEquals(MessageRouter.REASON_BLOCKED, failedReason("m1"))
        await(router)
        assertTrue(store.rows.isEmpty())
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    // ---- Review fixes (security + mesh) ----

    @Test
    fun `sends during a panic wipe are rejected until admission resumes`() {
        peerOffline()
        val router = newRouter()
        runBlocking { withTimeout(10_000) { router.clearAllAndAwait() } }
        assertEquals(MessageRouter.RouteResult.DROPPED, router.sendPrivate("x", peerID, "peer", "during"))
        await(router)
        assertFalse(store.ops.any { it.contains("during") })
        router.resumeAdmissionAfterPanic()
        assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("y", peerID, "peer", "after"))
        await(router)
        assertTrue(store.ops.contains("enqueue:after"))
    }

    @Test
    fun `panic invalidates a pending enqueue so no row survives the wipe`() {
        store = FakeOutboxPersistence(enqueueDelayMs = 50)
        MessageRouter.persistenceOverrideForTesting = store
        peerOffline()
        val router = newRouter()
        router.sendPrivate("a", peerID, "peer", "p1") // may already be in flight
        router.sendPrivate("b", peerID, "peer", "p2") // still pending behind it
        runBlocking { withTimeout(10_000) { router.clearAllAndAwait() } }
        synchronized(store) { store.rows.clear() } // the caller's database wipe
        await(router)
        assertTrue(store.rows.isEmpty())
        assertFalse(store.ops.contains("enqueue:p2"))
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `rehydrate interleaved with panic restores nothing`() {
        store.rows["q1"] = row("q1")
        store.sendingHistory["orphan-1"] = 0L
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        store.loadGate = gate
        val router = newRouter()
        router.rehydrate()
        runBlocking { withTimeout(10_000) { store.loadEntered.await() } }
        router.clearAll() // panic while loadAll is in flight
        gate.complete(Unit)
        await(router)
        peerReady()
        router.onSessionEstablished(peerID)
        router.tickOutbox()
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        assertTrue(statuses.isEmpty())
    }

    @Test
    fun `a delete during rehydrate is not resurrected`() {
        store.rows["q1"] = row("q1")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        store.loadGate = gate
        val router = newRouter()
        router.rehydrate()
        runBlocking { withTimeout(10_000) { store.loadEntered.await() } }
        router.onMessagesRemoved(null, listOf("q1")) // user deletes while the load runs
        gate.complete(Unit)
        await(router)
        assertTrue(store.rows.isEmpty())
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `an ACK arriving during rehydrate removes the row instead of resending`() {
        store.rows["q1"] = row("q1", state = OutboxState.SENT)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        store.loadGate = gate
        val router = newRouter()
        router.rehydrate()
        runBlocking { withTimeout(10_000) { store.loadEntered.await() } }
        AppStateStore.updatePrivateMessageStatus("q1", DeliveryStatus.Delivered(peerID, Date()))
        gate.complete(Unit)
        await(router)
        assertTrue(store.rows.isEmpty())
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `an ACK from a different peer does not remove the row`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        AppStateStore.updatePrivateMessageStatus("m1", DeliveryStatus.Delivered("9999888877776666", Date()))
        await(router)
        assertTrue(store.rows.containsKey("m1"))
    }

    @Test
    fun `blocked before rehydrate completes means no send`() {
        store.rows["q1"] = row("q1", recipientFingerprint = fingerprint)
        val router = newRouter()
        router.isFingerprintBlocked = { it.equals(fingerprint, ignoreCase = true) }
        router.rehydrate()
        await(router)
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        assertEquals(MessageRouter.REASON_BLOCKED, failedReason("q1"))
        await(router)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `flush refuses a peer blocked after queueing`() {
        peerOffline()
        val router = newRouter()
        router.sendPrivate("x", peerID, "peer", "m1")
        router.isFingerprintBlocked = { it.equals(fingerprint, ignoreCase = true) }
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        assertEquals(MessageRouter.REASON_BLOCKED, failedReason("m1"))
    }

    @Test
    fun `expired SENT row is removed in-process and fails with no delivery confirmation`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        await(router)
        statuses.clear() // P2-PR9: a first transmit sets Sent
        peerOffline() // unreachable after the first send: no resend consumes the D3 schedule
        fakeTime += AppConstants.Router.OUTBOX_EXPIRY_MS + 1
        router.tickOutbox()
        await(router)
        assertTrue(store.rows.isEmpty())
        assertEquals(MessageRouter.REASON_NO_CONFIRMATION, failedReason("m1"))
        assertEquals(1, failedCount("m1"))
    }

    @Test
    fun `expired SENT row on rehydrate fails with no delivery confirmation and is not resent`() {
        store.rows["s1"] = row("s1", state = OutboxState.SENT)
        fakeTime += AppConstants.Router.OUTBOX_EXPIRY_MS + 1
        val router = newRouter()
        router.rehydrate()
        await(router)
        assertTrue(store.rows.isEmpty())
        assertEquals(MessageRouter.REASON_NO_CONFIRMATION, failedReason("s1"))
        assertEquals(1, failedCount("s1"))
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `rehydrated SENT entry that expires before resend fails with no delivery confirmation`() {
        store.rows["s1"] = row("s1", state = OutboxState.SENT)
        val router = newRouter()
        router.rehydrate()
        await(router)
        statuses.clear()
        fakeTime += AppConstants.Router.OUTBOX_EXPIRY_MS + 1
        router.tickOutbox()
        await(router)
        assertTrue(store.rows.isEmpty())
        assertEquals(MessageRouter.REASON_NO_CONFIRMATION, failedReason("s1"))
        assertEquals(1, failedCount("s1"))
    }

    @Test
    fun `expired SENT row on rehydrate for a blocked peer fails as blocked, not no confirmation`() {
        store.rows["s1"] = row("s1", recipientFingerprint = fingerprint, state = OutboxState.SENT)
        fakeTime += AppConstants.Router.OUTBOX_EXPIRY_MS + 1
        val router = newRouter()
        router.isFingerprintBlocked = { it.equals(fingerprint, ignoreCase = true) }
        router.rehydrate()
        await(router)
        assertTrue(store.rows.isEmpty())
        assertEquals(MessageRouter.REASON_BLOCKED, failedReason("s1"))
        assertEquals(1, failedCount("s1"))
    }

    @Test
    fun `200 unacknowledged SENT rows do not block a new queued send and the cap evicts oldest`() {
        peerReady()
        val router = newRouter()
        repeat(201) { i ->
            fakeTime += 1
            assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("c$i", peerID, "peer", "s$i"))
        }
        await(router)
        assertEquals(200, store.rows.size)
        assertFalse(store.rows.containsKey("s0")) // oldest SENT evicted ...
        assertEquals(MessageRouter.REASON_NO_CONFIRMATION, failedReason("s0")) // ... as Failed, so Retry is offered
        assertEquals(1, failedCount("s0"))
        assertEquals(0, (1..200).count { failedCount("s$it") > 0 })
        peerOffline()
        assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("q", peerID, "peer", "q1"))
        await(router)
        assertEquals(OutboxState.QUEUED, store.rows["q1"]!!.state)
        // P2-PR9: Sent only; the single Failed is the evicted s0 (P2-PR13).
        assertTrue(statuses.filter { it.first != "s0" }.all { it.second is DeliveryStatus.Sent })
    }

    @Test
    fun `flush that pushes SENT over the cap fails the oldest, sends the flushed message, and does not crash`() {
        peerReady()
        val router = newRouter()
        repeat(200) { i ->
            fakeTime += 1
            router.sendPrivate("c$i", peerID, "peer", "s$i")
        }
        peerOffline()
        fakeTime += 1
        assertEquals(MessageRouter.RouteResult.QUEUED, router.sendPrivate("q", peerID, "peer", "q1"))
        await(router)

        peerReady()
        router.onSessionEstablished(peerID) // QUEUED -> SENT: 201 SENT rows
        await(router)

        verify(mesh).sendPrivateMessage(any(), any(), any(), org.mockito.kotlin.eq("q1"))
        assertEquals(OutboxState.SENT, store.rows["q1"]!!.state)
        assertEquals(200, store.rows.size)
        assertFalse(store.rows.containsKey("s0"))
        assertEquals(MessageRouter.REASON_NO_CONFIRMATION, failedReason("s0"))
        assertEquals(1, failedCount("s0"))
        assertEquals(0, failedCount("q1"))
    }

    @Test
    fun `null recipient fingerprint is filled from the authenticated session on send`() {
        store.rows["q1"] = row("q1", recipientFingerprint = null)
        val router = newRouter()
        router.rehydrate()
        await(router)
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, times(1)).sendPrivateMessage("content", peerID, "peer", "q1")
        await(router)
        assertEquals(fingerprint, store.rows["q1"]!!.recipientFingerprint)
        assertEquals(OutboxState.SENT, store.rows["q1"]!!.state)
    }

    @Test
    fun `rehydrate persists the re-canonicalised conversation id`() {
        store.rows["q1"] = row("q1") // stored under the raw mesh peer ID
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = false))
        val router = newRouter()
        router.rehydrate()
        await(router)
        val canonical = ContactDirectory.canonicalConversationId(peerID)
        assertEquals(canonical, store.rows["q1"]!!.conversationId)
    }

    @Test
    fun `failed load is retried on the next tick`() {
        store.rows["q1"] = row("q1")
        store.failNextLoad = true
        val router = newRouter()
        router.rehydrate()
        await(router)
        router.tickOutbox()
        await(router)
        assertEquals(2, store.loadCount)
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, times(1)).sendPrivateMessage("content", peerID, "peer", "q1")
    }

    @Test
    fun `funnel removePrivateMessage reaches the router`() {
        peerOffline()
        val router = newRouter()
        router.sendPrivate("x", peerID, "peer", "m1")
        AppStateStore.addPrivateMessage(peerID, message("m1"))
        AppStateStore.removePrivateMessage("m1")
        await(router)
        assertTrue(store.rows.isEmpty())
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `funnel deletePrivateConversation reaches the router`() {
        peerOffline()
        val router = newRouter()
        router.sendPrivate("x", peerID, "peer", "m1")
        AppStateStore.deletePrivateConversation(peerID)
        await(router)
        assertTrue(store.rows.isEmpty())
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `concurrent flush and tick send each queued message exactly once`() {
        peerOffline()
        val router = newRouter()
        val ids = (0 until 100).map { "c$it" }
        ids.forEach { router.sendPrivate("x", peerID, "peer", it) }
        peerReady()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (0 until 4).map { n ->
            Thread {
                start.await()
                repeat(20) { if (n % 2 == 0) router.tickOutbox() else router.flushOutboxFor(peerID) }
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(10_000) }
        ids.forEach { verify(mesh, times(1)).sendPrivateMessage("x", peerID, "peer", it) }
        await(router)
        ids.forEach { assertEquals(OutboxState.SENT, store.rows[it]!!.state) }
    }

    private fun message(id: String) = com.bitchat.android.model.BitchatMessage(
        id = id,
        sender = "me",
        content = "x",
        timestamp = Date(fakeTime),
        isPrivate = true,
        recipientNickname = "peer",
        senderPeerID = myPeerID,
        deliveryStatus = DeliveryStatus.Sending
    )

    private fun row(
        id: String,
        conversationId: String = peerID,
        recipientFingerprint: String? = null,
        state: OutboxState = OutboxState.QUEUED
    ) = OutboxEntry(
        messageId = id,
        conversationId = conversationId,
        toPeerId = conversationId,
        content = "content",
        recipientNickname = "peer",
        originalTimestampMs = fakeTime,
        recipientFingerprint = recipientFingerprint,
        state = state,
        createdAt = fakeTime,
        nextAttemptAt = fakeTime
    )

    private fun peerOffline() {
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = false))
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(false)
    }

    private fun peerReady() {
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = true))
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(true)
        // Authenticated by the Noise handshake (PeerFingerprintManager).
        whenever(mesh.getPeerFingerprint(peerID)).thenReturn(fingerprint)
    }

    private fun peerInfo(isConnected: Boolean) = PeerInfo(
        id = peerID,
        nickname = "peer",
        isConnected = isConnected,
        isDirectConnection = true,
        noisePublicKey = noiseKey,
        signingPublicKey = ByteArray(32) { 0x0A },
        isVerifiedNickname = false,
        lastSeen = System.currentTimeMillis()
    )
}
