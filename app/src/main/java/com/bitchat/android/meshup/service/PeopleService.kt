package com.bitchat.android.meshup.service

import com.bitchat.android.meshup.domain.Person
import kotlinx.coroutines.flow.StateFlow

/** Nearby people. Implemented over ChatViewModel in a later PR. */
interface PeopleService {
    val people: StateFlow<List<Person>>

    fun toggleFavorite(peerId: String)
}
