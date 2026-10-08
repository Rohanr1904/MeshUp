package com.bitchat.android.geohash

import android.location.Address
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class InternetGatedGeocoderProviderTest {
    private class RecordingProvider(private val onCall: () -> Unit = {}) : GeocoderProvider {
        var calls = 0
        override suspend fun getFromLocation(
            latitude: Double,
            longitude: Double,
            maxResults: Int,
            liveLocationToken: Long?
        ): List<Address> {
            calls++
            onCall()
            return listOf(Address(Locale.US).apply { locality = "Somewhere" })
        }
    }

    @Test fun gateOffNeverCallsDelegate() = runBlocking {
        val delegate = RecordingProvider()
        val gated = InternetGatedGeocoderProvider(delegate) { false }

        val result = gated.getFromLocation(12.9, 77.6, 1)

        assertTrue(result.isEmpty())
        assertEquals(0, delegate.calls)
    }

    @Test fun gateOnReturnsDelegateResult() = runBlocking {
        val delegate = RecordingProvider()
        val gated = InternetGatedGeocoderProvider(delegate) { true }

        val result = gated.getFromLocation(12.9, 77.6, 1)

        assertEquals(1, delegate.calls)
        assertEquals("Somewhere", result.single().locality)
    }

    @Test fun gateTurnedOffDuringLookupDropsResult() = runBlocking {
        var enabled = true
        val delegate = RecordingProvider(onCall = { enabled = false })
        val gated = InternetGatedGeocoderProvider(delegate) { enabled }

        val result = gated.getFromLocation(12.9, 77.6, 1)

        assertEquals(1, delegate.calls)
        assertTrue(result.isEmpty())
    }
}
