package com.bitchat.android.meshup.shell

import androidx.lifecycle.ViewModel
import com.bitchat.android.meshup.profile.DisplayNameValidator
import com.bitchat.android.meshup.profile.ProfileManager
import com.bitchat.android.meshup.settings.InternetGate
import com.bitchat.android.identity.IdentityHealth
import com.bitchat.android.identity.IdentityIssue
import com.bitchat.android.identity.IdentityIssueReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

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

/** What the identity banner should say; UNREADABLE wins when both kinds were reported. */
enum class IdentityBannerKind { UNREADABLE, NOT_PERSISTED }

/**
 * Identity-health banner and the typed-`reset` confirmation (Decision 015 D7). The banner never blocks;
 * [confirmReset] goes through [reset], which is the existing panic wipe.
 */
class IdentityViewModel(
    issues: StateFlow<List<IdentityIssue>> = IdentityHealth.issues,
    private val acknowledgeIssues: () -> Unit = IdentityHealth::acknowledge,
    private val reset: () -> Unit
) : ViewModel() {
    val banner: Flow<IdentityBannerKind?> = issues.map { list ->
        when {
            list.any { it.reason == IdentityIssueReason.UNREADABLE } -> IdentityBannerKind.UNREADABLE
            list.isNotEmpty() -> IdentityBannerKind.NOT_PERSISTED
            else -> null
        }
    }

    /** True when at least one UNREADABLE issue kept a copy of the old stored value. */
    val backupKept: Flow<Boolean> = issues.map { list ->
        list.any { it.reason == IdentityIssueReason.UNREADABLE && it.backedUp }
    }

    private val _resetText = MutableStateFlow("")
    val resetText: StateFlow<String> = _resetText.asStateFlow()

    fun onResetTextChange(text: String) { _resetText.value = text }

    fun canConfirmReset(text: String = _resetText.value): Boolean = text.trim().equals("reset", ignoreCase = true)

    fun acknowledge() = acknowledgeIssues()

    /** Runs the wipe only when the typed word matches. Returns whether it ran. */
    fun confirmReset(): Boolean {
        if (!canConfirmReset()) return false
        _resetText.value = ""
        reset()
        acknowledgeIssues()
        return true
    }

    fun clearResetText() { _resetText.value = "" }
}
