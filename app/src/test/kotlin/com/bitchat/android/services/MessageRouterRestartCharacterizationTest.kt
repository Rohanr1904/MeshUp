package com.bitchat.android.services

import android.content.Context
import android.os.Build
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Phase 0.5 characterization tests for the RAM-only DM outbox in [MessageRouter].
 * Synthetic IDs only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class MessageRouterRestartCharacterizationTest {

    private val myPeerID = "1111222233334444"
    private val peerID = "aaaabbbbccccdddd"
    private val noiseKey = ByteArray(32) { 0x0B }

    private lateinit var context: Context
    private lateinit var mesh: MeshService

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(
            "message-router-restart-test-${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        val identityManager = SecureIdentityStateManager(prefs, testOnly = true)
        ContactDirectory.identityManagerProvider = { identityManager }

        mesh = mock()
        whenever(mesh.myPeerID).thenReturn(myPeerID)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "peer"))
        ContactDirectory.initialize(context) { mesh }

        MessageRouter.disableSchedulerForTesting = true
        MessageRouter.resetForTesting()
    }

    @After
    fun tearDown() {
        MessageRouter.resetForTesting()
        MessageRouter.disableSchedulerForTesting = false
        ContactDirectory.identityManagerProvider = { SecureIdentityStateManager(it) }
    }

    /**
     * KNOWN DEFECT TD-01: the DM outbox is RAM-only (ConcurrentHashMap in MessageRouter),
     * so a queued DM is lost when the process dies and the `Sending` DB row is orphaned.
     *
     * This test pins the CURRENT (defective) behaviour: after a simulated restart the
     * queued message is never delivered. The correct expectation is exactly ONE delivery
     * after restart. The fix (TARGET_ARCHITECTURE R-1 durable outbox) must INVERT the
     * final assertion (times(1) instead of never()).
     *
     * Restart is simulated with resetForTesting() + getInstance(), which discards the
     * old instance and its in-memory outbox, as process death would.
     */
    @Test
    fun knownDefect_TD01_queuedDmIsLostAcrossRestart() {
        peerOffline()
        val oldRouter = MessageRouter.getInstance(context, mesh)
        val result = oldRouter.sendPrivate("hello", peerID, "peer", "msg-1")
        assertEquals(MessageRouter.RouteResult.QUEUED, result)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())

        // Simulated process restart: old instance and outbox are gone.
        MessageRouter.resetForTesting()
        val newRouter = MessageRouter.getInstance(context, mesh)
        assertNotSame(oldRouter, newRouter)

        peerReady()
        newRouter.onPeersUpdated(listOf(peerID))
        newRouter.onSessionEstablished(peerID)
        newRouter.flushAllOutbox()
        newRouter.tickOutbox()

        // CURRENT (defective) behaviour: nothing delivered. Correct: times(1).
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    /** Control: without a restart the same flush path delivers the queued DM exactly once. */
    @Test
    fun control_queuedDmIsDeliveredOnceWithoutRestart() {
        peerOffline()
        val router = MessageRouter.getInstance(context, mesh)
        val result = router.sendPrivate("hello", peerID, "peer", "msg-1")
        assertEquals(MessageRouter.RouteResult.QUEUED, result)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())

        peerReady()
        router.onPeersUpdated(listOf(peerID))
        router.onSessionEstablished(peerID)
        router.flushAllOutbox()
        router.tickOutbox()

        verify(mesh, times(1)).sendPrivateMessage("hello", peerID, "peer", "msg-1")
    }

    private fun peerOffline() {
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = false))
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(false)
    }

    private fun peerReady() {
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = true))
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(true)
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
