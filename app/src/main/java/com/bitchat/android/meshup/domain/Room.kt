package com.bitchat.android.meshup.domain

/** A public room (legacy channel). [isPasswordProtected] is data only; no logic yet. */
data class Room(
    val name: String,
    val unreadCount: Int,
    val isJoined: Boolean,
    val isPasswordProtected: Boolean
)
