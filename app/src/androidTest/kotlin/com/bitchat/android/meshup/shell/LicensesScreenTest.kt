package com.bitchat.android.meshup.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.meshup.domain.Conversation
import com.bitchat.android.meshup.domain.Person
import com.bitchat.android.meshup.domain.Room
import com.bitchat.android.meshup.service.AndroidTestChatSource
import com.bitchat.android.meshup.service.MessagingService
import com.bitchat.android.meshup.service.PeopleService
import com.bitchat.android.meshup.service.RoomService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test

class LicensesScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = AndroidTestChatSource("anon1234")
    private val messaging = object : MessagingService {
        override val conversations = MutableStateFlow(emptyList<Conversation>())
        override suspend fun openConversation(peerId: String) {}
    }
    private val people = object : PeopleService {
        override val people: StateFlow<List<Person>> = MutableStateFlow(emptyList())
        override fun toggleFavorite(peerId: String) {}
    }
    private val rooms = object : RoomService {
        override val rooms: StateFlow<List<Room>> = MutableStateFlow(emptyList())
        override fun joinRoom(name: String) = com.bitchat.android.meshup.service.JoinResult.JOINED
        override fun leaveRoom(name: String) {}
    }

    @Test fun profileRowOpensLicencesAndBackReturns() {
        val profile = AndroidTestChatSource.profile(context, source, true)
        rule.setContent {
            MaterialTheme {
                MeshUpApp(MeshUpShellState(), messaging, people, rooms, profile) { Text("stub chats") }
            }
        }
        rule.onNodeWithTag("tab_profile").performClick()
        rule.onNodeWithTag("profile_licences_row").performScrollTo().performClick()
        rule.onNodeWithTag("screen_licenses").assertIsDisplayed()
        rule.onNodeWithText("NearBird is free software licensed under the GNU General Public License v3.0.")
            .assertIsDisplayed()
        rule.onNodeWithText("Bouncy Castle", substring = true).performScrollTo().assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("screen_profile").assertIsDisplayed()
    }
}
