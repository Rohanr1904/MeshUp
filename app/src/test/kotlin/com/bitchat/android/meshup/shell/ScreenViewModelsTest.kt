package com.bitchat.android.meshup.shell

import com.bitchat.android.meshup.service.ChannelRoomService
import com.bitchat.android.meshup.service.ChatViewModelMessagingService
import com.bitchat.android.meshup.service.ChatViewModelPeopleService
import com.bitchat.android.meshup.service.FakeLegacyChatSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenViewModelsTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val src = FakeLegacyChatSource()
    private val scope = TestScope(dispatcher)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun peopleVm() = PeopleViewModel(
        ChatViewModelPeopleService(src, scope.backgroundScope),
        ChatViewModelMessagingService(src, scope.backgroundScope)
    )

    @Test fun peopleStateFollowsSource() {
        val vm = peopleVm()
        src.connectedPeers.value = listOf("p1")
        src.peerNicknames.value = mapOf("p1" to "Ann")
        assertEquals(listOf("Ann"), vm.state.value.map { it.displayName })
    }

    @Test fun openStartsPrivateChatThenCallsBack() {
        val vm = peopleVm()
        var opened = false
        vm.open("p1") { opened = true }
        assertEquals(listOf("p1"), src.started)
        assertTrue(opened)
    }

    @Test fun peopleToggleFavorite() {
        peopleVm().toggleFavorite("p1")
        assertEquals(listOf("p1"), src.toggled)
    }

    @Test fun roomsJoinNormalizesAndRejectsInvalid() {
        val vm = RoomsViewModel(ChannelRoomService(src, scope.backgroundScope))
        assertFalse(vm.join("  "))
        assertFalse(vm.join("#"))
        assertFalse(vm.join("two words"))
        assertTrue(vm.join(" #general "))
        assertEquals(listOf("general"), src.joined)
    }

    @Test fun roomsStateAndLeave() {
        val vm = RoomsViewModel(ChannelRoomService(src, scope.backgroundScope))
        src.joinedChannels.value = setOf("#a")
        assertEquals(listOf("#a"), vm.state.value.map { it.name })
        vm.leave("#a")
        assertEquals(listOf("#a"), src.left)
    }

    @Test fun shellBackGoesToChatsFirst() {
        val shell = MeshUpShellState()
        assertFalse(shell.handleBack())
        shell.tab = MeshUpTab.ROOMS
        assertTrue(shell.handleBack())
        assertEquals(MeshUpTab.CHATS, shell.tab)
        assertFalse(shell.handleBack())
    }
}
