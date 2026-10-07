package com.bitchat.android.meshup.service

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.flow.StateFlow

/** Narrow view of the legacy ChatViewModel so adapters are testable without constructing it. */
interface LegacyChatSource {
    val nickname: StateFlow<String>
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>>
    val peerNicknames: StateFlow<Map<String, String>>
    val unreadPrivateMessages: StateFlow<Set<String>>
    val connectedPeers: StateFlow<List<String>>
    val peerDirect: StateFlow<Map<String, Boolean>>
    val favoritePeers: StateFlow<Set<String>>
    val joinedChannels: StateFlow<Set<String>>
    val unreadChannelMessages: StateFlow<Map<String, Int>>
    val passwordProtectedChannels: StateFlow<Set<String>>

    fun isFavorite(peerId: String): Boolean
    fun toggleFavorite(peerId: String)
    fun joinChannel(name: String): Boolean
    fun leaveChannel(name: String)
    suspend fun startPrivateChat(peerId: String)
}

/** Delegates to the single existing [ChatViewModel] instance. Never passes a channel password. */
class ChatViewModelSource(private val vm: ChatViewModel) : LegacyChatSource {
    override val nickname get() = vm.nickname
    override val privateChats get() = vm.privateChats
    override val peerNicknames get() = vm.peerNicknames
    override val unreadPrivateMessages get() = vm.unreadPrivateMessages
    override val connectedPeers get() = vm.connectedPeers
    override val peerDirect get() = vm.peerDirect
    override val favoritePeers get() = vm.favoritePeers
    override val joinedChannels get() = vm.joinedChannels
    override val unreadChannelMessages get() = vm.unreadChannelMessages
    override val passwordProtectedChannels get() = vm.passwordProtectedChannels

    override fun isFavorite(peerId: String) = vm.isFavorite(peerId)
    override fun toggleFavorite(peerId: String) = vm.toggleFavorite(peerId)
    override fun joinChannel(name: String) = vm.joinChannel(name)
    override fun leaveChannel(name: String) = vm.leaveChannel(name)
    override suspend fun startPrivateChat(peerId: String) = vm.startPrivateChat(peerId)
}
