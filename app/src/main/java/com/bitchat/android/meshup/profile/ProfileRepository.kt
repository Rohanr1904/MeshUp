package com.bitchat.android.meshup.profile

import android.content.Context
import android.content.SharedPreferences
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Minimal storage seam over the legacy nickname persistence (bitchat_prefs/nickname). */
interface DisplayNameStore {
    fun load(): String
    fun save(name: String)
}

/** [DisplayNameStore] backed by the existing [DataManager]; no new key is introduced. */
class DataManagerDisplayNameStore(private val dataManager: DataManager) : DisplayNameStore {
    override fun load(): String = dataManager.loadNickname()
    override fun save(name: String) = dataManager.saveNickname(name)
}

/**
 * Owns display-name validation and the "name confirmed" onboarding flag.
 * The name itself stays in the legacy nickname pref so the mesh announce path keeps working.
 */
class ProfileRepository(
    private val store: DisplayNameStore,
    private val settingsPrefs: SharedPreferences
) {
    companion object {
        const val PREFS_NAME = "meshup_settings"
        const val KEY_NAME_CONFIRMED = "profile_name_confirmed"

        fun create(context: Context): ProfileRepository {
            val app = context.applicationContext
            return ProfileRepository(
                DataManagerDisplayNameStore(DataManager(app)),
                app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            )
        }
    }

    private val _displayName = MutableStateFlow(store.load())
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    /** Validates [raw]; persists and publishes the trimmed name only when valid. */
    fun setDisplayName(raw: String): DisplayNameValidator.Result {
        val result = DisplayNameValidator.validate(raw)
        if (result is DisplayNameValidator.Result.Valid) {
            store.save(result.normalized)
            _displayName.value = result.normalized
        }
        return result
    }

    fun isNameConfirmed(): Boolean = settingsPrefs.getBoolean(KEY_NAME_CONFIRMED, false)

    fun markNameConfirmed() {
        settingsPrefs.edit().putBoolean(KEY_NAME_CONFIRMED, true).apply()
    }
}
