package com.bitchat.android.meshup.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.bitchat.android.meshup.domain.Person
import com.bitchat.android.meshup.domain.Reachability
import com.bitchat.android.meshup.domain.Room
import com.bitchat.android.meshup.service.AndroidTestChatSource
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.meshup.service.MessagingService
import com.bitchat.android.meshup.service.PeopleService
import com.bitchat.android.meshup.service.RoomService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class MeshUpShellTest {
    @get:Rule val rule = createComposeRule()

    private val shell = MeshUpShellState()

    private val messaging = object : MessagingService {
        override val conversations = MutableStateFlow(emptyList<com.bitchat.android.meshup.domain.Conversation>())
        override suspend fun openConversation(peerId: String) {}
    }
    private val people = object : PeopleService {
        override val people: StateFlow<List<Person>> =
            MutableStateFlow(listOf(Person("p1", "Alice", Reachability.DIRECT, false)))
        override fun toggleFavorite(peerId: String) {}
    }
    private val rooms = object : RoomService {
        override val rooms: StateFlow<List<Room>> =
            MutableStateFlow(listOf(Room("#general", 0, true, false)))
        override fun joinRoom(name: String) = true
        override fun leaveRoom(name: String) {}
    }

    private fun setShell() = rule.setContent {
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

    @Test fun fourTabsRenderAndChatsIsDefault() {
        setShell()
        listOf("chats", "people", "rooms", "profile").forEach {
            rule.onNodeWithTag("tab_$it").assertIsDisplayed()
        }
        rule.onNodeWithText("stub chats").assertIsDisplayed()
    }

    @Test fun clickingEachTabShowsItsScreen() {
        setShell()
        rule.onNodeWithTag("tab_people").performClick()
        rule.onNodeWithTag("screen_people").assertIsDisplayed()
        rule.onNodeWithText("Alice").assertIsDisplayed()

        rule.onNodeWithTag("tab_rooms").performClick()
        rule.onNodeWithTag("screen_rooms").assertIsDisplayed()
        rule.onNodeWithText("#general").assertIsDisplayed()

        rule.onNodeWithTag("tab_profile").performClick()
        rule.onNodeWithTag("screen_profile").assertIsDisplayed()
        rule.onNodeWithText("Tester").assertIsDisplayed()

        rule.onNodeWithTag("tab_chats").performClick()
        rule.onNodeWithText("stub chats").assertIsDisplayed()
    }

    @Test fun tappingPersonSwitchesToChats() {
        setShell()
        rule.onNodeWithTag("tab_people").performClick()
        rule.onNodeWithText("Alice").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("stub chats").assertIsDisplayed()
    }
}
