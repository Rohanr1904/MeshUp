package com.bitchat.android.mesh

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Runs suspend work off the caller's thread, FIFO within a key and concurrent across keys.
 *
 * Used to keep slow receive side effects (file save, voice hand-off, durable DB admission,
 * notifications, delivery ACK) off the PacketProcessor stripe, while user-visible messages from
 * one sender are still admitted in arrival order.
 *
 * Memory is bounded: when a key already has [maxPendingPerKey] unfinished items, [submit]
 * suspends until that lane drains. That is back-pressure, not a drop, because the delivery ACK
 * for a message is only sent after its side effects run.
 */
internal class SerialLanes(
    private val scope: CoroutineScope,
    private val maxPendingPerKey: Int = 16,
    private val slowThresholdMs: Long = 2_000L,
    private val tag: String = "SerialLanes"
) {
    private class Lane {
        var tail: Job? = null
        var pending = 0
    }

    private val lanes = HashMap<String, Lane>()

    /** Enqueues [work] behind earlier work for [key]. [label] is logged when work is slow or fails. */
    suspend fun submit(key: String, label: String, work: suspend () -> Unit) {
        while (true) {
            val full = synchronized(lanes) {
                lanes[key]?.takeIf { it.pending >= maxPendingPerKey }?.tail
            } ?: break
            full.join()
            yield()
        }
        synchronized(lanes) {
            val lane = lanes.getOrPut(key) { Lane() }
            val previous = lane.tail
            val job = scope.launch(start = CoroutineStart.LAZY) {
                previous?.join()
                val started = System.nanoTime()
                try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(tag, "Receive side effect failed ($label): ${e.message}")
                } finally {
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000L
                    if (elapsedMs > slowThresholdMs) {
                        Log.w(tag, "Slow receive side effect ($label) took ${elapsedMs}ms")
                    }
                }
            }
            lane.tail = job
            lane.pending++
            job.invokeOnCompletion {
                synchronized(lanes) {
                    lane.pending--
                    if (lane.pending <= 0 && lanes[key] === lane) lanes.remove(key)
                }
            }
            job.start()
        }
    }

    /** Suspends until every lane is empty. For tests and orderly shutdown. */
    suspend fun awaitIdle() {
        while (true) {
            val tails = synchronized(lanes) { lanes.values.mapNotNull { it.tail } }
            if (tails.isEmpty()) return
            tails.forEach { it.join() }
            yield()
        }
    }

    internal val laneCount: Int get() = synchronized(lanes) { lanes.size }
}
