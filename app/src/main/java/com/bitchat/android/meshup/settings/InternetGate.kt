package com.bitchat.android.meshup.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide owner of the Internet master switch (Decision 013). Legacy code with no ViewModel reads
 * [isEnabled]; UI (PR-4) toggles it with [setEnabled]. Fail-closed: until [initialize] runs the gate is OFF.
 *
 * There is exactly one [NetworkSettings] instance per process (see [settings]); never create another one
 * to write the switch, or the gate and [InternetController] would not see the change.
 * [isEnabled] reads the StateFlow value directly, so a write is visible to every thread immediately.
 */
object InternetGate {
    private val closed: StateFlow<Boolean> = MutableStateFlow(false)

    @Volatile
    private var settingsRef: NetworkSettings? = null

    @Volatile
    private var source: StateFlow<Boolean> = closed

    /** Observable state of the gate. */
    val enabled: StateFlow<Boolean> get() = source

    /** The process-wide settings object. Only valid after [initialize]. */
    val settings: NetworkSettings
        get() = checkNotNull(settingsRef) { "InternetGate.initialize must run first" }

    fun isEnabled(): Boolean = source.value

    /** Public toggle API. Flips the gate synchronously and notifies [InternetController]. */
    fun setEnabled(enabled: Boolean) = settings.setInternetEnabled(enabled)

    /** Idempotent: later calls return the same settings and keep the same flow. */
    fun initialize(context: Context): NetworkSettings {
        settingsRef?.let { return it }
        return synchronized(this) {
            settingsRef ?: NetworkSettings.create(context).also {
                settingsRef = it
                source = it.internetEnabled
            }
        }
    }

    internal fun initialize(flow: StateFlow<Boolean>) {
        settingsRef = null
        source = flow
    }

    /** Test hook: returns to the fail-closed default. */
    internal fun resetForTesting() {
        settingsRef = null
        source = closed
    }
}
