package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatFilePacket

/**
 * Transport-agnostic mesh service API for UI and routing layers.
 */
interface MeshService {
    val myPeerID: String
    var delegate: MeshDelegate?

    fun startServices()
    fun stopServices()

    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null)
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null)

    /**
     * MeshUp P2-PR9 (R-1 session race): like [sendPrivateMessage], but reports the transport
     * outcome so the delivery engine only treats a message as transmitted when it really was.
     * Same packet and wire format as [sendPrivateMessage].
     *
     * [onResult] is invoked exactly once, possibly on another thread and possibly before this call
     * returns, never while holding a transport or Noise lock:
     * - `false`: nothing was sent because no Noise session with [recipientPeerID] is established.
     *   No handshake is started by this call; the caller decides (it owns the retry backoff).
     * - `true`: the message was handed to the transport (or it failed terminally there, e.g. a
     *   signing error, which the transport reports through the delivery status itself).
     *
     * The default keeps the legacy behaviour behind a lock-free session pre-check. It is
     * check-then-send: a session that disappears between the check and the (asynchronous) legacy
     * send is still reported as `true` and silently dropped, which is exactly the R-1 race. Only
     * test doubles rely on it; every production transport (UnifiedMeshService, WifiAwareMeshService
     * via MeshCore, BluetoothMeshService) overrides or implements the race-free variant.
     */
    fun sendPrivateMessageReporting(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String,
        onResult: (Boolean) -> Unit
    ) {
        val report: (Boolean) -> Unit = { sent -> try { onResult(sent) } catch (_: Exception) { } }
        if (!hasEstablishedSession(recipientPeerID)) {
            report(false)
            return
        }
        sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
        report(true)
    }

    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String)
    fun sendDeliveryAck(messageID: String, recipientPeerID: String) {}
    fun sendFavoriteNotification(peerID: String, isFavorite: Boolean) {}
    fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray)
    fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray)
    fun sendFileBroadcast(file: BitchatFilePacket)
    fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket)
    fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray)
    fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): PrivateMediaPreparation
    fun cancelFileTransfer(transferId: String): Boolean

    fun sendBroadcastAnnounce()
    fun sendAnnouncementToPeer(peerID: String)

    fun getPeerNicknames(): Map<String, String>
    fun getPeerRSSI(): Map<String, Int>
    fun getActivePeerCount(): Int
    fun hasEstablishedSession(peerID: String): Boolean
    fun getSessionState(peerID: String): com.bitchat.android.noise.NoiseSession.NoiseSessionState
    fun initiateNoiseHandshake(peerID: String)
    fun getPeerFingerprint(peerID: String): String?
    fun getPeerInfo(peerID: String): PeerInfo?
    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean
    fun getIdentityFingerprint(): String
    fun getStaticNoisePublicKey(): ByteArray?
    fun shouldShowEncryptionIcon(peerID: String): Boolean
    fun getEncryptedPeers(): List<String>

    fun getDeviceAddressForPeer(peerID: String): String?
    fun getDeviceAddressToPeerMapping(): Map<String, String>
    fun printDeviceAddressesForPeers(): String
    fun getDebugStatus(): String

    fun clearAllInternalData()
    fun clearAllEncryptionData()
}
