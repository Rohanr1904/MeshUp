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
 * KNOWN DEFECT R1 (docs/SECURITY_REVIEW.md, upstream H1): `markNonceAsSeen` shifts the
 * bitmap in the wrong direction when a newer nonce arrives. After advancing by one, the
 * bit of the previous nonce lands at offset 15 instead of offset 1, so a captured
 * ciphertext of the previous message is decrypted again.
 *
 * Tests named `knownDefect_*` pin the CURRENT (insecure) behaviour so the suite stays
 * green. The fix (TARGET_ARCHITECTURE R-5.1) must invert them: every replay must throw.
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

    /**
     * KNOWN DEFECT R1. Correct expectation: replaying message 0 after message 1 throws.
     * Current behaviour: it decrypts a second time.
     */
    @Test
    fun knownDefect_R1_replayOfPreviousMessageIsAcceptedAfterWindowAdvances() {
        val ct0 = sender.encrypt(payload(0))
        val ct1 = sender.encrypt(payload(1))
        receiver.decrypt(ct0)
        receiver.decrypt(ct1)

        val replayed = receiver.decrypt(ct0)

        assertArrayEquals("KNOWN DEFECT R1: replay accepted; the fix must make this throw", payload(0), replayed)
    }

    /**
     * KNOWN DEFECT R1. Correct expectation: a message accepted out of order is never
     * accepted again. Current behaviour: once the window advances, its bit is lost.
     */
    @Test
    fun knownDefect_R1_outOfOrderMessageCanBeReplayedAfterNextAdvance() {
        val ct0 = sender.encrypt(payload(0))
        val ct1 = sender.encrypt(payload(1))
        val ct2 = sender.encrypt(payload(2))
        receiver.decrypt(ct1)
        receiver.decrypt(ct0)
        receiver.decrypt(ct2)

        val replayed = receiver.decrypt(ct0)

        assertArrayEquals("KNOWN DEFECT R1: replay accepted; the fix must make this throw", payload(0), replayed)
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
