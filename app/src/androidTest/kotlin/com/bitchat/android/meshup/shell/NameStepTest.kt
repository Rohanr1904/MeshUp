package com.bitchat.android.meshup.shell

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NameStepTest {
    @get:Rule val rule = createComposeRule()

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

    private fun setApp(confirmed: Boolean) {
        val profile = AndroidTestChatSource.profile(context, source, confirmed)
        rule.setContent {
            MaterialTheme {
                MeshUpApp(MeshUpShellState(), messaging, people, rooms, profile) { Text("stub chats") }
            }
        }
    }

    @Test fun showsWhenUnconfirmedPrefilledWithCopy() {
        setApp(confirmed = false)
        rule.onNodeWithTag("screen_name_step").assertIsDisplayed()
        rule.onNodeWithText("Choose a display name").assertIsDisplayed()
        rule.onNodeWithText("This name is visible to people nearby. You can change it any time.")
            .assertIsDisplayed()
        rule.onNodeWithText("anon1234").assertIsDisplayed()
        rule.onNodeWithTag("name_save").assertIsEnabled()
        rule.onNodeWithText("stub chats").assertDoesNotExist()
    }

    @Test fun doesNotShowWhenConfirmed() {
        setApp(confirmed = true)
        rule.onNodeWithTag("screen_name_step").assertDoesNotExist()
        rule.onNodeWithText("stub chats").assertIsDisplayed()
    }

    @Test fun emptyInputShowsErrorAndDisablesSave() {
        setApp(confirmed = false)
        rule.onNodeWithTag("name_field").performTextReplacement("")
        rule.onNodeWithText("Enter a name").assertIsDisplayed()
        rule.onNodeWithTag("name_save").assertIsNotEnabled()
    }

    @Test fun thirtyThreeCharsShowsTooLongError() {
        setApp(confirmed = false)
        rule.onNodeWithTag("name_field").performTextReplacement("a".repeat(33))
        rule.onNodeWithText("Name must be 32 characters or fewer").assertIsDisplayed()
        rule.onNodeWithTag("name_save").assertIsNotEnabled()
    }

    @Test fun saveLeadsToShellRoutesThroughLegacySourceAndPersistsFlag() {
        setApp(confirmed = false)
        rule.onNodeWithTag("name_field").performTextReplacement("Alice")
        rule.onNodeWithTag("name_save").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("screen_name_step").assertDoesNotExist()
        rule.onNodeWithText("stub chats").assertIsDisplayed()
        assertEquals(listOf("Alice"), source.nicknamesSet)
        // The flag is persisted, so a new composition (e.g. after restart) skips the step.
        val prefs = context.getSharedPreferences("meshup_settings", Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("profile_name_confirmed", false))
    }

    @Test fun profileTabShowsFingerprintAndInternetSwitch() {
        setApp(confirmed = true)
        rule.onNodeWithTag("tab_profile").performClick()
        rule.onNodeWithTag("profile_fingerprint").assertIsDisplayed()
        rule.onNodeWithText("0123 4567 89AB CDEF").assertIsDisplayed()
        rule.onNodeWithTag("switch_internet").assertIsDisplayed()
    }
}
