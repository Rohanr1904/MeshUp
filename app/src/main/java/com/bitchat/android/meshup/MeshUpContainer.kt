package com.bitchat.android.meshup

import com.bitchat.android.meshup.service.ChannelRoomService
import com.bitchat.android.meshup.service.ChatViewModelMessagingService
import com.bitchat.android.meshup.service.ChatViewModelPeopleService
import com.bitchat.android.meshup.service.ChatViewModelSource
import com.bitchat.android.meshup.service.LegacyChatSource
import com.bitchat.android.meshup.service.MessagingService
import com.bitchat.android.meshup.service.PeopleService
import com.bitchat.android.meshup.service.RoomService
import com.bitchat.android.meshup.shell.MeshUpShellState
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Manual DI (P1-8). Adapters wrap the single existing ChatViewModel; never create a second one. */
class MeshUpContainer(source: LegacyChatSource, scope: CoroutineScope) {
    val messaging: MessagingService = ChatViewModelMessagingService(source, scope)
    val people: PeopleService = ChatViewModelPeopleService(source, scope)
    val rooms: RoomService = ChannelRoomService(source, scope)
    val displayName: StateFlow<String> = source.nickname
    val shell = MeshUpShellState()

    companion object {
        fun create(chatViewModel: ChatViewModel, scope: CoroutineScope) =
            MeshUpContainer(ChatViewModelSource(chatViewModel), scope)
    }
}
