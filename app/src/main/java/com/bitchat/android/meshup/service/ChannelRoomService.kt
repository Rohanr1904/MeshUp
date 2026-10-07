package com.bitchat.android.meshup.service

import com.bitchat.android.meshup.domain.Mappers
import com.bitchat.android.meshup.domain.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Rooms over legacy channels. Password rooms are out of scope (Phase 3 / R-4): the legacy password
 * check is a stub (`ChannelManager.verifyChannelPassword`), so a known password-protected room is
 * refused here before the legacy join can open its password prompt. No password is ever passed.
 */
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

    override fun joinRoom(name: String): JoinResult {
        val base = normalize(name) ?: return JoinResult.INVALID_NAME
        if ("#$base" in source.passwordProtectedChannels.value) return JoinResult.PASSWORD_PROTECTED
        return if (source.joinChannel(base)) JoinResult.JOINED else JoinResult.REJECTED
    }

    override fun leaveRoom(name: String) = source.leaveChannel(name)

    companion object {
        /** Strips one leading '#' and surrounding spaces; null if empty or containing spaces/controls. */
        fun normalize(raw: String): String? {
            val base = raw.trim().removePrefix("#").trim()
            if (base.isEmpty() || base.any { it.isWhitespace() || it.isISOControl() || it == '#' }) return null
            return base
        }
    }
}
