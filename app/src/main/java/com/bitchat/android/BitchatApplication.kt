package com.bitchat.android

import android.app.Application
import com.bitchat.android.nostr.RelayDirectory
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.bitchat.android.net.ArtiTorManager

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching { com.bitchat.android.identity.IdentityHealth.attach(this) }

        // MeshUp: Internet opt-in gate (Decision 013) - must be bound before any component that can
        // open a network connection. Fail-closed (OFF) if reading the setting fails.
        val internetEnabled = try {
            com.bitchat.android.meshup.settings.InternetGate.initialize(this)
            com.bitchat.android.meshup.settings.InternetGate.isEnabled()
        } catch (_: Exception) { false }

        // Start the single process-wide power policy before transport components are constructed.
        com.bitchat.android.mesh.PowerManager.getInstance(this).start()

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        // MeshUp: Internet opt-in gate (Decision 013) - RelayDirectory can download a relay list, so
        // it starts only when Internet is on (InternetController starts it on opt-in).
        if (internetEnabled) RelayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        // MeshUp: Internet opt-in gate (Decision 013)
        if (internetEnabled) {
            try { com.bitchat.android.nostr.LocationNotesInitializer.initialize(this) } catch (_: Exception) { }
        }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Restore private conversations before background transports can deliver new messages.
        // AppStateStore merges any in-flight arrivals by message ID, so startup cannot replace
        // newer transport state with an older database snapshot.
        try {
            com.bitchat.android.services.AppStateStore.initializeConversationPersistence(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize chat UI mode (matrix transcript vs bubbles)
        com.bitchat.android.ui.theme.ChatUiModeManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.bitchat.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Wi‑Fi Aware controller with persisted default
        try {
            val enabled = com.bitchat.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(false)
            com.bitchat.android.wifiaware.WifiAwareController.initialize(this, enabled)
        } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            com.bitchat.android.nostr.GeohashAliasRegistry.initialize(this)
            com.bitchat.android.nostr.GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Own relay connectivity, selected-channel subscriptions, and presence scheduling at the
        // process level so closing the Activity does not disconnect Nostr.
        // MeshUp: Internet opt-in gate (Decision 013)
        if (internetEnabled) {
            try { com.bitchat.android.nostr.NostrBackgroundRuntime.initialize(this) } catch (_: Exception) { }
        } else {
            // A persisted geohash selection must not be restored while Internet is off.
            try {
                com.bitchat.android.geohash.LocationChannelManager.getInstance(this)
                    .select(com.bitchat.android.geohash.ChannelID.Mesh)
            } catch (_: Exception) { }
        }

        // MeshUp: Internet opt-in gate (Decision 013) - react to runtime toggles from here on
        try { com.bitchat.android.meshup.settings.InternetController.startForApp(this) } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.bitchat.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.bitchat.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
