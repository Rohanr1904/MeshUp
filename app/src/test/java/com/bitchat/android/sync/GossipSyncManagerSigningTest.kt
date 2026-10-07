package com.bitchat.android.sync

import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class GossipSyncManagerSigningTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() { scope.cancel() }

    private val config = object : GossipSyncManager.ConfigProvider {
        override fun seenCapacity() = 500
        override fun gcsMaxBytes() = 400
        override fun gcsTargetFpr() = 0.01
    }

    private class RecordingDelegate(val signResult: (BitchatPacket) -> BitchatPacket?) : GossipSyncManager.Delegate {
        val signCalls = AtomicInteger()
        val sends = AtomicInteger()
        val latch = CountDownLatch(1)
        override fun sendPacket(packet: BitchatPacket) { sends.incrementAndGet(); latch.countDown() }
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) { sends.incrementAndGet(); latch.countDown() }
        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket? {
            signCalls.incrementAndGet()
            return signResult(packet).also { if (it == null) latch.countDown() }
        }
    }

    private fun manager() = GossipSyncManager("1122334455667788", scope, config)

    @Test
    fun `broadcast request sync is skipped when signing fails`() {
        val d = RecordingDelegate { null }
        val m = manager().also { it.delegate = d }
        m.scheduleInitialSync(0)
        assertTrue(d.latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        // The periodic sync may also fire during the wait; what matters is that nothing was sent.
        assertTrue(d.signCalls.get() >= 1)
        assertEquals(0, d.sends.get())
    }

    @Test
    fun `peer request sync is skipped when signing fails`() {
        val d = RecordingDelegate { null }
        val m = manager().also { it.delegate = d }
        m.scheduleInitialSyncToPeer("8877665544332211", 0)
        assertTrue(d.latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        // The periodic sync may also fire during the wait; what matters is that nothing was sent.
        assertTrue(d.signCalls.get() >= 1)
        assertEquals(0, d.sends.get())
    }

    @Test
    fun `request sync is sent when signing succeeds`() {
        val d = RecordingDelegate { it.copy(signature = ByteArray(64)) }
        val m = manager().also { it.delegate = d }
        m.scheduleInitialSync(0)
        assertTrue(d.latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, d.sends.get())
    }
}
