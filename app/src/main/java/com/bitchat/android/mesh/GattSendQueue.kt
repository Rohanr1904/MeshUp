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
 * fragments. Without a bound, that link stayed "in flight" forever and every later packet to
 * that peer queued behind it (field bug: messages stop after a voice note). A completion that
 * does not arrive within [completionTimeoutMs] is treated as done so the queue keeps moving;
 * lost packets are covered by the existing resend paths (outbox, fragment re-send, dedup).
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
    private val tag: String
) {
    private class Pending<R>(val request: R, val id: Long, var callbackFailures: Int = 0)

    private class LinkState<R> {
        val pending = ArrayDeque<Pending<R>>()
        var pendingBytes = 0
        var inFlight = false
        var inFlightId = NO_ID
        var retryScheduled = false
    }

    private val lock = Any()
    private val states = mutableMapOf<K, LinkState<R>>()
    private var nextId = 0L

    /** Number of completions that were forced by the timeout (for debug output and tests). */
    @Volatile
    var timedOutCompletions = 0
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
            state.pending.addLast(Pending(request, nextId++))
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

    /** Completion callback from the BLE stack for the request currently in flight on [key]. */
    fun complete(key: K, success: Boolean) = completeInternal(key, success, expectedId = null)

    fun removeLinks(predicate: (K) -> Boolean) {
        synchronized(lock) { states.keys.removeAll(predicate) }
    }

    private fun startHead(key: K) {
        val head = synchronized(lock) {
            val state = states[key] ?: return
            val head = state.pending.firstOrNull() ?: return
            state.inFlightId = head.id
            head
        }
        val accepted = try {
            start(key, head.request)
        } catch (error: Exception) {
            Log.w(tag, "BLE send failed to start: ${error.message}")
            false
        }
        if (accepted) {
            scope.launch {
                delay(completionTimeoutMs)
                completeInternal(key, success = true, expectedId = head.id)
            }
        } else {
            rejectStart(key)
        }
    }

    private fun rejectStart(key: K) {
        val schedule = synchronized(lock) {
            val state = states[key] ?: return
            state.inFlight = false
            state.inFlightId = NO_ID
            if (state.retryScheduled || state.pending.isEmpty()) false else {
                state.retryScheduled = true
                true
            }
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
     * [expectedId] is set only by the timeout: it completes the request only if that exact
     * request is still the one in flight, so a stale timer never completes a later request.
     */
    private fun completeInternal(key: K, success: Boolean, expectedId: Long?) {
        var retry = false
        val startNext = synchronized(lock) {
            val state = states[key] ?: return
            if (expectedId != null && (!state.inFlight || state.inFlightId != expectedId)) return
            val head = state.pending.firstOrNull() ?: run {
                states.remove(key)
                return
            }
            if (expectedId != null) {
                timedOutCompletions++
                Log.w(tag, "BLE completion callback missing after ${completionTimeoutMs} ms; continuing")
            }
            state.inFlight = false
            state.inFlightId = NO_ID
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
        const val NO_ID = -1L
    }
}
