package com.bitchat.android.meshup.domain

/** How a person is currently reachable over the local mesh. */
enum class Reachability { DIRECT, RELAYED }

/** A nearby person. [id] is the internal peerID and is never shown in the UI. */
data class Person(
    val id: String,
    val displayName: String,
    val reachability: Reachability,
    val isFavorite: Boolean
)
