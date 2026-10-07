package com.bitchat.android.meshup.service

import com.bitchat.android.meshup.domain.Conversation
import com.bitchat.android.meshup.domain.Mappers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ChatViewModelMessagingService(
    private val source: LegacyChatSource,
    scope: CoroutineScope
) : MessagingService {
    override val conversations: StateFlow<List<Conversation>> = combine(
        source.privateChats, source.peerNicknames, source.unreadPrivateMessages
    ) { chats, names, unread -> Mappers.conversations(chats, names, unread) }
        .stateIn(
            scope, SharingStarted.Eagerly,
            Mappers.conversations(
                source.privateChats.value, source.peerNicknames.value, source.unreadPrivateMessages.value
            )
        )

    override suspend fun openConversation(peerId: String) = source.startPrivateChat(peerId)
}
