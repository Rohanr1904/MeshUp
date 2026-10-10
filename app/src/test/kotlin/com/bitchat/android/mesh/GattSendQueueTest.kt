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
    private var accept = true

    private fun queue(timeoutMs: Long = 1_500L, maxPending: Int = 256) = GattSendQueue<String, String>(
        scope = scope,
        sizeOf = { it.length },
        start = { _, request -> started += request; accept },
        maxPendingPerLink = maxPending,
        maxPendingBytesPerLink = 1_048_576,
        retryDelayMs = 15L,
        maxCallbackRetries = 3,
        completionTimeoutMs = timeoutMs,
        tag = "test"
    )

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

        scope.advanceTimeBy(1_499L); scope.runCurrent()
        assertEquals(listOf("fragment-1"), started)

        scope.advanceTimeBy(2L); scope.runCurrent()
        assertEquals(listOf("fragment-1", "fragment-2"), started)

        scope.advanceTimeBy(1_501L); scope.runCurrent()
        assertEquals(listOf("fragment-1", "fragment-2", "text-after-voice"), started)
        assertEquals(2, q.timedOutCompletions)
    }

    @Test fun staleTimeoutNeverCompletesALaterRequest() {
        val q = queue(timeoutMs = 1_500L)
        q.enqueue("link", "a") { "k" }
        q.enqueue("link", "b") { "k" }
        q.enqueue("link", "c") { "k" }

        scope.advanceTimeBy(1_000L); scope.runCurrent()
        q.complete("link", success = true) // real callback for "a"; "b" starts at t=1000
        assertEquals(listOf("a", "b"), started)

        // a's timer fires at t=1500 and must not complete "b" (whose own timer is t=2500).
        scope.advanceTimeBy(600L); scope.runCurrent()
        assertEquals(listOf("a", "b"), started)
        assertEquals(0, q.timedOutCompletions)

        scope.advanceTimeBy(1_000L); scope.runCurrent()
        assertEquals(listOf("a", "b", "c"), started)
        assertEquals(1, q.timedOutCompletions)
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
        scope.advanceTimeBy(16L); scope.runCurrent()
        assertEquals(listOf("a", "a"), started)

        repeat(3) {
            q.complete("link", success = false)
            scope.advanceTimeBy(16L); scope.runCurrent()
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
        scope.advanceTimeBy(5_000L); scope.runCurrent()
        assertEquals(listOf("a"), started)
    }
}
