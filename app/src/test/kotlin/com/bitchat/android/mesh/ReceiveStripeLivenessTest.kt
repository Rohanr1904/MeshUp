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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A slow private-file side effect (save, voice hand-off, durable admission) must not hold the
 * sender's PacketProcessor stripe: later packets from the same sender are still decrypted in
 * order and promptly, while user-visible admission and delivery ACKs keep their order.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class ReceiveStripeLivenessTest {
    private val myPeerID = "1111222233334444"
    private val peerA = "aaaabbbbccccdddd"
    private val session = AuthenticatedNoiseSession(ByteArray(32) { 0x0B }, ByteArray(32) { 0x5D })

    private lateinit var handler: MessageHandler
    private lateinit var processor: PacketProcessor
    private val releaseFile = CountDownLatch(1)
    private val fileAdmissionStarted = CountDownLatch(1)
    private val textDecrypted = CountDownLatch(1)
    private val ackReceived = CountDownLatch(1)
    private val admitted = Collections.synchronizedList(mutableListOf<String>())
    private val events = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setup() {
        val file = BitchatFilePacket("note.pdf", 4, "application/pdf", byteArrayOf(1, 2, 3, 4))
        val plaintexts = mapOf<Byte, ByteArray>(
            1.toByte() to NoisePayload(NoisePayloadType.FILE_TRANSFER, file.encode()!!).encode(),
            2.toByte() to NoisePayload(
                NoisePayloadType.PRIVATE_MESSAGE,
                PrivateMessagePacket("TEXT-1", "hello").encode()!!
            ).encode(),
            3.toByte() to NoisePayload(NoisePayloadType.DELIVERED, "MINE-1".toByteArray()).encode()
        )
        val delegate: MessageHandlerDelegate = mock {
            on { getPeerNickname(any()) } doAnswer { "peerA" }
            on { encryptForPeer(any(), eq(peerA)) } doAnswer { byteArrayOf(0x55) }
        }
        whenever(delegate.decryptFromPeer(any(), eq(peerA))).thenAnswer { inv ->
            val tag = (inv.arguments[0] as ByteArray)[0]
            if (tag == 2.toByte()) textDecrypted.countDown()
            NoiseDecryptionResult(plaintexts.getValue(tag), session)
        }
        whenever(delegate.onMessageReceived(any())).thenAnswer { inv ->
            val message = inv.arguments[0] as BitchatMessage
            if (message.content == "hello") {
                admitted += "text"
            } else {
                fileAdmissionStarted.countDown()
                // Simulates a stuck disk/DB/voice hand-off for the voice note.
                releaseFile.await(10, TimeUnit.SECONDS)
                admitted += "file"
            }
            events += "admit"
            Unit
        }
        whenever(delegate.sendPacket(any())).thenAnswer { events += "ack"; Unit }
        whenever(delegate.onDeliveryAckReceived(any(), any())).thenAnswer { ackReceived.countDown(); Unit }

        handler = MessageHandler(myPeerID, RuntimeEnvironment.getApplication())
        handler.delegate = delegate
        processor = PacketProcessor(myPeerID)
        processor.delegate = StripeDelegate(handler)
    }

    @After
    fun tearDown() {
        releaseFile.countDown()
        processor.shutdown()
        handler.shutdown()
    }

    @Test
    fun `blocked file side effect does not hold the stripe`() = runBlocking {
        processor.processPacket(encrypted(1))
        assertTrue("file admission should start", fileAdmissionStarted.await(5, TimeUnit.SECONDS))

        val start = System.nanoTime()
        processor.processPacket(encrypted(2))
        processor.processPacket(encrypted(3))

        // Both later packets from the same sender are handled on the stripe while the file waits.
        assertTrue(textDecrypted.await(3, TimeUnit.SECONDS))
        assertTrue(ackReceived.await(3, TimeUnit.SECONDS))
        assertTrue((System.nanoTime() - start) / 1_000_000L < 3_000L)

        // Ordering: text is admitted after the file, and no ACK is sent before admission.
        assertEquals(emptyList<String>(), admitted.toList())
        assertEquals(emptyList<String>(), events.toList())

        releaseFile.countDown()
        handler.awaitReceiveSideEffects()

        assertEquals(listOf("file", "text"), admitted.toList())
        // Exactly one delivery ACK per message, each after its own admission.
        assertEquals(listOf("admit", "ack", "admit", "ack"), events.toList())
    }

    private fun encrypted(tag: Int) = RoutedPacket(
        BitchatPacket(
            version = 1u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = peerA.hexToBytes(),
            recipientID = myPeerID.hexToBytes(),
            timestamp = System.currentTimeMillis().toULong(),
            payload = byteArrayOf(tag.toByte()),
            ttl = 7u
        ),
        peerA
    )

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

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
