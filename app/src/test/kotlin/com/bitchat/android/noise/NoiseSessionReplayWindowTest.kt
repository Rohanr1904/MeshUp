package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.Noise
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * Behavioural tests for the transport replay window (R-5.1), driven through the public
 * encrypt/decrypt path. The sender pre-generates ciphertexts so that any nonce can be
 * delivered to the receiver in any order.
 */
class NoiseSessionReplayWindowTest {
    private data class TestIdentity(val privateKey: ByteArray, val publicKey: ByteArray, val peerID: String)

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

    // 1. advances of various sizes
    @Test
    fun advanceBy1() = advanceCheck(1)

    @Test
    fun advanceBy7() = advanceCheck(7)

    @Test
    fun advanceBy8() = advanceCheck(8)

    @Test
    fun advanceBy9() = advanceCheck(9)

    @Test
    fun advanceBy1023() = advanceCheck(1023)

    @Test
    fun advanceBy1024() = advanceCheck(1024)

    private fun advanceCheck(shift: Int) {
        val base = 5
        val newHighest = base + shift
        val cts = encryptUpTo(newHighest)
        val seen = mutableSetOf(0, 2, base)
        seen.sorted().forEach { assertAccepted(cts, it) }
        assertAccepted(cts, newHighest)
        seen += newHighest

        fun expectAccepted(n: Int) = n !in seen && n + WINDOW > newHighest

        // every seen or too-old nonce is rejected
        for (n in 0..newHighest) if (!expectAccepted(n)) assertRejected(cts, n)
        // every unseen in-window nonce is accepted exactly once
        for (n in 0..newHighest) if (expectAccepted(n)) assertAccepted(cts, n)
        for (n in 0..newHighest) assertRejected(cts, n)
    }

    // 2. boundary
    @Test
    fun windowBoundary() {
        val highest = 1500
        val cts = encryptUpTo(highest)
        assertAccepted(cts, highest)
        assertRejected(cts, highest - 1024)
        assertAccepted(cts, highest - 1023)
        assertRejected(cts, highest - 1023)
        assertRejected(cts, highest - 1024)
    }

    // 3. duplicate of current highest
    @Test
    fun duplicateOfHighestIsRejected() {
        val cts = encryptUpTo(1200)
        assertAccepted(cts, 1200)
        assertRejected(cts, 1200)
        assertAccepted(cts, 1199)
        assertRejected(cts, 1200)
    }

    // 4. forged far-future nonce fails AEAD and must not advance the window
    @Test
    fun forgedFutureNonceDoesNotMoveWindow() {
        val cts = encryptUpTo(10)
        assertAccepted(cts, 3)
        val forged = cts[5].copyOf()
        val forgedNonce = 5000
        for (i in 0 until 4) forged[i] = (forgedNonce ushr (8 * (3 - i))).toByte()
        try {
            receiver.decrypt(forged)
            fail("Forged payload should be rejected")
        } catch (_: SessionError) {
        }
        // window did not jump to 5000: older nonces are still accepted once
        assertAccepted(cts, 1)
        assertAccepted(cts, 2)
        assertRejected(cts, 3)
        assertAccepted(cts, 4)
    }

    // 5. randomized comparison with a reference model
    @Test
    fun randomizedAgainstReferenceModel() {
        val maxNonce = 40000
        val cts = encryptUpTo(maxNonce)
        val rnd = Random(0xC0FFEEL)
        val seen = HashSet<Int>()
        var highest = 0
        var accepted = 0
        var rejected = 0
        var advances = 0
        val shiftsMod8 = HashSet<Int>()
        repeat(6000) {
            // Half the steps advance the frontier by 1..16 (exercises every shift % 8); the rest
            // replay or reorder within [highest-1100, highest], covering in-window and too-old nonces.
            val n = if (rnd.nextBoolean()) {
                highest + 1 + rnd.nextInt(16)
            } else {
                highest - rnd.nextInt(1101)
            }.coerceIn(0, maxNonce)
            val expected = n > highest || (n + WINDOW > highest && n !in seen)
            if (expected) {
                assertAccepted(cts, n)
                seen += n
                if (n > highest) {
                    shiftsMod8 += (n - highest) % 8
                    highest = n
                    advances++
                }
                accepted++
            } else {
                assertRejected(cts, n)
                rejected++
            }
        }
        assertTrue("stream should exercise both outcomes", accepted > 100 && rejected > 100)
        assertTrue("stream should advance the window often (was $advances)", advances >= 1000)
        assertEquals("every shift % 8 should be exercised", 8, shiftsMod8.size)
    }

    // 6. golden wire nonce prefix: 4-byte big-endian
    @Test
    fun wireNoncePrefixIsFourByteBigEndian() {
        val cts = encryptUpTo(300)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), cts[0].copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), cts[1].copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 0, 0xFF.toByte()), cts[255].copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 1, 0), cts[256].copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(0, 0, 1, 44), cts[300].copyOfRange(0, 4))
        assertEquals(4 + payload(0).size + 16, cts[0].size)
    }

    private fun encryptUpTo(maxNonce: Int): List<ByteArray> =
        (0..maxNonce).map { sender.encrypt(payload(it)) }

    private fun assertAccepted(cts: List<ByteArray>, n: Int) {
        val plain = try {
            receiver.decrypt(cts[n])
        } catch (e: SessionError) {
            fail("Nonce $n should be accepted")
            return
        }
        assertArrayEquals("payload for nonce $n", payload(n), plain)
    }

    private fun assertRejected(cts: List<ByteArray>, n: Int) {
        try {
            receiver.decrypt(cts[n])
            fail("Nonce $n should be rejected")
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

    private companion object {
        const val WINDOW = 1024
    }
}
