package com.bitchat.android.meshup.service

import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.flow.MutableStateFlow

class FakeLegacyChatSource : LegacyChatSource {
    override val nickname = MutableStateFlow("me")
    override val privateChats = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    override val peerNicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    override val unreadPrivateMessages = MutableStateFlow<Set<String>>(emptySet())
    override val connectedPeers = MutableStateFlow<List<String>>(emptyList())
    override val peerDirect = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val favoritePeers = MutableStateFlow<Set<String>>(emptySet())
    override val joinedChannels = MutableStateFlow<Set<String>>(emptySet())
    override val unreadChannelMessages = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val passwordProtectedChannels = MutableStateFlow<Set<String>>(emptySet())

    val favoriteFingerprints = mutableSetOf<String>()
    val toggled = mutableListOf<String>()
    val joined = mutableListOf<String>()
    val left = mutableListOf<String>()
    val started = mutableListOf<String>()
    var joinResult = true
    val nicknamesSet = mutableListOf<String>()
    var fingerprint = "0123456789abcdef0123456789abcdef"

    override fun isFavorite(peerId: String) = peerId in favoriteFingerprints
    override fun toggleFavorite(peerId: String) { toggled += peerId }
    override fun joinChannel(name: String): Boolean { joined += name; return joinResult }
    override fun leaveChannel(name: String) { left += name }
    override suspend fun startPrivateChat(peerId: String) { started += peerId }
    override fun setNickname(name: String) { nicknamesSet += name; nickname.value = name }
    override fun myFingerprint() = fingerprint
}
