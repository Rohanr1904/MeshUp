package com.bitchat.android.meshup.profile

import com.bitchat.android.meshup.service.LegacyChatSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shell-layer facade for the user's own profile. [ProfileRepository] validates and owns the
 * "name confirmed" flag; [LegacyChatSource.setNickname] is the legacy path that updates the
 * ChatViewModel nickname state (header), persists it and re-announces on the mesh.
 */
class ProfileManager(
    private val repo: ProfileRepository,
    private val source: LegacyChatSource
) {
    val displayName: StateFlow<String> get() = source.nickname

    private val _nameConfirmed = MutableStateFlow(repo.isNameConfirmed())
    val nameConfirmed: StateFlow<Boolean> = _nameConfirmed.asStateFlow()

    /** Validates and saves through both owners. Nothing is written when invalid. */
    fun saveName(raw: String): DisplayNameValidator.Result {
        val result = repo.setDisplayName(raw)
        if (result is DisplayNameValidator.Result.Valid) source.setNickname(result.normalized)
        return result
    }

    /** Saves the name and, when valid, marks the name step as done. */
    fun confirmName(raw: String): DisplayNameValidator.Result {
        val result = saveName(raw)
        if (result is DisplayNameValidator.Result.Valid) {
            repo.markNameConfirmed()
            _nameConfirmed.value = true
        }
        return result
    }

    /** First 16 hex chars of our identity fingerprint in groups of four; empty if unavailable. */
    fun shortFingerprint(): String = formatFingerprint(runCatching { source.myFingerprint() }.getOrDefault(""))

    /** Deliberate identity reset: delegates to the existing panic wipe (no separate mechanism). */
    fun resetIdentity() = source.panicClearAllData()

    companion object {
        fun formatFingerprint(full: String): String =
            full.filter { it.isLetterOrDigit() }.take(16).chunked(4).joinToString(" ").uppercase()
    }
}
