package com.bitchat.android.meshup.service

import com.bitchat.android.meshup.domain.Room
import kotlinx.coroutines.flow.StateFlow

/** Public rooms (legacy channels). Implemented over ChatViewModel in a later PR. */
interface RoomService {
    val rooms: StateFlow<List<Room>>

    /** Joins the public room [name]; returns false if it was rejected. */
    fun joinRoom(name: String): Boolean

    fun leaveRoom(name: String)
}
