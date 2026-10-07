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
 * KNOWN DEFECT (docs/SECURITY_REVIEW.md R3, TARGET_ARCHITECTURE R-5.3): ingress TTL is not
 * clamped to [AppConstants.MESSAGE_TTL_HOPS]. A packet arriving with TTL 255 is relayed with
 * TTL 254, and `ttl >= 4` always relays, so one forged packet can travel ~255 hops.
 *
 * Tests named `knownDefect_*` pin the CURRENT behaviour. The fix must invert them: the
 * relayed TTL must never exceed MESSAGE_TTL_HOPS - 1.
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

    /**
     * KNOWN DEFECT R-5.3. Correct expectation: relayed TTL <= MESSAGE_TTL_HOPS - 1 (6).
     * Current behaviour: TTL 255 is relayed as 254.
     */
    @Test
    fun knownDefect_R5_3_oversizedIngressTtlIsRelayedUnclamped() = runTest {
        packetRelayManager.handlePacketRelay(RoutedPacket(broadcastPacket(ttl = 255u), otherPeerID))

        assertEquals(
            "KNOWN DEFECT R-5.3: TTL not clamped; the fix must cap it at MESSAGE_TTL_HOPS - 1",
            254u.toUByte(),
            relayedTtl()
        )
    }

    /**
     * KNOWN DEFECT R-5.3. A TTL above the protocol default always takes the `ttl >= 4`
     * branch, so it is relayed regardless of network size (no probabilistic damping).
     */
    @Test
    fun knownDefect_R5_3_oversizedTtlBypassesProbabilisticRelayInLargeNetworks() = runTest {
        whenever(delegate.getNetworkSize()).thenReturn(500)

        repeat(20) {
            packetRelayManager.handlePacketRelay(RoutedPacket(broadcastPacket(ttl = 200u), otherPeerID))
        }

        argumentCaptor<RoutedPacket> {
            verify(delegate, org.mockito.kotlin.times(20)).broadcastPacket(capture())
            allValues.forEach { assertEquals(199u.toUByte(), it.packet.ttl) }
        }
    }

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
