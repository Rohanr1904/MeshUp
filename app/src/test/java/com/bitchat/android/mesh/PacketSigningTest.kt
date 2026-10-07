package com.bitchat.android.mesh

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.FragmentPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PacketSigningTest {
    private val sender = ByteArray(8) { (it + 1).toByte() }
    private val recipient = ByteArray(8) { (it + 9).toByte() }

    private fun packet(payload: ByteArray = ByteArray(4)) = BitchatPacket(
        type = MessageType.MESSAGE.value,
        senderID = sender,
        recipientID = recipient,
        timestamp = 1000UL,
        payload = payload,
        ttl = 7u
    )

    private val sig = ByteArray(64) { 0x5A }

    @Test
    fun `encode failure yields null and never signs`() {
        var signed = false
        val result = signOrNull(packet(), { it }, { null }, { signed = true; sig })
        assertNull(result)
        assertTrue(!signed)
    }

    @Test
    fun `null signature yields null`() {
        assertNull(signOrNull(packet(), { it }, { byteArrayOf(1) }, { null }))
    }

    @Test
    fun `sign exception yields null`() {
        assertNull(signOrNull(packet(), { it }, { byteArrayOf(1) }, { throw IllegalStateException("boom") }))
    }

    @Test
    fun `encode exception yields null`() {
        assertNull(signOrNull(packet(), { it }, { throw IllegalStateException("boom") }, { sig }))
    }

    @Test
    fun `route exception yields null`() {
        assertNull(signOrNull(packet(), { throw IllegalStateException("boom") }, { byteArrayOf(1) }, { sig }))
    }

    @Test
    fun `success attaches signature and keeps route`() {
        val hop = ByteArray(8) { 0x77 }
        var encoded: BitchatPacket? = null
        val result = signOrNull(
            packet(),
            applyRoute = { it.copy(route = listOf(hop), version = 2u) },
            encode = { encoded = it; byteArrayOf(9) },
            sign = { sig }
        )
        assertNotNull(result)
        assertArrayEquals(sig, result!!.signature)
        assertEquals(1, result.route?.size)
        assertArrayEquals(hop, result.route!![0])
        assertEquals(2u.toUByte(), result.version)
        // The signing bytes were computed over the routed packet.
        assertNotNull(encoded?.route)
    }

    @Test
    fun `large signed packet still fragments and fragments carry no signature`() {
        val signed = signOrNull(
            packet(ByteArray(2000) { it.toByte() }),
            { it },
            { it.toBinaryDataForSigning() },
            { sig }
        )!!
        assertNotNull(signed.signature)
        val fragments = FragmentManager().createFragments(signed)
        assertTrue(fragments.size > 1)
        for (f in fragments) {
            assertEquals(MessageType.FRAGMENT.value, f.type)
            assertNull(f.signature)
            assertNotNull(FragmentPayload.decode(f.payload))
        }
    }
}
