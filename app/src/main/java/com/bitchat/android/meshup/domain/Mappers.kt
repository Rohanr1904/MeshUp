package com.bitchat.android.meshup.domain

import com.bitchat.android.model.BitchatMessage

/**
 * Pure mappers from legacy state values to domain models. They take plain collections
 * (not ChatViewModel) so they can be unit tested directly.
 */
object Mappers {

    /**
     * Connected peers only, sorted by favourite first then name. Unknown nicknames fall back to
     * "Unknown". A peer is DIRECT when [peerDirect] says so, otherwise RELAYED.
     * Legacy favourites are keyed by fingerprint, not peerID, so the caller supplies
     * [isFavorite] (e.g. `ChatViewModel::isFavorite`) and must re-map when favourites change.
     */
    fun people(
        connectedPeers: List<String>,
        peerNicknames: Map<String, String>,
        peerDirect: Map<String, Boolean>,
        isFavorite: (peerId: String) -> Boolean
    ): List<Person> = connectedPeers.distinct()
        .map { id ->
            Person(
                id = id,
                displayName = peerNicknames[id]?.takeIf { it.isNotBlank() } ?: "Unknown",
                reachability = if (peerDirect[id] == true) Reachability.DIRECT else Reachability.RELAYED,
                isFavorite = isFavorite(id)
            )
        }
        .sortedWith(compareByDescending<Person> { it.isFavorite }.thenBy { it.displayName.lowercase() })

    /** One conversation per private chat, newest last-message first. */
    fun conversations(
        privateChats: Map<String, List<BitchatMessage>>,
        peerNicknames: Map<String, String>,
        unreadPeers: Set<String>
    ): List<Conversation> = privateChats.map { (peerId, messages) ->
        val last = messages.maxByOrNull { it.timestamp.time }
        Conversation(
            peerId = peerId,
            title = peerNicknames[peerId]?.takeIf { it.isNotBlank() } ?: "Unknown",
            lastMessagePreview = last?.content,
            lastMessageTimestampMs = last?.timestamp?.time,
            hasUnread = peerId in unreadPeers
        )
    }.sortedByDescending { it.lastMessageTimestampMs ?: Long.MIN_VALUE }

    /** Joined rooms only, sorted by name. Unread counts for channels not joined are ignored. */
    fun rooms(
        joinedChannels: Set<String>,
        unreadChannelCounts: Map<String, Int>,
        passwordProtected: Set<String>
    ): List<Room> = joinedChannels
        .map { name ->
            Room(
                name = name,
                unreadCount = unreadChannelCounts[name] ?: 0,
                isJoined = true,
                isPasswordProtected = name in passwordProtected
            )
        }
        .sortedBy { it.name.lowercase() }
}
