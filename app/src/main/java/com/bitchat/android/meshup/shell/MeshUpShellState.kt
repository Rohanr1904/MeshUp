package com.bitchat.android.meshup.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class MeshUpTab { CHATS, PEOPLE, ROOMS, PROFILE }

/**
 * Decision 019: the Rooms tab is hidden in v1. Mesh channel messages carry no channel on the
 * wire (inherited from bitchat), so a "room" message reaches everyone nearby in the public chat.
 * The tab stays in the code for a later version with a real room format.
 */
const val ROOMS_TAB_ENABLED = false

/** Tabs shown in the bottom bar. */
fun visibleTabs(): List<MeshUpTab> =
    MeshUpTab.values().filter { it != MeshUpTab.ROOMS || ROOMS_TAB_ENABLED }

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
