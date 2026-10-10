package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.util.AppConstants
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketProcessorBoundingTest {
    private val processors = mutableListOf<PacketProcessor>()
    private val clock = AtomicLong(1_000_000_000_000L)

    @After
    fun tearDown() {
        processors.forEach(PacketProcessor::shutdown)
    }

    @Test
    fun `forged peer ids from one link keep structures bounded`() {
        val processor = processor(RecordingDelegate())

        repeat(10_000) { processor.processPacket(routed("forged$it", "link-A", seq = it)) }
        assertEquals(AppConstants.Mesh.STRIPES, processor.stripeCount)
        assertEquals(1, processor.linkBucketCount)

        // Many distinct links: bucket map stays at the LRU bound.
        repeat(AppConstants.Mesh.MAX_LINK_BUCKETS * 3) {
            processor.processPacket(routed("peer", "link-$it", seq = it))
        }
        assertTrue(processor.linkBucketCount <= AppConstants.Mesh.MAX_LINK_BUCKETS)
        assertEquals(AppConstants.Mesh.STRIPES, processor.stripeCount)
    }

    @Test
    fun `per-link rate limit starts small then refills and isolates links`() {
        val processor = processor(RecordingDelegate())
        val initial = AppConstants.Mesh.LINK_INITIAL_TOKENS

        // A fresh link only has LINK_INITIAL_TOKENS, not LINK_BURST.
        repeat(initial + 10) { processor.processPacket(routed("p$it", "link-A", seq = it)) }
        assertEquals(10L, processor.droppedPacketCount)

        // 1s refills LINK_RATE_PER_SEC tokens.
        clock.addAndGet(1_000_000_000L)
        repeat(AppConstants.Mesh.LINK_RATE_PER_SEC + 50) {
            processor.processPacket(routed("q$it", "link-A", seq = it))
        }
        assertEquals(60L, processor.droppedPacketCount)

        // A second link is unaffected.
        repeat(initial) { processor.processPacket(routed("r$it", "link-B", seq = it)) }
        assertEquals(60L, processor.droppedPacketCount)
    }

    @Test
    fun `ingressLinkID takes precedence over relayAddress as limiter key`() {
        val processor = processor(RecordingDelegate())
        val initial = AppConstants.Mesh.LINK_INITIAL_TOKENS
        repeat(initial + 10) {
            processor.processPacket(routed("p$it", relay = "same-relay", ingress = "ingress-A", seq = it))
        }
        assertEquals(10L, processor.droppedPacketCount)
        processor.processPacket(routed("x", relay = "same-relay", ingress = "ingress-B", seq = 0))
        assertEquals(10L, processor.droppedPacketCount)
    }

    @Test
    fun `reconnect cycling cannot exceed the global bucket`() {
        val processor = processor(RecordingDelegate())
        val links = 30
        val perLink = AppConstants.Mesh.LINK_INITIAL_TOKENS
        repeat(links) { l ->
            val link = UUID.randomUUID().toString()
            repeat(perLink) { processor.processPacket(routed("f$l-$it", link, seq = it)) }
        }
        // Every link's own bucket admitted all of its packets; the global bucket capped the total.
        val attempted = links * perLink
        assertEquals((attempted - AppConstants.Mesh.GLOBAL_BURST).toLong(), processor.droppedPacketCount)
    }

    @Test
    fun `same peer packets are processed in arrival order`() {
        val delegate = RecordingDelegate()
        val processor = processor(delegate)
        val n = AppConstants.Mesh.LINK_INITIAL_TOKENS
        delegate.expect(n)

        repeat(n) { processor.processPacket(routed("peerFIFO", "link-A", seq = it)) }

        assertTrue(delegate.await())
        assertEquals((0 until n).toList(), delegate.seen.toList())
        assertEquals(0L, processor.droppedPacketCount)
    }

    @Test
    fun `blocked stripe drops oldest and keeps newest`() {
        val delegate = RecordingDelegate()
        val processor = processor(delegate)
        delegate.blockFirst = true

        processor.processPacket(routed("peerD", "link-first", seq = 0))
        assertTrue(delegate.firstEntered.await(2, TimeUnit.SECONDS))

        // 6 links x 50 = 300 packets queued behind the blocked one; capacity 256, per-link quota 64.
        val extra = 300
        val capacity = AppConstants.Mesh.STRIPE_CAPACITY
        repeat(extra) { processor.processPacket(routed("peerD", "link-${it / 50}", seq = it + 1)) }
        assertEquals((extra - capacity).toLong(), processor.droppedPacketCount)

        delegate.expect(1 + capacity)
        delegate.release.countDown()
        assertTrue(delegate.await())
        val expected = listOf(0) + ((extra - capacity + 1)..extra).toList()
        assertEquals(expected, delegate.seen.toList())
    }

    @Test
    fun `flooding link cannot evict another link's packets on a claimed peer id`() {
        val delegate = RecordingDelegate()
        val processor = processor(delegate)
        delegate.blockFirst = true

        processor.processPacket(routed("victim", "link-first", seq = 0))
        assertTrue(delegate.firstEntered.await(2, TimeUnit.SECONDS))

        val quota = AppConstants.Mesh.STRIPE_LINK_QUOTA
        val flood = 100
        repeat(50) { processor.processPacket(routed("victim", "link-A", seq = it + 1)) }
        clock.addAndGet(1_000_000_000L) // refill link-A so the quota (not the rate limit) is what binds
        repeat(flood - 50) { processor.processPacket(routed("victim", "link-A", seq = it + 51)) }
        assertEquals((flood - quota).toLong(), processor.droppedPacketCount)

        val victimSeqs = (1001..1010).toList()
        victimSeqs.forEach { processor.processPacket(routed("victim", "link-B", seq = it)) }
        assertEquals((flood - quota).toLong(), processor.droppedPacketCount)

        delegate.expect(1 + quota + victimSeqs.size)
        delegate.release.countDown()
        assertTrue(delegate.await())
        assertEquals(1 + quota + victimSeqs.size, delegate.seen.size)
        assertEquals(victimSeqs, delegate.seen.filter { it >= 1001 })
    }

    @Test
    fun `multiple producers keep per-producer order and lose nothing`() {
        val delegate = RecordingDelegate()
        val step = AtomicLong(1_000_000_000_000L)
        // Every clock read jumps 10s so no limiter ever binds.
        val processor = PacketProcessor(MY_PEER_ID) { step.addAndGet(10_000_000_000L) }
            .also { it.delegate = delegate; processors += it }
        val threads = 4
        val perThread = 500
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        val workers = (0 until threads).map { t ->
            Thread {
                try {
                    val peer = "mp$t"
                    repeat(perThread) { i ->
                        // Back-pressure so the per-link stripe quota never binds.
                        while (i - delegate.countFor(peer) >= 30) Thread.sleep(0, 100_000)
                        processor.processPacket(routed(peer, "mpLink$t", seq = i))
                    }
                } catch (e: Throwable) {
                    failures += e
                }
            }.also { it.start() }
        }
        workers.forEach { it.join(30_000) }
        assertTrue(failures.isEmpty())

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            (0 until threads).any { delegate.countFor("mp$it") < perThread }) Thread.sleep(10)
        (0 until threads).forEach {
            assertEquals((0 until perThread).toList(), delegate.seenFor("mp$it"))
        }
        assertEquals(0L, processor.droppedPacketCount)
    }

    @Test
    fun `exception in handling does not stop later packets on the stripe`() {
        val delegate = RecordingDelegate()
        val processor = processor(delegate)
        delegate.throwOnSeq = 0
        delegate.expect(3)

        repeat(3) { processor.processPacket(routed("peerE", "link-A", seq = it)) }

        assertTrue(delegate.await())
        assertEquals(listOf(0, 1, 2), delegate.seen.toList())
    }

    @Test
    fun `packets with no link key are not rate limited`() {
        val delegate = RecordingDelegate()
        val processor = processor(delegate)
        val n = AppConstants.Mesh.LINK_BURST * 2
        delegate.expect(n)

        repeat(n) { processor.processPacket(routed("local$it", null, seq = it)) }

        assertTrue(delegate.await())
        assertEquals(0L, processor.droppedPacketCount)
        assertEquals(0, processor.linkBucketCount)
    }

    @Test
    fun `processPacket after shutdown does not throw or count drops`() {
        val processor = processor(RecordingDelegate())
        processor.shutdown()

        repeat(10) { processor.processPacket(routed("peerS", "link-A", seq = it)) }

        assertEquals(0L, processor.droppedPacketCount)
    }

    @Test
    fun `backwards clock neither goes negative nor explodes`() {
        val processor = processor(RecordingDelegate())
        val initial = AppConstants.Mesh.LINK_INITIAL_TOKENS

        repeat(initial) { processor.processPacket(routed("a$it", "link-A", seq = it)) }
        assertEquals(0L, processor.droppedPacketCount)

        clock.set(0L) // clock jumps far backwards: no refill, no negative tokens
        processor.processPacket(routed("b", "link-A", seq = 0))
        assertEquals(1L, processor.droppedPacketCount)

        // 1s after the new baseline refills exactly one second of tokens, not the 1000s gap.
        clock.set(1_000_000_000L)
        val rate = AppConstants.Mesh.LINK_RATE_PER_SEC
        repeat(rate + 50) { processor.processPacket(routed("c$it", "link-A", seq = it)) }
        assertEquals(51L, processor.droppedPacketCount)
    }

    private fun processor(delegate: RecordingDelegate): PacketProcessor =
        PacketProcessor(MY_PEER_ID) { clock.get() }.also {
            it.delegate = delegate
            processors += it
        }

    private fun routed(
        peerID: String,
        relay: String?,
        seq: Int,
        ingress: String? = null
    ): RoutedPacket {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = ByteArray(8),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = seq.toULong(),
            payload = byteArrayOf(0x01),
            ttl = 7u
        )
        return RoutedPacket(packet, peerID, relay, ingressLinkID = ingress)
    }

    private class RecordingDelegate : PacketProcessorDelegate {
        val seen: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        private val byPeer = ConcurrentHashMap<String, MutableList<Int>>()
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var blockFirst = false
        @Volatile var throwOnSeq = -1
        @Volatile private var done = CountDownLatch(0)

        fun expect(count: Int) {
            done = CountDownLatch(count)
        }

        fun await(): Boolean = done.await(5, TimeUnit.SECONDS)
        fun countFor(peer: String): Int = byPeer[peer]?.size ?: 0
        fun seenFor(peer: String): List<Int> = synchronized(byPeer[peer]!!) { byPeer[peer]!!.toList() }

        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
            val seq = packet.timestamp.toInt()
            if (blockFirst && seq == 0) {
                firstEntered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            seen += seq
            byPeer.getOrPut(peerID) { Collections.synchronizedList(mutableListOf()) } += seq
            done.countDown()
            if (seq == throwOnSeq) throw IllegalStateException("boom")
            return false
        }

        override fun updatePeerLastSeen(peerID: String) = Unit
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override suspend fun handleNoiseHandshake(routed: RoutedPacket) = false
        override suspend fun handleNoiseEncrypted(routed: RoutedPacket) = false
        override suspend fun handleAnnounce(routed: RoutedPacket) = false
        override fun handleMessage(routed: RoutedPacket) = Unit
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) = Unit
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private companion object {
        const val MY_PEER_ID = "1111222233334444"
    }
}
