package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.Noise
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Characterization tests for the transport-message replay window in [NoiseSession].
 *
 * Defect R1 (docs/SECURITY_REVIEW.md, upstream H1) was that `markNonceAsSeen` shifted the
 * bitmap in the wrong direction when a newer nonce arrived, so a captured ciphertext of the
 * previous message was decrypted again. Fixed by P2-PR1 (TARGET_ARCHITECTURE R-5.1); the
 * `R1_*` tests assert that every replay is rejected.
 */
class NoiseSessionReplayWindowCharacterizationTest {
    private data class TestIdentity(
        val privateKey: ByteArray,
        val publicKey: ByteArray,
        val peerID: String
    )

    private val managers = mutableListOf<NoiseSessionManager>()
    private lateinit var sender: NoiseSession
    private lateinit var receiver: NoiseSession

    @Before
    fun setUp() {
        val alice = identity()
        val bob = identity()
        val aliceManager = manager(alice)
        val bobManager = manager(bob)

        val message1 = aliceManager.initiateHandshake(bob.peerID)!!
        val message2 = bobManager.processHandshakeMessage(alice.peerID, message1)!!
        val message3 = aliceManager.processHandshakeMessage(bob.peerID, message2)!!
        assertNull(bobManager.processHandshakeMessage(alice.peerID, message3))

        sender = aliceManager.getSession(bob.peerID)!!
        receiver = bobManager.getSession(alice.peerID)!!
        assertTrue(sender.isEstablished() && receiver.isEstablished())
    }

    @After
    fun tearDown() {
        managers.forEach(NoiseSessionManager::shutdown)
    }

    @Test
    fun `in-order messages decrypt`() {
        val ciphertexts = (0 until 10).map { sender.encrypt(payload(it)) }
        ciphertexts.forEachIndexed { i, ct -> assertArrayEquals(payload(i), receiver.decrypt(ct)) }
    }

    @Test
    fun `immediate duplicate of the newest message is rejected`() {
        val ct0 = sender.encrypt(payload(0))
        receiver.decrypt(ct0)
        assertReplayRejected(ct0)
    }

    @Test
    fun `out-of-order delivery within the window is accepted once`() {
        val ct0 = sender.encrypt(payload(0))
        val ct1 = sender.encrypt(payload(1))
        assertArrayEquals(payload(1), receiver.decrypt(ct1))
        assertArrayEquals(payload(0), receiver.decrypt(ct0))
    }

    @Test
    fun `nonce older than the window is rejected`() {
        val ct0 = sender.encrypt(payload(0))
        receiver.decrypt(ct0)
        repeat(1024) { receiver.decrypt(sender.encrypt(payload(it + 1))) }
        assertReplayRejected(ct0)
    }

    @Test
    fun R1_replayOfPreviousNonceAfterAdvanceIsRejected() {
        val ct0 = sender.encrypt(payload(0))
        val ct1 = sender.encrypt(payload(1))
        receiver.decrypt(ct0)
        receiver.decrypt(ct1)

        assertReplayRejected(ct0)
    }

    @Test
    fun R1_outOfOrderNonceCannotBeReplayedAfterNextAdvance() {
        val ct0 = sender.encrypt(payload(0))
        val ct1 = sender.encrypt(payload(1))
        val ct2 = sender.encrypt(payload(2))
        receiver.decrypt(ct1)
        receiver.decrypt(ct0)
        receiver.decrypt(ct2)

        assertReplayRejected(ct0)
    }

    private fun assertReplayRejected(ciphertext: ByteArray) {
        try {
            receiver.decrypt(ciphertext)
            fail("Replay must be rejected")
        } catch (_: SessionError) {
        }
    }

    private fun payload(i: Int): ByteArray = "synthetic-message-$i".toByteArray()

    private fun manager(identity: TestIdentity): NoiseSessionManager = NoiseSessionManager(
        localStaticPrivateKey = identity.privateKey,
        localStaticPublicKey = identity.publicKey,
        localPeerID = identity.peerID
    ).also { managers += it }

    private fun identity(): TestIdentity {
        val dh = Noise.createDH("25519")
        return try {
            dh.generateKeyPair()
            val privateKey = ByteArray(32)
            val publicKey = ByteArray(32)
            dh.getPrivateKey(privateKey, 0)
            dh.getPublicKey(publicKey, 0)
            TestIdentity(privateKey, publicKey, NoisePeerIdentity.derivePeerID(publicKey)!!)
        } finally {
            dh.destroy()
        }
    }
}
