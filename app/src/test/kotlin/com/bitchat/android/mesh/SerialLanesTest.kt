package com.bitchat.android.mesh

import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `full lane applies back-pressure instead of dropping`() = runBlocking {
        val lanes = SerialLanes(scope, maxPendingPerKey = 2)
        val gate = CompletableDeferred<Unit>()
        val seen = Collections.synchronizedList(mutableListOf<Int>())
        lanes.submit("a", "t") { gate.await(); seen += 0 }
        lanes.submit("a", "t") { seen += 1 }
        val third = scope.launch { lanes.submit("a", "t") { seen += 2 } }
        Thread.sleep(200)
        assertFalse("third submit should wait while the lane is full", third.isCompleted)
        gate.complete(Unit)
        withTimeout(5_000) { third.join(); lanes.awaitIdle() }
        assertEquals(listOf(0, 1, 2), seen.toList())
    }
}
