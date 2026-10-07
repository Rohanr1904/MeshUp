package com.bitchat.android.meshup.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class MeshUpTab { CHATS, PEOPLE, ROOMS, PROFILE }

/** Single source of truth for the selected tab; also read by the Activity back callback. */
class MeshUpShellState {
    var tab by mutableStateOf(MeshUpTab.CHATS)

    /** Consumes a back press when not on Chats (goes to Chats). Returns true if consumed. */
    fun handleBack(): Boolean {
        if (tab == MeshUpTab.CHATS) return false
        tab = MeshUpTab.CHATS
        return true
    }
}
