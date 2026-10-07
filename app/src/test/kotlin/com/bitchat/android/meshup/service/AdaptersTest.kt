package com.bitchat.android.meshup.service

import com.bitchat.android.meshup.domain.Reachability
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class AdaptersTest {
    private val src = FakeLegacyChatSource()
    private val scope = TestScope(UnconfinedTestDispatcher())

    @Test fun conversationsRecomputeOnSourceChanges() {
        val svc = ChatViewModelMessagingService(src, scope.backgroundScope)
        assertTrue(svc.conversations.value.isEmpty())
        src.peerNicknames.value = mapOf("a" to "Ann")
        src.privateChats.value = mapOf("a" to listOf(BitchatMessage(sender = "x", content = "hi", timestamp = Date(5))))
        src.unreadPrivateMessages.value = setOf("a")
        val c = svc.conversations.value.single()
        assertEquals("Ann", c.title)
        assertEquals("hi", c.lastMessagePreview)
        assertTrue(c.hasUnread)
    }

    @Test fun conversationsInitialValueReflectsCurrentState() {
        src.privateChats.value = mapOf("a" to emptyList())
        val svc = ChatViewModelMessagingService(src, scope.backgroundScope)
        assertEquals(listOf("a"), svc.conversations.value.map { it.peerId })
    }

    @Test fun openConversationDelegates() = runTest {
        ChatViewModelMessagingService(src, scope.backgroundScope).openConversation("p9")
        assertEquals(listOf("p9"), src.started)
    }

    @Test fun peopleRecomputeOnPeersDirectAndFavoriteTrigger() {
        val svc = ChatViewModelPeopleService(src, scope.backgroundScope)
        src.connectedPeers.value = listOf("p1", "p2")
        src.peerNicknames.value = mapOf("p1" to "zed", "p2" to "amy")
        src.peerDirect.value = mapOf("p1" to true)
        assertEquals(listOf("amy", "zed"), svc.people.value.map { it.displayName })
        assertEquals(Reachability.DIRECT, svc.people.value.first { it.id == "p1" }.reachability)

        // Predicate changes are only picked up when favoritePeers emits.
        src.favoriteFingerprints += "p1"
        src.favoritePeers.value = setOf("fp")
        assertEquals("zed", svc.people.value.first().displayName)
        assertTrue(svc.people.value.first().isFavorite)
    }

    @Test fun toggleFavoriteDelegates() {
        ChatViewModelPeopleService(src, scope.backgroundScope).toggleFavorite("p1")
        assertEquals(listOf("p1"), src.toggled)
    }

    @Test fun roomsMapAndDelegate() {
        val svc = ChannelRoomService(src, scope.backgroundScope)
        src.joinedChannels.value = setOf("#b", "#a")
        src.unreadChannelMessages.value = mapOf("#a" to 3)
        src.passwordProtectedChannels.value = setOf("#b")
        val rooms = svc.rooms.value
        assertEquals(listOf("#a", "#b"), rooms.map { it.name })
        assertEquals(3, rooms[0].unreadCount)
        assertTrue(rooms[1].isPasswordProtected)
        assertEquals(JoinResult.JOINED, svc.joinRoom("x"))
        src.joinResult = false
        assertEquals(JoinResult.REJECTED, svc.joinRoom("y"))
        assertEquals(JoinResult.PASSWORD_PROTECTED, svc.joinRoom("#b"))
        svc.leaveRoom("x")
        assertEquals(listOf("x", "y"), src.joined)
        assertEquals(listOf("x"), src.left)
    }
}
