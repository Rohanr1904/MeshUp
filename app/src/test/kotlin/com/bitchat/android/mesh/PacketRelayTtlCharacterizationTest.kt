package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Characterization tests for TTL handling on relay in [PacketRelayManager].
 *
 * R-5.3 (docs/SECURITY_REVIEW.md R3, TARGET_ARCHITECTURE R-5.3) is FIXED: an oversized TTL is
 * clamped to [AppConstants.MESSAGE_TTL_HOPS] before the decrement, so the relayed TTL never
 * exceeds MESSAGE_TTL_HOPS - 1 (previously TTL 255 was relayed as 254). The `ttl >= 4`
 * always-relay policy is unchanged (TD-06).
 */
@ExperimentalCoroutinesApi
class PacketRelayTtlCharacterizationTest {

    private lateinit var packetRelayManager: PacketRelayManager
    private val delegate: PacketRelayManagerDelegate = mock()

    private val myPeerID = "1111111111111111"
    private val otherPeerID = "2222222222222222"

    @Before
    fun setUp() {
        packetRelayManager = PacketRelayManager(myPeerID)
        packetRelayManager.delegate = delegate
        whenever(delegate.getNetworkSize()).thenReturn(10)
        whenever(delegate.getBroadcastRecipient()).thenReturn(ByteArray(8))
    }

    @Test
    fun `packet with TTL 0 is not relayed`() = runTest {
        packetRelayManager.handlePacketRelay(RoutedPacket(broadcastPacket(ttl = 0u), otherPeerID))

        verify(delegate, never()).broadcastPacket(any())
    }

    @Test
    fun `packet with default TTL is relayed with TTL decremented by one`() = runTest {
        packetRelayManager.handlePacketRelay(
            RoutedPacket(broadcastPacket(ttl = AppConstants.MESSAGE_TTL_HOPS), otherPeerID)
        )

        assertEquals((AppConstants.MESSAGE_TTL_HOPS - 1u).toUByte(), relayedTtl())
    }

    /** R-5.3 (fixed): an oversized TTL is clamped to MESSAGE_TTL_HOPS before the decrement. */
    @Test
    fun R5_3_oversizedIngressTtlIsClampedBeforeRelay() = runTest {
        packetRelayManager.handlePacketRelay(RoutedPacket(broadcastPacket(ttl = 255u), otherPeerID))

        assertEquals((AppConstants.MESSAGE_TTL_HOPS - 1u).toUByte(), relayedTtl())
    }

    /**
     * R-5.3 (fixed) clamps the TTL, but a clamped TTL of 6 still takes the `ttl >= 4` branch, so it
     * is relayed regardless of network size. That is the existing relay policy (TD-06), not R-5.3.
     */
    @Test
    fun R5_3_oversizedTtlIsClampedButHighTtlStillAlwaysRelays_TD06() = runTest {
        whenever(delegate.getNetworkSize()).thenReturn(500)

        repeat(20) {
            packetRelayManager.handlePacketRelay(RoutedPacket(broadcastPacket(ttl = 200u), otherPeerID))
        }

        argumentCaptor<RoutedPacket> {
            verify(delegate, org.mockito.kotlin.times(20)).broadcastPacket(capture())
            allValues.forEach { assertEquals((AppConstants.MESSAGE_TTL_HOPS - 1u).toUByte(), it.packet.ttl) }
        }
    }

    @Test
    fun `TTL just above the maximum is clamped`() = runTest {
        packetRelayManager.handlePacketRelay(
            RoutedPacket(broadcastPacket(ttl = (AppConstants.MESSAGE_TTL_HOPS + 1u).toUByte()), otherPeerID)
        )

        assertEquals((AppConstants.MESSAGE_TTL_HOPS - 1u).toUByte(), relayedTtl())
    }

    @Test
    fun `oversized voice frame in a small network is clamped`() = runTest {
        // networkSize <= 6 skips the voice cap, so only the R-5.3 clamp limits the TTL.
        whenever(delegate.getNetworkSize()).thenReturn(5)
        val voice = broadcastPacket(ttl = 255u).copy(type = MessageType.VOICE_FRAME.value)
        packetRelayManager.handlePacketRelay(RoutedPacket(voice, otherPeerID))

        assertEquals((AppConstants.MESSAGE_TTL_HOPS - 1u).toUByte(), relayedTtl())
    }

    @Test
    fun `oversized voice frame is clamped then voice-capped in larger networks`() = runTest {
        // networkSize is 10 (> 6), so the existing voice cap of 5 applies after the clamp.
        val voice = broadcastPacket(ttl = 255u).copy(type = MessageType.VOICE_FRAME.value)
        packetRelayManager.handlePacketRelay(RoutedPacket(voice, otherPeerID))

        assertEquals(5u.toUByte(), relayedTtl())
    }

    @Test
    fun `oversized source-routed packet is clamped on the targeted next hop`() = runTest {
        val nextHop = "3333333333333333"
        whenever(delegate.sendToPeer(any(), any())).thenReturn(true)
        val routed = broadcastPacket(ttl = 255u).copy(route = listOf(peerBytes(myPeerID), peerBytes(nextHop)))

        packetRelayManager.handlePacketRelay(RoutedPacket(routed, otherPeerID))

        val sent = argumentCaptor<RoutedPacket> {
            verify(delegate).sendToPeer(org.mockito.kotlin.eq(nextHop), capture())
        }.firstValue
        assertEquals((AppConstants.MESSAGE_TTL_HOPS - 1u).toUByte(), sent.packet.ttl)
        verify(delegate, never()).broadcastPacket(any())
    }

    private fun peerBytes(hex: String) = ByteArray(8) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun relayedTtl(): UByte = argumentCaptor<RoutedPacket> {
        verify(delegate).broadcastPacket(capture())
    }.firstValue.packet.ttl

    private fun broadcastPacket(ttl: UByte) = BitchatPacket(
        type = MessageType.MESSAGE.value,
        senderID = ByteArray(8) { 0x22 },
        recipientID = null,
        timestamp = System.currentTimeMillis().toULong(),
        payload = "synthetic".toByteArray(),
        ttl = ttl
    )
}
