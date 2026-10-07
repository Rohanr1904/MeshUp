package com.bitchat.android.meshup.shell

import androidx.lifecycle.ViewModel
import com.bitchat.android.meshup.profile.DisplayNameValidator
import com.bitchat.android.meshup.profile.ProfileManager
import com.bitchat.android.meshup.settings.InternetGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NameEditState(
    val text: String,
    val error: DisplayNameValidator.Reason?,
    val saved: Boolean = false
) {
    val canSave: Boolean get() = error == null
}

/**
 * Live-validated display-name editor, shared by the onboarding name step ([confirm] = true, also marks
 * the name confirmed) and the Profile edit action.
 */
class NameEditViewModel(
    private val profile: ProfileManager,
    private val confirm: Boolean,
    initial: String = profile.displayName.value
) : ViewModel() {
    private val _state = MutableStateFlow(NameEditState(initial, errorFor(initial)))
    val state: StateFlow<NameEditState> = _state.asStateFlow()

    fun onTextChange(text: String) {
        _state.value = NameEditState(text, errorFor(text))
    }

    /** Returns true if saved. Invalid input is never written. */
    fun save(): Boolean {
        val text = _state.value.text
        val result = if (confirm) profile.confirmName(text) else profile.saveName(text)
        return when (result) {
            is DisplayNameValidator.Result.Valid -> {
                _state.value = NameEditState(result.normalized, null, saved = true)
                true
            }
            is DisplayNameValidator.Result.Invalid -> {
                _state.value = NameEditState(text, result.reason)
                false
            }
        }
    }

    private fun errorFor(text: String): DisplayNameValidator.Reason? =
        (DisplayNameValidator.validate(text) as? DisplayNameValidator.Result.Invalid)?.reason
}

/** Internet master switch (Decision 013). Defaults bind to the process-wide [InternetGate]. */
class SettingsViewModel(
    val internetEnabled: StateFlow<Boolean> = InternetGate.enabled,
    private val setEnabled: (Boolean) -> Unit = InternetGate::setEnabled,
    private val usedThisProcess: () -> Boolean = { InternetGate.usedThisProcess }
) : ViewModel() {
    fun setInternetEnabled(enabled: Boolean) = setEnabled(enabled)

    /** Internet is OFF but was ON earlier in this process: a restart fully closes Tor's connections. */
    fun restartRecommended(internetOn: Boolean): Boolean = !internetOn && usedThisProcess()
}
