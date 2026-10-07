package com.bitchat.android.meshup.service

import com.bitchat.android.meshup.domain.Mappers
import com.bitchat.android.meshup.domain.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Rooms over legacy channels. Password rooms are out of scope: no password is ever passed. */
class ChannelRoomService(
    private val source: LegacyChatSource,
    scope: CoroutineScope
) : RoomService {
    override val rooms: StateFlow<List<Room>> = combine(
        source.joinedChannels, source.unreadChannelMessages, source.passwordProtectedChannels
    ) { joined, unread, pw -> Mappers.rooms(joined, unread, pw) }
        .stateIn(
            scope, SharingStarted.Eagerly,
            Mappers.rooms(
                source.joinedChannels.value, source.unreadChannelMessages.value,
                source.passwordProtectedChannels.value
            )
        )

    override fun joinRoom(name: String): Boolean = source.joinChannel(name)
    override fun leaveRoom(name: String) = source.leaveChannel(name)
}
