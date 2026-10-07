package com.bitchat.android.identity

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which persisted identity key was affected. */
enum class IdentityKeyKind { NOISE_STATIC, NOISE_SIGNING, ED25519_SIGNING }

enum class IdentityIssueReason {
    /** A key was stored but could not be read; a new key replaced it. */
    UNREADABLE,

    /** A newly generated key could not be saved; it is only valid for this session. */
    NOT_PERSISTED
}

/** [backedUp] (UNREADABLE only) says whether the old stored value was copied to a backup pref key. */
data class IdentityIssue(
    val reason: IdentityIssueReason,
    val key: IdentityKeyKind,
    val backedUp: Boolean = false
)

/**
 * Process-wide record of identity-key problems (P2-10). Pure Android/Kotlin so the shared crypto/noise/
 * identity packages (also compiled into Wear) can report into it.
 *
 * The warning survives process death: [report] writes `identity_issue_at` and the issue list to a small
 * non-identity prefs file, [acknowledge] writes `identity_issue_ack_at`, and [attach] re-hydrates
 * [issues] when `issue_at > ack_at`. [reset] (panic wipe) removes all markers.
 */
object IdentityHealth {
    private const val PREFS_NAME = "bitchat_identity_health"
    private const val KEY_AT = "identity_issue_at"
    private const val KEY_REASON = "identity_issue_reason"
    private const val KEY_LIST = "identity_issue_list"
    private const val KEY_ACK_AT = "identity_issue_ack_at"

    private val lock = Any()
    private val _issues = MutableStateFlow<List<IdentityIssue>>(emptyList())
    val issues: StateFlow<List<IdentityIssue>> = _issues.asStateFlow()
    private var prefs: SharedPreferences? = null

    /** Binds persistence (idempotent) and restores an unacknowledged persisted warning. */
    fun attach(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            attachLocked(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
        }
    }

    internal fun attach(p: SharedPreferences) = synchronized(lock) { attachLocked(p) }

    private fun attachLocked(p: SharedPreferences) {
        prefs = p
        val restored = if (p.getLong(KEY_AT, 0L) > p.getLong(KEY_ACK_AT, 0L)) {
            parse(p.getString(KEY_LIST, null))
        } else {
            emptyList()
        }
        val merged = (restored + _issues.value).distinct()
        val added = merged.size != restored.size
        _issues.value = merged
        if (added) persistLocked()
    }

    fun report(issue: IdentityIssue, context: Context? = null) {
        synchronized(lock) {
            if (context != null && prefs == null) {
                attachLocked(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
            }
            if (issue in _issues.value) return
            _issues.value = _issues.value + issue
            persistLocked()
        }
    }

    fun acknowledge() {
        synchronized(lock) {
            _issues.value = emptyList()
            prefs?.let {
                val at = it.getLong(KEY_AT, 0L)
                it.edit().putLong(KEY_ACK_AT, maxOf(System.currentTimeMillis(), at)).commit()
            }
        }
    }

    /** Panic wipe: forget the warning in memory and on disk. */
    fun reset(context: Context) {
        synchronized(lock) {
            _issues.value = emptyList()
            val p = prefs ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            p.edit().remove(KEY_AT).remove(KEY_REASON).remove(KEY_LIST).remove(KEY_ACK_AT).commit()
        }
    }

    /** Test hook: simulates a new process (memory gone, persisted markers remain). */
    internal fun simulateProcessDeath() = synchronized(lock) {
        prefs = null
        _issues.value = emptyList()
    }

    private fun persistLocked() {
        val p = prefs ?: return
        val ack = p.getLong(KEY_ACK_AT, 0L)
        val list = _issues.value
        p.edit()
            .putLong(KEY_AT, maxOf(System.currentTimeMillis(), ack + 1))
            .putString(KEY_REASON, list.lastOrNull()?.reason?.name)
            .putString(KEY_LIST, list.joinToString(",") { "${it.reason}:${it.key}:${if (it.backedUp) 1 else 0}" })
            .commit()
    }

    private fun parse(raw: String?): List<IdentityIssue> = raw.orEmpty().split(',').mapNotNull { part ->
        val f = part.split(':')
        if (f.size != 3) return@mapNotNull null
        runCatching {
            IdentityIssue(IdentityIssueReason.valueOf(f[0]), IdentityKeyKind.valueOf(f[1]), f[2] == "1")
        }.getOrNull()
    }
}
