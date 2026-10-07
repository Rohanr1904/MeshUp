package com.bitchat.android.meshup.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.meshup.domain.Conversation
import com.bitchat.android.meshup.domain.Person
import com.bitchat.android.meshup.domain.Room
import com.bitchat.android.meshup.service.AndroidTestChatSource
import com.bitchat.android.meshup.service.ChannelRoomService
import com.bitchat.android.meshup.service.JoinResult
import com.bitchat.android.meshup.service.MessagingService
import com.bitchat.android.meshup.service.PeopleService
import com.bitchat.android.meshup.service.RoomService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RoomsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val shell = MeshUpShellState()

    /** Stateful fake: joining adds a public room, leaving removes it; known password rooms are refused. */
    private class FakeRooms(initial: List<Room>) : RoomService {
        val state = MutableStateFlow(initial)
        val joinCalls = mutableListOf<String>()
        override val rooms: StateFlow<List<Room>> = state
        override fun joinRoom(name: String): JoinResult {
            val base = ChannelRoomService.normalize(name) ?: return JoinResult.INVALID_NAME
            val tag = "#$base"
            if (state.value.any { it.name == tag && it.isPasswordProtected }) return JoinResult.PASSWORD_PROTECTED
            joinCalls += base
            if (state.value.none { it.name == tag }) state.value = state.value + Room(tag, 0, true, false)
            return JoinResult.JOINED
        }
        override fun leaveRoom(name: String) {
            state.value = state.value.filterNot { it.name == name }
        }
    }

    private val messaging = object : MessagingService {
        override val conversations = MutableStateFlow(emptyList<Conversation>())
        override suspend fun openConversation(peerId: String) {}
    }
    private val people = object : PeopleService {
        override val people: StateFlow<List<Person>> = MutableStateFlow(emptyList())
        override fun toggleFavorite(peerId: String) {}
    }

    private fun setShell(rooms: RoomService) {
        shell.tab = MeshUpTab.ROOMS
        rule.setContent {
            MaterialTheme {
                MeshUpApp(
                    shell = shell,
                    messaging = messaging,
                    people = people,
                    rooms = rooms,
                    profile = AndroidTestChatSource.profile(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                        AndroidTestChatSource("Tester"),
                        confirmed = true
                    )
                ) { Text("stub chats") }
            }
        }
    }

    @Test fun joinByNameAddsRoomAndOpensChats() {
        val rooms = FakeRooms(emptyList())
        setShell(rooms)
        rule.onNodeWithTag("field_room_name").performTextInput("#general")
        rule.onNodeWithTag("button_join").performClick()
        rule.waitForIdle()
        assertEquals(listOf("general"), rooms.joinCalls)
        assertEquals(MeshUpTab.CHATS, shell.tab)
        rule.onNodeWithText("stub chats").assertIsDisplayed()
    }

    @Test fun invalidNameShowsErrorAndStaysOnRooms() {
        val rooms = FakeRooms(emptyList())
        setShell(rooms)
        rule.onNodeWithTag("field_room_name").performTextInput("two words")
        rule.onNodeWithTag("button_join").performClick()
        rule.onNodeWithText("Enter a room name without spaces").assertIsDisplayed()
        assertTrue(rooms.joinCalls.isEmpty())
        assertEquals(MeshUpTab.ROOMS, shell.tab)
    }

    @Test fun leaveRemovesRoom() {
        val rooms = FakeRooms(listOf(Room("#general", 2, true, false)))
        setShell(rooms)
        rule.onNodeWithTag("room_#general").assertIsDisplayed()
        rule.onNodeWithTag("leave_#general").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("You have not joined any rooms").assertIsDisplayed()
    }

    @Test fun passwordRoomCannotBeOpenedOrJoined() {
        val rooms = FakeRooms(listOf(Room("#secret", 0, true, true)))
        setShell(rooms)
        rule.onNodeWithText("Password rooms are not supported yet").assertIsDisplayed()
        rule.onNodeWithTag("room_#secret").assertIsNotEnabled()
        rule.onNodeWithTag("field_room_name").performTextInput("secret")
        rule.onNodeWithTag("button_join").performClick()
        rule.onNodeWithText("This room needs a password. Password rooms are not supported yet.").assertIsDisplayed()
        assertTrue(rooms.joinCalls.isEmpty())
    }
}
