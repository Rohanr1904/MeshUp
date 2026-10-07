package com.bitchat.android.meshup.service

import com.bitchat.android.meshup.domain.Conversation
import kotlinx.coroutines.flow.StateFlow

/** Messaging commands and conversation list. Implemented over ChatViewModel in a later PR. */
interface MessagingService {
    val conversations: StateFlow<List<Conversation>>

    /** Sends [content] in the currently selected context; [onAccepted] reports acceptance. */
    fun sendMessage(content: String, onAccepted: (Boolean) -> Unit = {})

    /** Opens (or creates) the private chat with [peerId]. */
    suspend fun openConversation(peerId: String)
}
