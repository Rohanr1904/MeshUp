package com.bitchat.android.mesh

import android.os.Build
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.util.AppConstants
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Safety of the per-sender receive lanes: a hung admission cannot stall the shared stripe, a
 * panic wipe stops queued file writes and ACKs, and a failed admission is never acknowledged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class ReceiveLaneSafetyTest {
    private val myPeerID = "1111222233334444"
    private val peerA = "aaaabbbbccccdddd"
    private val peerB = sameStripePeer(peerA)
    private val session = AuthenticatedNoiseSession(ByteArray(32) { 0x0B }, ByteArray(32) { 0x5D })

    private lateinit var handler: MessageHandler
    private lateinit var processor: PacketProcessor
    private lateinit var delegate: MessageHandlerDelegate
    private val release = CountDownLatch(1)
    private val hungStarted = CountDownLatch(1)
    private val ackedTo = Collections.synchronizedList(mutableListOf<String>())
    private val fileName = "lane-safety-${System.nanoTime()}.pdf"

    /** Decides the admission outcome per message; may block to simulate a hung DB. */
    @Volatile private var admission: (BitchatMessage) -> Boolean = { true }

    @Before
    fun setup() {
        AppStateStore.resumePrivateConversationsAfterPanic()
        delegate = mock {
            on { getPeerNickname(any()) } doAnswer { "peer" }
            on { encryptForPeer(any(), any()) } doAnswer { byteArrayOf(0x55) }
        }
        whenever(delegate.decryptFromPeer(any(), any())).thenAnswer { inv ->
            NoiseDecryptionResult(inv.arguments[0] as ByteArray, session)
        }
        whenever(delegate.onMessageReceived(any())).thenAnswer { inv ->
            admission(inv.arguments[0] as BitchatMessage)
        }
        whenever(delegate.sendPacket(any())).thenAnswer { inv ->
            ackedTo += (inv.arguments[0] as BitchatPacket).recipientID!!.toHexString()
            Unit
        }
        handler = MessageHandler(myPeerID, RuntimeEnvironment.getApplication())
        handler.delegate = delegate
        processor = PacketProcessor(myPeerID)
        processor.delegate = StripeDelegate(handler)
    }

    @After
    fun tearDown() {
        release.countDown()
        processor.shutdown()
        handler.shutdown()
        AppStateStore.resumePrivateConversationsAfterPanic()
        incomingFiles().forEach { it.delete() }
    }

    @Test
    fun `hung lane does not stall stripe`() = runBlocking {
        val bAdmitted = CountDownLatch(1)
        admission = { message ->
            if (message.senderPeerID == peerA) {
                hungStarted.countDown()
                release.await(20, TimeUnit.SECONDS)
            } else {
                bAdmitted.countDown()
            }
            true
        }
        processor.processPacket(packet(peerA, text("A-0")))
        assertTrue(hungStarted.await(5, TimeUnit.SECONDS))
        repeat(19) { i -> processor.processPacket(packet(peerA, text("A-${i + 1}"))) }

        val start = System.nanoTime()
        processor.processPacket(packet(peerB, text("B-0")))
        assertTrue("same-stripe peer must be handled", bAdmitted.await(2, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - start) / 1_000_000L < 2_000L)

        release.countDown()
        withTimeout(10_000) { handler.awaitReceiveSideEffects() }
        // 16 lane slots for A were acked after admission; the 4 dropped ones got no ACK.
        assertEquals(16, ackedTo.count { it == peerA })
        assertEquals(1, ackedTo.count { it == peerB })
    }

    @Test
    fun `panic stops queued file and ack`() = runBlocking {
        admission = { hungStarted.countDown(); release.await(20, TimeUnit.SECONDS); true }
        assertTrue(handler.handleNoiseEncrypted(packet(peerA, text("A-0"))))
        assertTrue(hungStarted.await(5, TimeUnit.SECONDS))
        assertTrue(handler.handleNoiseEncrypted(packet(peerA, file())))

        handler.cancelPendingReceiveSideEffects()
        release.countDown()
        withTimeout(10_000) { handler.awaitReceiveSideEffects() }
        Thread.sleep(200)

        assertTrue("no ACK after panic", ackedTo.isEmpty())
        assertTrue("no file after panic", incomingFiles().isEmpty())
    }

    @Test
    fun `store wipe skips stale work`() = runBlocking {
        admission = { hungStarted.countDown(); release.await(20, TimeUnit.SECONDS); true }
        assertTrue(handler.handleNoiseEncrypted(packet(peerA, text("A-0"))))
        assertTrue(hungStarted.await(5, TimeUnit.SECONDS))
        assertTrue(handler.handleNoiseEncrypted(packet(peerA, file())))

        // Panic began elsewhere (store generation bumped) before the queued work ran.
        assertTrue(AppStateStore.panicClearPrivateConversations())
        AppStateStore.resumePrivateConversationsAfterPanic()
        release.countDown()
        withTimeout(10_000) { handler.awaitReceiveSideEffects() }

        assertTrue(ackedTo.isEmpty())
        assertTrue(incomingFiles().isEmpty())
    }

    @Test
    fun `failed admission sends no ack`() = runBlocking {
        admission = { message -> message.id != "REJECTED-1" }
        handler.handleNoiseEncrypted(packet(peerA, text("REJECTED-1")))
        handler.handleNoiseEncrypted(packet(peerA, text("HELD-1")))
        withTimeout(10_000) { handler.awaitReceiveSideEffects() }

        assertEquals(listOf(peerA), ackedTo.toList())
    }

    @Test
    fun `rejected file leaves no file`() = runBlocking {
        admission = { false }
        handler.handleNoiseEncrypted(packet(peerA, file()))
        withTimeout(10_000) { handler.awaitReceiveSideEffects() }

        assertTrue("no ACK for a rejected file", ackedTo.isEmpty())
        assertTrue("rejected file is deleted", incomingFiles().isEmpty())
    }

    private fun incomingFiles(): List<File> =
        File(RuntimeEnvironment.getApplication().cacheDir, "files/incoming")
            .listFiles()?.filter { it.name.startsWith(fileName.substringBefore('.')) }.orEmpty()

    private fun text(id: String) = NoisePayload(
        NoisePayloadType.PRIVATE_MESSAGE,
        PrivateMessagePacket(id, "hello").encode()!!
    ).encode()

    private fun file() = NoisePayload(
        NoisePayloadType.FILE_TRANSFER,
        BitchatFilePacket(fileName, 4, "application/pdf", byteArrayOf(1, 2, 3, 4)).encode()!!
    ).encode()

    private fun packet(from: String, plaintext: ByteArray) = RoutedPacket(
        BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = from.hexToBytes(),
            recipientID = myPeerID.hexToBytes(),
            timestamp = System.currentTimeMillis().toULong(),
            payload = plaintext,
            ttl = 7u
        ),
        from
    )

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun stripeOf(id: String) = (id.hashCode() and Int.MAX_VALUE) % AppConstants.Mesh.STRIPES

    private fun sameStripePeer(of: String): String = generateSequence(1L) { it + 1 }
        .map { "%016x".format(it) }
        .first { it != of && stripeOf(it) == stripeOf(of) }

    private class StripeDelegate(private val handler: MessageHandler) : PacketProcessorDelegate {
        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = true
        override fun updatePeerLastSeen(peerID: String) = Unit
        override fun getPeerNickname(peerID: String): String? = null
        override fun getNetworkSize() = 2
        override fun getBroadcastRecipient() = SpecialRecipients.BROADCAST
        override suspend fun handleNoiseHandshake(routed: RoutedPacket) = false
        override suspend fun handleNoiseEncrypted(routed: RoutedPacket) = handler.handleNoiseEncrypted(routed)
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
}
