package com.bitchat.android.meshup.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bitchat.android.R
import com.bitchat.android.meshup.MeshUpContainer
import com.bitchat.android.meshup.domain.Reachability
import com.bitchat.android.meshup.service.MessagingService
import com.bitchat.android.meshup.service.PeopleService
import com.bitchat.android.meshup.service.RoomService
import com.bitchat.android.ui.ChatScreen
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.flow.StateFlow

/** Production entry point: Chats tab hosts the legacy [ChatScreen] on the single [chatViewModel]. */
@Composable
fun MeshUpApp(container: MeshUpContainer, chatViewModel: ChatViewModel, modifier: Modifier = Modifier) {
    MeshUpApp(
        shell = container.shell,
        messaging = container.messaging,
        people = container.people,
        rooms = container.rooms,
        displayName = container.displayName,
        modifier = modifier,
        chatsContent = { ChatScreen(viewModel = chatViewModel) }
    )
}

/** Testable shell. [chatsContent] is the Chats tab slot. */
@Composable
fun MeshUpApp(
    shell: MeshUpShellState,
    messaging: MessagingService,
    people: PeopleService,
    rooms: RoomService,
    displayName: StateFlow<String>,
    modifier: Modifier = Modifier,
    chatsContent: @Composable () -> Unit
) {
    val navController = rememberNavController()
    val tab = shell.tab

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        // The outer Scaffold/ChatScreen own top insets; only the bar is added at the bottom.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                MeshUpTab.values().forEach { t ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("tab_${t.route}"),
                        selected = tab == t,
                        onClick = { shell.tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(stringResource(t.label)) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MeshUpTab.CHATS.route,
            // Consume the bar's padding so the legacy ChatScreen's own navigation-bar/IME insets
            // do not add a second gap above the bottom bar.
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            composable(MeshUpTab.CHATS.route) { chatsContent() }
            // New tabs own their top inset (the legacy ChatScreen handles its own).
            composable(MeshUpTab.PEOPLE.route) {
                val vm = viewModel { PeopleViewModel(people, messaging) }
                Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    PeopleScreen(vm) { shell.tab = MeshUpTab.CHATS }
                }
            }
            composable(MeshUpTab.ROOMS.route) {
                val vm = viewModel { RoomsViewModel(rooms) }
                Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    RoomsScreen(vm) { shell.tab = MeshUpTab.CHATS }
                }
            }
            composable(MeshUpTab.PROFILE.route) {
                Box(Modifier.windowInsetsPadding(WindowInsets.statusBars)) { ProfileScreen(displayName) }
            }
        }

        // shell.tab is the single source of truth; the NavHost follows it. Declared after the
        // NavHost so the graph is set before the first navigate (start destination is Chats).
        LaunchedEffect(tab) {
            val current = navController.currentDestination?.route
            if (current != null && current != tab.route) {
                navController.navigate(tab.route) {
                    popUpTo(MeshUpTab.CHATS.route) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
    }
}

private val MeshUpTab.route: String
    get() = when (this) {
        MeshUpTab.CHATS -> "chats"
        MeshUpTab.PEOPLE -> "people"
        MeshUpTab.ROOMS -> "rooms"
        MeshUpTab.PROFILE -> "profile"
    }

private val MeshUpTab.label: Int
    get() = when (this) {
        MeshUpTab.CHATS -> R.string.meshup_tab_chats
        MeshUpTab.PEOPLE -> R.string.meshup_tab_people
        MeshUpTab.ROOMS -> R.string.meshup_tab_rooms
        MeshUpTab.PROFILE -> R.string.meshup_tab_profile
    }

private val MeshUpTab.icon: ImageVector
    get() = when (this) {
        MeshUpTab.CHATS -> Icons.AutoMirrored.Filled.Chat
        MeshUpTab.PEOPLE -> Icons.Filled.People
        MeshUpTab.ROOMS -> Icons.Filled.Tag
        MeshUpTab.PROFILE -> Icons.Filled.Person
    }

@Composable
private fun PeopleScreen(vm: PeopleViewModel, onOpened: () -> Unit) {
    val list by vm.state.collectAsState()
    Box(Modifier.fillMaxSize().testTag("screen_people")) {
        if (list.isEmpty()) {
            Text(
                stringResource(R.string.meshup_people_empty),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { person ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.open(person.id, onOpened) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(person.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(
                                    if (person.reachability == Reachability.DIRECT)
                                        R.string.meshup_reachability_direct
                                    else R.string.meshup_reachability_relayed
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { vm.toggleFavorite(person.id) }) {
                            Icon(
                                if (person.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                contentDescription = stringResource(R.string.meshup_toggle_favorite)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomsScreen(vm: RoomsViewModel, onOpened: () -> Unit) {
    val list by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val doJoin = {
        if (vm.join(input)) {
            input = ""
            onOpened()
        }
    }
    Column(Modifier.fillMaxSize().testTag("screen_rooms")) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.meshup_room_name_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { doJoin() })
            )
            Button(onClick = { doJoin() }) { Text(stringResource(R.string.meshup_room_join)) }
        }
        if (list.isEmpty()) {
            Text(
                stringResource(R.string.meshup_rooms_empty),
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.name }) { room ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { if (vm.join(room.name)) onOpened() }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(room.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        if (room.unreadCount > 0) {
                            Text(room.unreadCount.toString(), color = MaterialTheme.colorScheme.primary)
                        }
                        TextButton(onClick = { vm.leave(room.name) }) {
                            Text(stringResource(R.string.meshup_room_leave))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(displayName: StateFlow<String>) {
    val name by displayName.collectAsState()
    Column(
        Modifier.fillMaxSize().testTag("screen_profile").padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(stringResource(R.string.meshup_profile_display_name), style = MaterialTheme.typography.labelMedium)
        Text(name, style = MaterialTheme.typography.headlineSmall)
    }
}
