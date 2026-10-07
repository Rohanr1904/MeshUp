package com.bitchat.android.meshup.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Master switch for internet-backed features (Tor, Nostr relays, location channels).
 * Default is OFF. Nothing reads this yet; wiring happens in a later PR.
 */
class NetworkSettings(private val prefs: SharedPreferences) {
    companion object {
        const val PREFS_NAME = "meshup_settings"
        const val KEY_INTERNET_ENABLED = "internet_enabled"

        fun create(context: Context): NetworkSettings = NetworkSettings(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    }

    private val _internetEnabled = MutableStateFlow(prefs.getBoolean(KEY_INTERNET_ENABLED, false))
    val internetEnabled: StateFlow<Boolean> = _internetEnabled.asStateFlow()

    fun setInternetEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INTERNET_ENABLED, enabled).apply()
        _internetEnabled.value = enabled
    }
}
