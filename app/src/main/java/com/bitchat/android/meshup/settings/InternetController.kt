package com.bitchat.android.meshup.settings

import android.app.Application
import android.util.Log
import com.bitchat.android.net.ArtiTorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Starts or stops the Internet-backed components when the master switch changes at runtime
 * (Decision 013). The initial state is applied by BitchatApplication; this only reacts to changes.
 */
class InternetController(
    private val enabled: StateFlow<Boolean>,
    private val actions: Actions,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
) {
    interface Actions {
        fun onEnabled()
        fun onDisabled()
    }

    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        var last = enabled.value
        job = scope.launch {
            enabled.collect { now ->
                if (now == last) return@collect
                last = now
                try {
                    if (now) actions.onEnabled() else actions.onDisabled()
                } catch (e: Exception) {
                    Log.w(TAG, "Internet toggle handler failed: ${e.message}")
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "InternetController"

        @Volatile
        private var instance: InternetController? = null

        /** Wires the real components. Idempotent. Call after [InternetGate.initialize]. */
        fun startForApp(app: Application) {
            val controller = instance ?: synchronized(this) {
                instance ?: InternetController(InternetGate.enabled, AppActions(app)).also { instance = it }
            }
            controller.start()
        }
    }

    /**
     * The gate flip and the fail-closed Tor route are applied synchronously on the caller's thread;
     * everything heavier runs on one serial worker so rapid toggles cannot interleave.
     */
    private class AppActions(private val app: Application) : Actions {
        private val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "internet-controller").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val serial = Mutex()

        private inline fun step(name: String, block: () -> Unit) {
            try { block() } catch (e: Throwable) { Log.w(TAG, "$name failed: ${e.message}") }
        }

        override fun onEnabled() {
            Log.i(TAG, "Internet features enabled")
            // Publish the SOCKS route (fail-closed) before anything can connect.
            step("reserveRoute") { ArtiTorManager.getInstance().reserveRouteForGate(app) }
            scope.launch {
                serial.withLock {
                    if (!InternetGate.isEnabled()) return@withLock // superseded by a later OFF
                    try { ArtiTorManager.getInstance().reconcile(app) } catch (e: Exception) { Log.w(TAG, "tor failed: ${e.message}") }
                    step("relayDirectory") { com.bitchat.android.nostr.RelayDirectory.initialize(app) }
                    step("locationNotes") { com.bitchat.android.nostr.LocationNotesInitializer.initialize(app) }
                    step("nostrRuntime") { com.bitchat.android.nostr.NostrBackgroundRuntime.initialize(app) }
                    // No-op if the runtime was just initialized; reconnects after a previous OFF.
                    step("relays") { com.bitchat.android.nostr.NostrRelayManager.getInstance(app).connect() }
                }
            }
        }

        override fun onDisabled() {
            Log.i(TAG, "Internet features disabled")
            scope.launch {
                serial.withLock {
                    if (InternetGate.isEnabled()) return@withLock // superseded by a later ON
                    step("locationNotes") { com.bitchat.android.nostr.LocationNotesManager.getInstance().stop() }
                    step("relays") { com.bitchat.android.nostr.NostrRelayManager.getInstance(app).disconnect() }
                    step("http") { com.bitchat.android.net.OkHttpProvider.cancelAll() }
                    step("apkWork") {
                        androidx.work.WorkManager.getInstance(app)
                            .cancelUniqueWork(com.bitchat.android.util.ApkDownloadWorker.WORK_NAME)
                    }
                    step("channel") {
                        com.bitchat.android.geohash.LocationChannelManager.getInstance(app)
                            .select(com.bitchat.android.geohash.ChannelID.Mesh)
                    }
                    try { ArtiTorManager.getInstance().reconcile(app) } catch (e: Exception) { Log.w(TAG, "tor failed: ${e.message}") }
                }
            }
        }
    }
}
