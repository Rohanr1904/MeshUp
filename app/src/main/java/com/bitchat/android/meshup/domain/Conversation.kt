package com.bitchat.android.meshup.domain

/** A one-to-one conversation summary. [peerId] is internal only. */
data class Conversation(
    val peerId: String,
    val title: String,
    val lastMessagePreview: String?,
    val lastMessageTimestampMs: Long?,
    val hasUnread: Boolean
)
