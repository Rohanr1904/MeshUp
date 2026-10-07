package com.bitchat.android.meshup.service

import com.bitchat.android.meshup.domain.Conversation
import kotlinx.coroutines.flow.StateFlow

/**
 * Private conversations. Sending is intentionally absent: PR-3 hosts the legacy
 * ChatScreen for composing/sending, so nothing needs a "selected context" send here.
 */
interface MessagingService {
    val conversations: StateFlow<List<Conversation>>

    /** Opens (and selects) the private conversation with [peerId] in the legacy chat UI. */
    suspend fun openConversation(peerId: String)
}
