package com.bitchat.android.meshup.shell

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.meshup.domain.Person
import com.bitchat.android.meshup.domain.Room
import com.bitchat.android.meshup.service.MessagingService
import com.bitchat.android.meshup.service.PeopleService
import com.bitchat.android.meshup.service.JoinResult
import com.bitchat.android.meshup.service.RoomService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PeopleViewModel(
    private val people: PeopleService,
    private val messaging: MessagingService
) : ViewModel() {
    val state: StateFlow<List<Person>> get() = people.people

    fun toggleFavorite(id: String) = people.toggleFavorite(id)

    /** Opens the private chat, then invokes [onOpened] (to switch to the Chats tab). */
    fun open(id: String, onOpened: () -> Unit) {
        viewModelScope.launch {
            messaging.openConversation(id)
            onOpened()
        }
    }
}

class RoomsViewModel(private val rooms: RoomService) : ViewModel() {
    val state: StateFlow<List<Room>> get() = rooms.rooms

    /** Joins (or re-selects) a public room. Never passes a password. */
    fun join(name: String): JoinResult = rooms.joinRoom(name)

    fun leave(name: String) = rooms.leaveRoom(name)
}
