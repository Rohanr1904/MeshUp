package com.bitchat.android.meshup.service

import android.content.Context
import com.bitchat.android.meshup.profile.DataManagerDisplayNameStore
import com.bitchat.android.meshup.profile.ProfileManager
import com.bitchat.android.meshup.profile.ProfileRepository
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.flow.MutableStateFlow

/** Inert [LegacyChatSource] for Compose tests; records nickname changes. */
class AndroidTestChatSource(initialNickname: String = "anon1234") : LegacyChatSource {
    override val nickname = MutableStateFlow(initialNickname)
    override val privateChats = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    override val peerNicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    override val unreadPrivateMessages = MutableStateFlow<Set<String>>(emptySet())
    override val connectedPeers = MutableStateFlow<List<String>>(emptyList())
    override val peerDirect = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    override val favoritePeers = MutableStateFlow<Set<String>>(emptySet())
    override val joinedChannels = MutableStateFlow<Set<String>>(emptySet())
    override val unreadChannelMessages = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val passwordProtectedChannels = MutableStateFlow<Set<String>>(emptySet())
    val nicknamesSet = mutableListOf<String>()

    override fun isFavorite(peerId: String) = false
    override fun toggleFavorite(peerId: String) {}
    override fun joinChannel(name: String) = true
    override fun leaveChannel(name: String) {}
    override suspend fun startPrivateChat(peerId: String) {}
    override fun setNickname(name: String) {
        nicknamesSet += name
        nickname.value = name
    }
    override fun myFingerprint() = "0123456789abcdef0123456789abcdef"

    companion object {
        /** Fresh profile on cleared prefs; [confirmed] pre-sets the name-confirmed flag. */
        fun profile(context: Context, source: LegacyChatSource, confirmed: Boolean): ProfileManager {
            context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
            val settings = context.getSharedPreferences(ProfileRepository.PREFS_NAME, Context.MODE_PRIVATE)
            settings.edit().clear().putBoolean(ProfileRepository.KEY_NAME_CONFIRMED, confirmed).commit()
            val store = DataManagerDisplayNameStore(DataManager(context))
            return ProfileManager(ProfileRepository(store, settings), source)
        }
    }
}
