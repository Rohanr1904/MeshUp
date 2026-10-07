package com.bitchat.android.meshup.domain

import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class MappersTest {
    @Test fun peopleMapsFieldsAndSortsFavoritesFirst() {
        val out = Mappers.people(
            connectedPeers = listOf("p1", "p2", "p3", "p1"),
            peerNicknames = mapOf("p1" to "zed", "p2" to "bob", "p3" to " "),
            peerDirect = mapOf("p1" to true, "p2" to false),
            isFavorite = { it == "p1" }
        )
        assertEquals(listOf("p1", "p2", "p3"), out.map { it.id })
        assertEquals(Reachability.DIRECT, out[0].reachability)
        assertTrue(out[0].isFavorite)
        assertEquals(Reachability.RELAYED, out[1].reachability)
        assertEquals("Unknown", out[2].displayName)
        assertFalse(out[1].isFavorite)
    }

    @Test fun conversationsUseLatestMessageAndUnread() {
        fun msg(c: String, t: Long) = BitchatMessage(sender = "x", content = c, timestamp = Date(t))
        val out = Mappers.conversations(
            privateChats = mapOf(
                "a" to listOf(msg("old", 1), msg("new", 5)),
                "b" to listOf(msg("later", 9)),
                "c" to emptyList()
            ),
            peerNicknames = mapOf("a" to "Ann"),
            unreadPeers = setOf("b")
        )
        assertEquals(listOf("b", "a", "c"), out.map { it.peerId })
        assertEquals("new", out[1].lastMessagePreview)
        assertEquals("Ann", out[1].title)
        assertTrue(out[0].hasUnread)
        assertNull(out[2].lastMessagePreview)
    }

    @Test fun roomsMapJoinedUnreadAndPassword() {
        val out = Mappers.rooms(
            joinedChannels = setOf("#b", "#a"),
            unreadChannelCounts = mapOf("#a" to 3, "#c" to 1),
            passwordProtected = setOf("#b")
        )
        assertEquals(listOf("#a", "#b"), out.map { it.name })
        assertEquals(3, out[0].unreadCount)
        assertEquals(0, out[1].unreadCount)
        assertTrue(out[1].isPasswordProtected)
        assertTrue(out.all { it.isJoined })
    }
}
