package com.bitchat.android.meshup.service

import com.bitchat.android.meshup.domain.Mappers
import com.bitchat.android.meshup.domain.Person
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ChatViewModelPeopleService(
    private val source: LegacyChatSource,
    scope: CoroutineScope
) : PeopleService {
    private fun compute(peers: List<String>, names: Map<String, String>, direct: Map<String, Boolean>) =
        Mappers.people(peers, names, direct, isFavorite = source::isFavorite)

    // favoritePeers is only a recompute trigger: legacy favourites are keyed by fingerprint,
    // so the predicate (source::isFavorite) is the source of truth.
    override val people: StateFlow<List<Person>> = combine(
        source.connectedPeers, source.peerNicknames, source.peerDirect, source.favoritePeers
    ) { peers, names, direct, _ -> compute(peers, names, direct) }
        .stateIn(
            scope, SharingStarted.Eagerly,
            compute(source.connectedPeers.value, source.peerNicknames.value, source.peerDirect.value)
        )

    override fun toggleFavorite(peerId: String) = source.toggleFavorite(peerId)
}
