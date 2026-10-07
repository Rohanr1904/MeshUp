package com.bitchat.android.meshup.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.identity.IdentityHealth
import com.bitchat.android.identity.IdentityIssue
import com.bitchat.android.identity.IdentityIssueReason
import com.bitchat.android.identity.IdentityKeyKind
import com.bitchat.android.meshup.domain.Conversation
import com.bitchat.android.meshup.domain.Person
import com.bitchat.android.meshup.domain.Room
import com.bitchat.android.meshup.service.AndroidTestChatSource
import com.bitchat.android.meshup.service.MessagingService
import com.bitchat.android.meshup.service.PeopleService
import com.bitchat.android.meshup.service.RoomService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class IdentityResetTest {
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

    @Before fun clear() = IdentityHealth.acknowledge()
    @After fun clearAfter() = IdentityHealth.acknowledge()

    private fun openProfile() {
        val profile = AndroidTestChatSource.profile(context, source, confirmed = true)
        rule.setContent {
            MaterialTheme {
                MeshUpApp(MeshUpShellState(), messaging, people, rooms, profile) { Text("stub chats") }
            }
        }
        rule.onNodeWithTag("tab_profile").performClick()
    }

    @Test fun resetConfirmDisabledUntilResetTyped() {
        openProfile()
        rule.onNodeWithTag("profile_reset_identity").performScrollTo().performClick()
        rule.onNodeWithTag("identity_reset_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("identity_reset_field").performTextReplacement("reset!")
        rule.onNodeWithTag("identity_reset_confirm").assertIsNotEnabled()
        rule.onNodeWithTag("identity_reset_field").performTextReplacement("reset")
        rule.onNodeWithTag("identity_reset_confirm").assertIsEnabled()
        rule.onNodeWithTag("identity_reset_confirm").performClick()
        rule.waitForIdle()
        assertEquals(1, source.panicClears)
    }

    @Test fun bannerShowsAndGotItHidesIt() {
        IdentityHealth.report(IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.NOISE_STATIC))
        openProfile()
        rule.onNodeWithTag("identity_banner").assertIsDisplayed()
        rule.onNodeWithTag("identity_banner_got_it").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("identity_banner").assertDoesNotExist()
    }
}
