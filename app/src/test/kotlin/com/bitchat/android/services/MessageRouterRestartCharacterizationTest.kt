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
import kotlinx.coroutines.runBlocking
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
    private lateinit var store: FakeOutboxPersistence

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
        // P2-PR8: the durable outbox, backed by an in-memory store that outlives the router.
        store = FakeOutboxPersistence()
        MessageRouter.persistenceOverrideForTesting = store
        MessageRouter.statusSinkOverrideForTesting = { _, _ -> }
        MessageRouter.resetForTesting()
    }

    @After
    fun tearDown() {
        MessageRouter.resetForTesting()
        MessageRouter.disableSchedulerForTesting = false
        MessageRouter.persistenceOverrideForTesting = null
        MessageRouter.statusSinkOverrideForTesting = null
        ContactDirectory.identityManagerProvider = { SecureIdentityStateManager(it) }
    }

    /**
     * TD-01 (fixed by P2-PR8, R-1 durable outbox): a queued DM survives process death and is
     * delivered exactly once, with the same message ID, after the peer reappears.
     *
     * Restart is simulated with resetForTesting() + getInstance(), which discards the old
     * instance and its in-memory queue, as process death would; the persisted outbox survives
     * and is restored by rehydrate().
     */
    @Test
    fun td01_queuedDmSurvivesRestartAndIsDeliveredOnce() {
        peerOffline()
        val oldRouter = MessageRouter.getInstance(context, mesh)
        val result = oldRouter.sendPrivate("hello", peerID, "peer", "msg-1")
        assertEquals(MessageRouter.RouteResult.QUEUED, result)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())

        // Simulated process restart: the old instance and its RAM queue are gone.
        runBlocking { oldRouter.awaitOutboxWrites() }
        MessageRouter.resetForTesting()
        val newRouter = MessageRouter.getInstance(context, mesh)
        assertNotSame(oldRouter, newRouter)
        newRouter.rehydrate()
        runBlocking { newRouter.awaitOutboxWrites() }

        peerReady()
        newRouter.onPeersUpdated(listOf(peerID))
        newRouter.onSessionEstablished(peerID)
        newRouter.flushAllOutbox()
        newRouter.tickOutbox()

        verify(mesh, times(1)).sendPrivateMessage(any(), any(), any(), anyOrNull())
        verify(mesh, times(1)).sendPrivateMessage("hello", peerID, "peer", "msg-1")
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
        // Authenticated by the Noise handshake (PeerFingerprintManager).
        whenever(mesh.getPeerFingerprint(peerID))
            .thenReturn(ContactIdentityResolver.fingerprintHex(noiseKey))
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
