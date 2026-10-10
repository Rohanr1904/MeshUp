package com.bitchat.android.mesh

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GattSendQueueTest {
    private val scope = TestScope()
    private val started = mutableListOf<String>()
    private val stalled = mutableListOf<Pair<String, String>>()
    private var accept = true

    private fun queue(timeoutMs: Long = 1_500L, maxPending: Int = 256, stallMs: Long = 3_000L) =
        GattSendQueue<String, String>(
            scope = scope,
            sizeOf = { it.length },
            start = { _, request -> started += request; accept },
            maxPendingPerLink = maxPending,
            maxPendingBytesPerLink = 1_048_576,
            retryDelayMs = 15L,
            maxCallbackRetries = 3,
            completionTimeoutMs = timeoutMs,
            stallAfterMs = stallMs,
            now = { scope.testScheduler.currentTime },
            onLinkStalled = { key, head -> stalled += key to head },
            tag = "test"
        )

    private fun advance(ms: Long) {
        scope.advanceTimeBy(ms); scope.runCurrent()
    }

    @Test fun sendsOneAtATimeAndAdvancesOnCompletion() {
        val q = queue()
        q.enqueue("link", "a") { "k" }
        q.enqueue("link", "b") { "k" }
        assertEquals(listOf("a"), started)

        q.complete("link", success = true)
        assertEquals(listOf("a", "b"), started)
        assertEquals(0, q.timedOutCompletions)
    }

    @Test fun missingCompletionCallbackDoesNotStallTheLinkForever() {
        // Field bug: the BLE stack never reported completion for one write; every later
        // packet to that peer waited behind it.
        val q = queue(timeoutMs = 1_500L)
        q.enqueue("link", "fragment-1") { "k" }
        q.enqueue("link", "fragment-2") { "k" }
        q.enqueue("link", "text-after-voice") { "k" }
        assertEquals(listOf("fragment-1"), started)

        advance(1_499L)
        assertEquals(listOf("fragment-1"), started)

        advance(2L)
        assertEquals(listOf("fragment-1", "fragment-2"), started)

        advance(1_501L)
        assertEquals(listOf("fragment-1", "fragment-2", "text-after-voice"), started)
        assertEquals(2, q.timedOutCompletions)
    }

    @Test fun staleTimeoutNeverCompletesALaterRequest() {
        val q = queue(timeoutMs = 1_500L)
        q.enqueue("link", "a") { "k" }
        q.enqueue("link", "b") { "k" }
        q.enqueue("link", "c") { "k" }

        advance(1_000L)
        q.complete("link", success = true) // real callback for "a"; "b" starts at t=1000
        assertEquals(listOf("a", "b"), started)

        advance(600L) // a's timer (t=1500) must not complete "b"
        assertEquals(listOf("a", "b"), started)
        assertEquals(0, q.timedOutCompletions)

        advance(1_000L) // b's own timer (t=2500)
        assertEquals(listOf("a", "b", "c"), started)
        assertEquals(1, q.timedOutCompletions)
    }

    @Test fun firstAttemptTimerDoesNotCompleteARetriedAttempt() {
        val q = queue(timeoutMs = 1_500L)
        q.enqueue("link", "a") { "k" }
        q.enqueue("link", "b") { "k" }
        advance(1_000L)
        q.complete("link", success = false) // callback failure -> "a" retried at t=1015
        advance(15L)
        assertEquals(listOf("a", "a"), started)

        advance(500L) // first attempt's timer (t=1500) must be ignored
        assertEquals(listOf("a", "a"), started)
        assertEquals(0, q.timedOutCompletions)
    }

    @Test fun callbackWhileNothingInFlightIsIgnored() {
        val q = queue()
        accept = false
        q.enqueue("link", "a") { "k" }
        q.enqueue("link", "b") { "k" }
        // Retry pending (start was rejected): a stray callback must not pop "a" unsent.
        q.complete("link", success = true)
        accept = true
        advance(16L)
        assertEquals(listOf("a", "a"), started)
        q.complete("link", success = true)
        assertEquals("b", started.last())
    }

    @Test fun lateCallbackAfterTimeoutDoesNotSkipAnUnsentRequest() {
        val q = queue(timeoutMs = 1_500L)
        q.enqueue("link", "a") { "k" }
        q.enqueue("link", "b") { "k" }
        q.enqueue("link", "c") { "k" }
        advance(1_501L) // "a" times out, "b" starts
        accept = false // controller still busy with "a"
        q.complete("link", success = true) // late callback for "a" completes "b" early; "c" rejected
        q.complete("link", success = true) // real callback for "b": nothing in flight -> ignored
        accept = true
        advance(16L)
        // "c" is still sent: nothing was silently dropped.
        assertEquals("c", started.last())
    }

    @Test fun linkThatRefusesSendsIsReportedStalledAndDropped() {
        val q = queue(stallMs = 3_000L)
        accept = false
        q.enqueue("link", "a") { "k" }
        advance(2_900L)
        assertTrue(stalled.isEmpty())
        advance(200L)
        assertEquals(listOf("link" to "a"), stalled)
        assertEquals(1, q.stalledLinks)

        // Retrying stops once the link is dropped.
        val attempts = started.size
        advance(5_000L)
        assertEquals(attempts, started.size)

        // A new connection starts clean.
        accept = true
        q.enqueue("link", "fresh") { "k" }
        assertEquals("fresh", started.last())
    }

    @Test fun linksAreIndependent() {
        val q = queue()
        q.enqueue("stuck", "s1") { "k" }
        q.enqueue("stuck", "s2") { "k" }
        q.enqueue("healthy", "h1") { "k" }
        q.enqueue("healthy", "h2") { "k" }
        q.complete("healthy", success = true)
        assertEquals(listOf("s1", "h1", "h2"), started)
    }

    @Test fun rejectedStartIsRetriedAndFailedCallbackRetriesThenDrops() {
        val q = queue()
        accept = false
        q.enqueue("link", "a") { "k" }
        assertEquals(listOf("a"), started)
        accept = true
        advance(16L)
        assertEquals(listOf("a", "a"), started)

        repeat(3) {
            q.complete("link", success = false)
            advance(16L)
        }
        assertEquals(5, started.size)
        q.complete("link", success = false) // 4th failure: dropped
        q.enqueue("link", "b") { "k" }
        assertEquals("b", started.last())
    }

    @Test fun fullQueueRejectsAndRemovedLinkStopsSending() {
        val q = queue(maxPending = 2)
        assertTrue(q.enqueue("link", "a") { "k" })
        assertTrue(q.enqueue("link", "b") { "k" })
        assertFalse(q.enqueue("link", "c") { "k" })

        q.removeLinks { it == "link" }
        q.complete("link", success = true)
        advance(5_000L) // the pending timer fires after removal and must do nothing
        assertEquals(listOf("a"), started)
        assertEquals(0, q.timedOutCompletions)
    }
}
