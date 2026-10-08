package com.bitchat.android.services

import android.content.Context
import android.os.Build
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
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

/** P2-PR9: D3 resend, Failed, session race (R-1) and D5 retry. Synthetic IDs only, no sleeps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class MessageRouterResendRetryTest {

    private val myPeerID = "1111222233334444"
    private val peerID = "aaaabbbbccccdddd"
    private val noiseKey = ByteArray(32) { 0x0B }
    private val fingerprint = ContactIdentityResolver.fingerprintHex(noiseKey)
    private val backoff = AppConstants.Router.OUTBOX_RESEND_BACKOFF_MS

    private lateinit var context: Context
    private lateinit var mesh: MeshService
    private lateinit var store: FakeOutboxPersistence
    private val statuses = Collections.synchronizedList(mutableListOf<Pair<String, DeliveryStatus>>())
    private var fakeTime = 10_000_000L

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("router-pr9-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val identityManager = SecureIdentityStateManager(prefs, testOnly = true)
        ContactDirectory.identityManagerProvider = { identityManager }

        mesh = mock()
        transportReports(true)
        whenever(mesh.myPeerID).thenReturn(myPeerID)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "peer"))
        ContactDirectory.initialize(context) { mesh }

        store = FakeOutboxPersistence()
        statuses.clear()
        fakeTime = 10_000_000L
        AppStateStore.clear()
        MessageRouter.disableSchedulerForTesting = true
        MessageRouter.persistenceOverrideForTesting = store
        // Real AppStateStore too, so late ACKs and retry lookups are end to end.
        MessageRouter.statusSinkOverrideForTesting = { id, status ->
            statuses += id to status
            AppStateStore.updatePrivateMessageStatus(id, status)
        }
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

    @Test
    fun `d3 schedule resends then fails`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        val t0 = fakeTime
        verifySends("m1", 1)

        tick(router, t0 + backoff[0] - 1)
        verifySends("m1", 1)

        // Resends at +30 s, then +1 min, +2 min, +2 min after the previous one.
        var at = t0
        backoff.forEachIndexed { index, delay ->
            at += delay
            tick(router, at)
            verifySends("m1", index + 2)
            val row = store.rows["m1"]!!
            assertEquals(index + 2, row.attempts)
            assertEquals(at + backoff[(index + 1).coerceAtMost(backoff.size - 1)], row.nextAttemptAt)
        }
        assertNull(failedReason("m1"))

        // Final ACK wait (last backoff value), then Failed and the row is removed.
        tick(router, at + backoff.last() - 1)
        assertNull(failedReason("m1"))
        tick(router, at + backoff.last())
        verifySends("m1", backoff.size + 1)
        assertEquals(MessageRouter.REASON_NO_CONFIRMATION, failedReason("m1"))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `ack between attempts stops resends`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        val t0 = fakeTime
        tick(router, t0 + backoff[0])
        verifySends("m1", 2)
        AppStateStore.updatePrivateMessageStatus("m1", DeliveryStatus.Delivered(peerID, Date()))
        await(router)
        tick(router, t0 + 30L * 60_000L)
        verifySends("m1", 2)
        assertNull(failedReason("m1"))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `expiry tick with the peer ready fails once and does not resend`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        tick(router, fakeTime + AppConstants.Router.OUTBOX_EXPIRY_MS + 1)
        verifySends("m1", 1)
        assertTrue(store.rows.isEmpty())
        assertEquals(MessageRouter.REASON_NO_CONFIRMATION, failedReason("m1"))
        assertEquals(1, statuses.count { it.first == "m1" && it.second is DeliveryStatus.Failed })
    }

    @Test
    fun `one hour Failed offers retry with the same id and a late ack upgrades it`() {
        peerReady()
        val router = newRouter()
        AppStateStore.addPrivateMessage(peerID, ownMessage("m1", DeliveryStatus.Sending))
        router.sendPrivate("hi", peerID, "peer", "m1")
        peerOffline()
        tick(router, fakeTime + AppConstants.Router.OUTBOX_EXPIRY_MS + 1)
        assertEquals(
            MessageRouter.REASON_NO_CONFIRMATION,
            (storedStatus("m1") as? DeliveryStatus.Failed)?.reason
        )

        peerReady()
        assertNotNull(router.retry("m1"))
        verifySends("m1", 2)

        AppStateStore.updatePrivateMessageStatus("m1", DeliveryStatus.Delivered(peerID, Date()))
        assertTrue(storedStatus("m1") is DeliveryStatus.Delivered)
    }

    @Test
    fun `late ack upgrades failed`() {
        peerReady()
        val router = newRouter()
        AppStateStore.addPrivateMessage(peerID, ownMessage("m1", DeliveryStatus.Sending))
        router.sendPrivate("hi", peerID, "peer", "m1")
        runScheduleToFailure(router)
        assertTrue(storedStatus("m1") is DeliveryStatus.Failed)

        AppStateStore.updatePrivateMessageStatus("m1", DeliveryStatus.Delivered(peerID, Date()))
        assertTrue(storedStatus("m1") is DeliveryStatus.Delivered)
    }

    @Test
    fun `unreachable peer consumes no attempt`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        val t0 = fakeTime
        peerOffline()
        tick(router, t0 + 10L * 60_000L)
        verifySends("m1", 1)
        assertEquals(1, store.rows["m1"]!!.attempts)

        peerReady()
        tick(router, t0 + 11L * 60_000L)
        verifySends("m1", 2)
        assertEquals(2, store.rows["m1"]!!.attempts)

        // Still bounded by 1 h: removed and Failed, so Retry is offered (amendment 3).
        peerOffline()
        tick(router, t0 + AppConstants.Router.OUTBOX_EXPIRY_MS + 1)
        assertTrue(store.rows.isEmpty())
        assertEquals(MessageRouter.REASON_NO_CONFIRMATION, failedReason("m1"))
    }

    @Test
    fun `session race requeues without attempt`() {
        peerReady()
        transportReports(false)
        val router = newRouter()
        assertEquals(MessageRouter.RouteResult.MESH, router.sendPrivate("hi", peerID, "peer", "m1"))
        await(router)
        val row = store.rows["m1"]!!
        assertEquals(OutboxState.QUEUED, row.state)
        assertEquals(0, row.attempts)
        assertEquals(listOf("enqueue", "markQueued"), store.opsFor("m1"))
        verify(mesh, atLeastOnce()).initiateNoiseHandshake(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())

        // Session comes up: sent once, now SENT with attempt 1.
        transportReports(true)
        router.onSessionEstablished(peerID)
        await(router)
        verifySends("m1", 1)
        assertEquals(OutboxState.SENT, store.rows["m1"]!!.state)
        router.onSessionEstablished(peerID)
        verifySends("m1", 1)
    }

    @Test
    fun `refused resend keeps attempt count`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        val t0 = fakeTime
        transportReports(false)
        tick(router, t0 + backoff[0])
        val row = store.rows["m1"]!!
        assertEquals(OutboxState.SENT, row.state)
        assertEquals(1, row.attempts)
        transportReports(true)
        tick(router, t0 + backoff[0] + 2_000L)
        verifySends("m1", 2)
        assertEquals(2, store.rows["m1"]!!.attempts)
    }

    @Test
    fun `retry resends failed with same id`() {
        peerReady()
        val router = newRouter()
        AppStateStore.addPrivateMessage(peerID, ownMessage("m1", DeliveryStatus.Failed("x")))
        assertEquals(MessageRouter.RouteResult.QUEUED, router.retry("m1"))
        verify(mesh, times(1)).sendPrivateMessage("hi", peerID, "peer", "m1")
        assertTrue(statuses.any { it.first == "m1" && it.second is DeliveryStatus.Sending })
        await(router)
        assertEquals(OutboxState.SENT, store.rows["m1"]!!.state)
        // Not Failed any more: a second retry is refused.
        assertNull(router.retry("m1"))
    }

    @Test
    fun `retry refused for blocked peer`() {
        peerReady()
        val router = newRouter()
        router.isFingerprintBlocked = { it.equals(fingerprint, ignoreCase = true) }
        AppStateStore.addPrivateMessage(peerID, ownMessage("m1", DeliveryStatus.Failed("x")))
        assertNull(router.retry("m1"))
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        assertTrue(storedStatus("m1") is DeliveryStatus.Failed)
    }

    @Test
    fun `retry refused for nostr alias`() {
        val router = newRouter()
        val alias = "nostr_abcdef0123456789"
        AppStateStore.addPrivateMessage(alias, ownMessage("m1", DeliveryStatus.Failed("x")))
        assertNull(router.retry("m1"))
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        assertTrue(storedStatus("m1") is DeliveryStatus.Failed)
    }

    @Test
    fun `retry refused during panic`() {
        peerReady()
        val router = newRouter()
        AppStateStore.addPrivateMessage(peerID, ownMessage("m1", DeliveryStatus.Failed("x")))
        runBlocking { router.clearAllAndAwait() }
        assertNull(router.retry("m1"))
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        router.resumeAdmissionAfterPanic()
        assertEquals(MessageRouter.RouteResult.QUEUED, router.retry("m1"))
    }

    @Test
    fun `retry over limit is queue full`() {
        peerOffline()
        val router = newRouter()
        repeat(AppConstants.Router.OUTBOX_PER_PEER_LIMIT) { router.sendPrivate("c$it", peerID, "peer", "q$it") }
        AppStateStore.addPrivateMessage(peerID, ownMessage("m1", DeliveryStatus.Failed("x")))
        assertEquals(MessageRouter.RouteResult.FAILED, router.retry("m1"))
        assertEquals(MessageRouter.REASON_QUEUE_FULL, failedReason("m1"))
    }

    @Test
    fun `retry ignores incoming messages`() {
        peerReady()
        val router = newRouter()
        AppStateStore.addPrivateMessage(
            peerID,
            ownMessage("m1", DeliveryStatus.Failed("x")).copy(senderPeerID = peerID, sender = "peer")
        )
        assertNull(router.retry("m1"))
    }

    @Test
    fun `retry refused after recipient changed`() {
        peerReady()
        val router = newRouter()
        AppStateStore.addPrivateMessage(
            peerID, ownMessage("m1", DeliveryStatus.Failed(MessageRouter.REASON_RECIPIENT_CHANGED))
        )
        assertNull(router.retry("m1"))
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `retry to changed identity fails`() {
        peerReady()
        whenever(mesh.getPeerFingerprint(peerID)).thenReturn(otherFingerprint)
        val router = newRouter()
        AppStateStore.addPrivateMessage(peerID, ownMessage("m1", DeliveryStatus.Failed("x")))
        router.retry("m1")
        await(router)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        assertEquals(MessageRouter.REASON_RECIPIENT_CHANGED, failedReason("m1"))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `first transmit sets sent`() {
        peerReady()
        val router = newRouter()
        AppStateStore.addPrivateMessage(peerID, ownMessage("m1", DeliveryStatus.Sending))
        router.sendPrivate("hi", peerID, "peer", "m1")
        assertTrue(storedStatus("m1") is DeliveryStatus.Sent)
        // A D3 resend does not touch the status; Delivered still upgrades.
        tick(router, fakeTime + backoff[0])
        AppStateStore.updatePrivateMessageStatus("m1", DeliveryStatus.Delivered(peerID, Date()))
        assertTrue(storedStatus("m1") is DeliveryStatus.Delivered)
    }

    @Test
    fun `retried message becomes sent`() {
        peerReady()
        val router = newRouter()
        AppStateStore.addPrivateMessage(peerID, ownMessage("m1", DeliveryStatus.Failed("x")))
        router.retry("m1")
        assertTrue(storedStatus("m1") is DeliveryStatus.Sent)
    }

    @Test
    fun `block before drain drops resend`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        router.beforeTransmitForTesting = { router.isFingerprintBlocked = { true } }
        tick(router, fakeTime + backoff[0])
        verifySends("m1", 1)
        assertEquals(MessageRouter.REASON_BLOCKED, failedReason("m1"))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `delete before drain drops resend`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        router.beforeTransmitForTesting = { router.onMessagesRemoved(null, listOf("m1")) }
        tick(router, fakeTime + backoff[0])
        verifySends("m1", 1)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `identity change before drain drops resend`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        router.beforeTransmitForTesting = {
            whenever(mesh.getPeerFingerprint(peerID)).thenReturn(otherFingerprint)
        }
        tick(router, fakeTime + backoff[0])
        verifySends("m1", 1)
        assertEquals(MessageRouter.REASON_RECIPIENT_CHANGED, failedReason("m1"))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `d3 refused on identity change`() {
        peerReady()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        whenever(mesh.getPeerFingerprint(peerID)).thenReturn(otherFingerprint)
        tick(router, fakeTime + backoff[0])
        verifySends("m1", 1)
        assertEquals(MessageRouter.REASON_RECIPIENT_CHANGED, failedReason("m1"))
    }

    @Test
    fun `async refusal requeues later`() {
        peerReady()
        val callbacks = captureCallbacks()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        await(router)
        assertEquals(OutboxState.SENT, store.rows["m1"]!!.state)
        callbacks.single().invoke(false)
        await(router)
        assertEquals(OutboxState.QUEUED, store.rows["m1"]!!.state)
        assertEquals(0, store.rows["m1"]!!.attempts)
    }

    @Test
    fun `refusal after ack restores nothing`() {
        peerReady()
        val callbacks = captureCallbacks()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        AppStateStore.updatePrivateMessageStatus("m1", DeliveryStatus.Delivered(peerID, Date()))
        callbacks.single().invoke(false)
        await(router)
        assertTrue(store.rows.isEmpty())
        tick(router, fakeTime + 10L * 60_000L)
        verify(mesh, never()).initiateNoiseHandshake(peerID)
    }

    @Test
    fun `refusal after block restores nothing`() {
        peerReady()
        val callbacks = captureCallbacks()
        val router = newRouter()
        router.sendPrivate("hi", peerID, "peer", "m1")
        router.dropConversation(peerID)
        callbacks.single().invoke(false)
        await(router)
        assertTrue(store.rows.isEmpty())
        assertEquals(MessageRouter.REASON_BLOCKED, failedReason("m1"))
    }

    @Test
    fun `rehydrate attempts 0 resends`() {
        peerReady()
        store.rows["r0"] = sentRow("r0", attempts = 0)
        val router = newRouter()
        router.rehydrate()
        await(router)
        router.flushOutboxFor(peerID)
        await(router)
        verifySends("r0", 1)
        assertEquals(2, store.rows["r0"]!!.attempts)
    }

    @Test
    fun `rehydrate attempts 5 fails`() {
        peerReady()
        store.rows["r5"] = sentRow("r5", attempts = 5)
        val router = newRouter()
        router.rehydrate()
        await(router)
        router.flushOutboxFor(peerID)
        verifySends("r5", 0)
        tick(router, fakeTime + 2_000L)
        verifySends("r5", 0)
        assertEquals(MessageRouter.REASON_NO_CONFIRMATION, failedReason("r5"))
        assertTrue(store.rows.isEmpty())
    }

    // --- helpers ---

    private val otherFingerprint = "f".repeat(64)

    private fun captureCallbacks(): MutableList<(Boolean) -> Unit> {
        val callbacks = Collections.synchronizedList(mutableListOf<(Boolean) -> Unit>())
        doAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            callbacks += invocation.arguments[4] as (Boolean) -> Unit
            Unit
        }.whenever(mesh).sendPrivateMessageReporting(any(), any(), any(), any(), any())
        return callbacks
    }

    private fun sentRow(id: String, attempts: Int) = OutboxEntry(
        messageId = id,
        conversationId = ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKey),
        toPeerId = ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKey),
        content = "content",
        recipientNickname = "peer",
        originalTimestampMs = fakeTime,
        recipientFingerprint = fingerprint,
        state = OutboxState.SENT,
        attempts = attempts,
        createdAt = fakeTime,
        nextAttemptAt = fakeTime
    )

    private fun newRouter(): MessageRouter =
        MessageRouter.getInstance(context, mesh).also { it.clock = { fakeTime } }

    private fun tick(router: MessageRouter, at: Long) {
        fakeTime = at
        router.tickOutbox(at)
        await(router)
    }

    private fun runScheduleToFailure(router: MessageRouter) {
        var at = fakeTime
        backoff.forEach { at += it; tick(router, at) }
        tick(router, at + backoff.last())
        assertEquals(MessageRouter.REASON_NO_CONFIRMATION, failedReason("m1"))
    }

    private fun await(router: MessageRouter) = runBlocking {
        withTimeout(10_000) { router.awaitOutboxWrites() }
    }

    private fun verifySends(id: String, count: Int) =
        verify(mesh, times(count)).sendPrivateMessage(any(), any(), any(), org.mockito.kotlin.eq(id))

    private fun failedReason(id: String): String? =
        statuses.lastOrNull { it.first == id }?.second?.let { (it as? DeliveryStatus.Failed)?.reason }

    private fun storedStatus(id: String): DeliveryStatus? =
        AppStateStore.privateMessages.value.values.flatten().firstOrNull { it.id == id }?.deliveryStatus

    /** true: the real default (session check + sendPrivateMessage); false: transport has no session. */
    private fun transportReports(sent: Boolean) {
        if (sent) {
            doCallRealMethod().whenever(mesh).sendPrivateMessageReporting(any(), any(), any(), any(), any())
        } else {
            doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                (invocation.arguments[4] as (Boolean) -> Unit).invoke(false)
            }.whenever(mesh).sendPrivateMessageReporting(any(), any(), any(), any(), any())
        }
    }

    private fun ownMessage(id: String, status: DeliveryStatus) = BitchatMessage(
        id = id,
        sender = "me",
        content = "hi",
        timestamp = Date(fakeTime),
        isPrivate = true,
        recipientNickname = "peer",
        senderPeerID = myPeerID,
        deliveryStatus = status
    )

    private fun peerOffline() {
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = false))
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(false)
    }

    private fun peerReady() {
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = true))
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(true)
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
