package com.bitchat.android.mesh

import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class SerialLanesTest {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `same key runs in order and survives failures`() = runBlocking {
        val lanes = SerialLanes(scope)
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        repeat(20) { i ->
            lanes.submit("a", "t") {
                if (i == 5) error("boom")
                seen += i
            }
        }
        withTimeout(5_000) { lanes.awaitIdle() }
        assertEquals((0 until 20).filter { it != 5 }, seen.toList())
        assertEquals(0, lanes.laneCount)
    }

    @Test
    fun `blocked key does not block another key`() = runBlocking {
        val lanes = SerialLanes(scope)
        val gate = CompletableDeferred<Unit>()
        val otherRan = CompletableDeferred<Unit>()
        lanes.submit("a", "t") { gate.await() }
        lanes.submit("b", "t") { otherRan.complete(Unit) }
        withTimeout(2_000) { otherRan.await() }
        gate.complete(Unit)
        withTimeout(5_000) { lanes.awaitIdle() }
    }

    @Test
    fun `full lane drops after bounded wait`() = runBlocking {
        val lanes = SerialLanes(scope, maxPendingPerKey = 2, fullWaitMs = 100)
        val gate = CompletableDeferred<Unit>()
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        assertTrue(lanes.submit("a", "t") { gate.await(); seen += 0 })
        assertTrue(lanes.submit("a", "t") { seen += 1 })

        var t = System.nanoTime()
        assertFalse(lanes.submit("a", "t") { seen += 2 })
        assertTrue((System.nanoTime() - t) / 1_000_000L < 1_000L)
        // A saturated lane drops at once instead of waiting again.
        t = System.nanoTime()
        assertFalse(lanes.submit("a", "t") { seen += 3 })
        assertTrue((System.nanoTime() - t) / 1_000_000L < 50L)
        // Other keys are unaffected.
        assertTrue(lanes.submit("b", "t") { seen += 9 })
        assertEquals(2L, lanes.droppedCount)

        gate.complete(Unit)
        withTimeout(5_000) { lanes.awaitIdle() }
        assertTrue(lanes.submit("a", "t") { seen += 4 })
        withTimeout(5_000) { lanes.awaitIdle() }
        assertEquals(listOf(0, 1, 4), seen.filter { it != 9 })
    }

    @Test
    fun `cancelAll stops queued work`() = runBlocking {
        val lanes = SerialLanes(scope)
        val gate = CompletableDeferred<Unit>()
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        lanes.submit("a", "t") { gate.await(); seen += 0 }
        lanes.submit("a", "t") { seen += 1 }
        lanes.cancelAll()
        gate.complete(Unit)
        Thread.sleep(200)
        assertEquals(0, lanes.laneCount)
        assertEquals(emptyList<Int>(), seen.toList())
    }
}
