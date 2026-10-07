package com.bitchat.android.mesh

import com.bitchat.android.protocol.BitchatPacket

/**
 * Fail-closed signing helper shared by the BLE and Wi-Fi Aware services.
 *
 * Returns the routed packet with its signature attached, or null on ANY failure
 * (route application throws, encoding returns null, signing returns null, or any
 * exception). It never returns an unsigned packet; callers must skip the send.
 */
internal fun signOrNull(
    packet: BitchatPacket,
    applyRoute: (BitchatPacket) -> BitchatPacket,
    encode: (BitchatPacket) -> ByteArray?,
    sign: (ByteArray) -> ByteArray?
): BitchatPacket? {
    return try {
        val routed = applyRoute(packet)
        val bytes = encode(routed) ?: return null
        val signature = sign(bytes) ?: return null
        routed.copy(signature = signature)
    } catch (_: Exception) {
        null
    }
}
