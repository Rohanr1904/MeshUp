package com.bitchat.android.mesh

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs suspend work off the caller's thread, FIFO within a key and concurrent across keys.
 *
 * Used to keep slow receive side effects (file save, voice hand-off, durable DB admission,
 * notifications, delivery ACK) off the PacketProcessor stripe, while user-visible messages from
 * one sender are still admitted in arrival order.
 *
 * Memory is bounded and the caller is never held for long: when a key already has
 * [maxPendingPerKey] unfinished items, [submit] waits at most [fullWaitMs] for room and then
 * drops the work. A lane that already timed out drops further work at once until it has room,
 * so a hung lane cannot stall the caller (the shared stripe) packet after packet. Dropping is
 * safe for receive work because its delivery ACK is only sent from inside the work: the sender's
 * outbox resends and receive-side dedup absorbs the copy.
 */
internal class SerialLanes(
    private val scope: CoroutineScope,
    private val maxPendingPerKey: Int = 16,
    private val slowThresholdMs: Long = 2_000L,
    private val tag: String = "SerialLanes",
    private val fullWaitMs: Long = 100L
) {
    private class Lane {
        /** Unfinished jobs, oldest first; the last one is the tail new work queues behind. */
        val jobs = ArrayDeque<Job>()
        /** Set when a submit timed out on this full lane; cleared once it has room again. */
        var saturated = false
    }

    private val lanes = HashMap<String, Lane>()
    private val dropped = AtomicLong()

    /**
     * Enqueues [work] behind earlier work for [key]. [label] is logged when work is slow, fails or
     * is dropped. Returns false when the lane stayed full and [work] was dropped.
     */
    suspend fun submit(key: String, label: String, work: suspend () -> Unit): Boolean {
        if (!hasRoom(key)) {
            val skipWait = synchronized(lanes) { lanes[key]?.saturated == true }
            val gotRoom = !skipWait && withTimeoutOrNull(fullWaitMs) {
                while (!hasRoom(key)) delay(FULL_POLL_MS)
                true
            } == true
            if (!gotRoom) {
                synchronized(lanes) { lanes[key]?.saturated = true }
                val count = dropped.incrementAndGet()
                Log.w(tag, "Receive lane full; dropped $label (dropped total=$count)")
                return false
            }
        }
        synchronized(lanes) {
            val lane = lanes.getOrPut(key) { Lane() }
            val previous = lane.jobs.lastOrNull()
            val job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                val started = System.nanoTime()
                try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Class name only: messages may carry sender-supplied text (e.g. file names).
                    Log.e(tag, "Receive side effect failed ($label): ${e.javaClass.simpleName}")
                } finally {
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                    if (elapsedMs > slowThresholdMs) {
                        Log.w(tag, "Slow receive side effect ($label) took ${elapsedMs}ms")
                    }
                }
            }
            lane.jobs.addLast(job)
            job.invokeOnCompletion {
                synchronized(lanes) {
                    lane.jobs.remove(job)
                    if (lane.jobs.size < maxPendingPerKey) lane.saturated = false
                    if (lane.jobs.isEmpty() && lanes[key] === lane) lanes.remove(key)
                }
            }
            job.start()
        }
        return true
    }

    private fun hasRoom(key: String): Boolean = synchronized(lanes) {
        (lanes[key]?.jobs?.size ?: 0) < maxPendingPerKey
    }

    /**
     * Cancels all queued work and forgets every lane. Work already inside blocking code keeps
     * running until it returns, so callers must also guard their side effects themselves.
     */
    fun cancelAll() {
        val jobs = synchronized(lanes) {
            val all = lanes.values.flatMap { it.jobs.toList() }
            lanes.clear()
            all
        }
        jobs.forEach { it.cancel() }
    }

    /** Suspends until every lane is empty. For tests and orderly shutdown. */
    suspend fun awaitIdle() {
        while (true) {
            val tails = synchronized(lanes) { lanes.values.mapNotNull { it.jobs.lastOrNull() } }
            if (tails.isEmpty()) return
            tails.forEach { it.join() }
            yield()
        }
    }

    internal val laneCount: Int get() = synchronized(lanes) { lanes.size }

    internal val droppedCount: Long get() = dropped.get()

    private companion object {
        const val FULL_POLL_MS = 10L
    }
}
