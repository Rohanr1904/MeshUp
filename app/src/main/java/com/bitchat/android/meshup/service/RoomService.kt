package com.bitchat.android.meshup.service

import com.bitchat.android.meshup.domain.Room
import kotlinx.coroutines.flow.StateFlow

/** Outcome of [RoomService.joinRoom]. */
enum class JoinResult { JOINED, INVALID_NAME, PASSWORD_PROTECTED, REJECTED }

/** Public rooms (legacy channels). Password-protected rooms are not supported in Phase 1. */
interface RoomService {
    val rooms: StateFlow<List<Room>>

    /** Joins (or re-selects) the public room [name]; never joins a password-protected room. */
    fun joinRoom(name: String): JoinResult

    fun leaveRoom(name: String)
}
