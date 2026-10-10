package com.bitchat.android.mesh

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Per-link serial send queue for GATT writes/notifications.
 *
 * Android permits only one outstanding GATT operation per link, so each link sends its head
 * request and waits for the completion callback before starting the next one.
 *
 * MeshUp: some Android BLE stacks occasionally never deliver the completion callback
 * (onCharacteristicWrite / onNotificationSent), typically during bursts such as voice-note
 * fragments. Without a bound, the link stayed "in flight" forever and every later packet to that
 * peer queued behind it (field bug: messages stop after a voice note). Two guards:
 * - a completion that does not arrive within [completionTimeoutMs] is treated as done, so the
 *   queue keeps moving (lost packets are covered by the outbox, fragment re-send and dedup);
 * - if the stack keeps refusing to start sends for [stallAfterMs] (for example the framework's
 *   own busy flag is stuck after a lost callback), the link is reported via [onLinkStalled] so
 *   the caller can drop and re-establish the connection.
 */
internal class GattSendQueue<K : Any, R : Any>(
    private val scope: CoroutineScope,
    private val sizeOf: (R) -> Int,
    private val start: (K, R) -> Boolean,
    private val maxPendingPerLink: Int,
    private val maxPendingBytesPerLink: Int,
    private val retryDelayMs: Long,
    private val maxCallbackRetries: Int,
    private val completionTimeoutMs: Long,
    private val stallAfterMs: Long,
    private val now: () -> Long,
    private val onLinkStalled: (K, R) -> Unit,
    private val tag: String
) {
    private class Pending<R>(val request: R, var callbackFailures: Int = 0)

    private class LinkState<R> {
        val pending = ArrayDeque<Pending<R>>()
        var pendingBytes = 0
        var inFlight = false
        /** Identifies one start attempt; a timer only completes the attempt it was armed for. */
        var attempt = NO_ATTEMPT
        var retryScheduled = false
        var rejectingSince = NOT_REJECTING
    }

    private val lock = Any()
    private val states = mutableMapOf<K, LinkState<R>>()
    private var nextAttempt = 0L

    /** Completions forced by the timeout (debug output and tests). */
    @Volatile
    var timedOutCompletions = 0
        private set

    /** Links dropped because the stack kept refusing sends (debug output and tests). */
    @Volatile
    var stalledLinks = 0
        private set

    /** Queues [request]; returns false when the link's queue is full. */
    fun enqueue(key: K, request: R, describe: () -> String): Boolean {
        val startNow = synchronized(lock) {
            val state = states.getOrPut(key) { LinkState() }
            val size = sizeOf(request)
            if (state.pending.size >= maxPendingPerLink || state.pendingBytes + size > maxPendingBytesPerLink) {
                Log.w(tag, "BLE send queue full for ${describe()}; rejecting $size bytes")
                return false
            }
            state.pending.addLast(Pending(request))
            state.pendingBytes += size
            if (!state.inFlight && !state.retryScheduled) {
                state.inFlight = true
                true
            } else {
                false
            }
        }
        if (startNow) startHead(key)
        return true
    }

    /**
     * Completion callback from the BLE stack. Ignored when nothing is in flight on [key]
     * (a late callback after a timeout, or one arriving while a retry is pending).
     */
    fun complete(key: K, success: Boolean) = completeInternal(key, success, expectedAttempt = null)

    fun removeLinks(predicate: (K) -> Boolean) {
        synchronized(lock) { states.keys.removeAll(predicate) }
    }

    private fun startHead(key: K) {
        val (head, attempt) = synchronized(lock) {
            val state = states[key] ?: return
            val head = state.pending.firstOrNull() ?: return
            state.attempt = nextAttempt++
            head to state.attempt
        }
        val accepted = try {
            start(key, head.request)
        } catch (error: Exception) {
            Log.w(tag, "BLE send failed to start: ${error.message}")
            false
        }
        if (accepted) {
            synchronized(lock) { states[key]?.rejectingSince = NOT_REJECTING }
            scope.launch {
                delay(completionTimeoutMs)
                completeInternal(key, success = true, expectedAttempt = attempt)
            }
        } else {
            rejectStart(key)
        }
    }

    private fun rejectStart(key: K) {
        var stalled: R? = null
        val schedule = synchronized(lock) {
            val state = states[key] ?: return
            state.inFlight = false
            state.attempt = NO_ATTEMPT
            val t = now()
            if (state.rejectingSince == NOT_REJECTING) state.rejectingSince = t
            if (t - state.rejectingSince >= stallAfterMs && state.pending.isNotEmpty()) {
                stalled = state.pending.first().request
                states.remove(key)
                stalledLinks++
                return@synchronized false
            }
            if (state.retryScheduled || state.pending.isEmpty()) false else {
                state.retryScheduled = true
                true
            }
        }
        stalled?.let {
            Log.w(tag, "BLE link refused sends for ${stallAfterMs} ms; dropping link")
            onLinkStalled(key, it)
            return
        }
        if (schedule) {
            scope.launch {
                delay(retryDelayMs)
                val retry = synchronized(lock) {
                    val state = states[key] ?: return@synchronized false
                    state.retryScheduled = false
                    if (!state.inFlight && state.pending.isNotEmpty()) {
                        state.inFlight = true
                        true
                    } else false
                }
                if (retry) startHead(key)
            }
        }
    }

    /**
     * [expectedAttempt] is set only by the timeout: it completes the request only if that exact
     * start attempt is still the one in flight, so a stale timer never completes a later request
     * or a retried attempt.
     */
    private fun completeInternal(key: K, success: Boolean, expectedAttempt: Long?) {
        var retry = false
        val startNext = synchronized(lock) {
            val state = states[key] ?: return
            if (!state.inFlight || state.attempt == NO_ATTEMPT) return
            if (expectedAttempt != null && state.attempt != expectedAttempt) return
            val head = state.pending.firstOrNull() ?: run {
                states.remove(key)
                return
            }
            if (expectedAttempt != null) {
                timedOutCompletions++
                Log.w(tag, "BLE completion callback missing after ${completionTimeoutMs} ms; continuing")
            }
            state.inFlight = false
            state.attempt = NO_ATTEMPT
            if (!success && head.callbackFailures < maxCallbackRetries) {
                head.callbackFailures++
                retry = true
                false
            } else {
                if (!success) Log.w(tag, "BLE send failed after retries")
                state.pending.removeFirst()
                state.pendingBytes -= sizeOf(head.request)
                if (state.pending.isEmpty()) {
                    states.remove(key)
                    false
                } else {
                    state.inFlight = true
                    true
                }
            }
        }
        if (retry) rejectStart(key) else if (startNext) startHead(key)
    }

    private companion object {
        const val NO_ATTEMPT = -1L
        const val NOT_REJECTING = -1L
    }
}
