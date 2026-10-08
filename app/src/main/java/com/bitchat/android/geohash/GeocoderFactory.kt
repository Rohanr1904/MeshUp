package com.bitchat.android.geohash

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.bitchat.android.meshup.settings.InternetGate

/**
 * Factory to provide the best available geocoder.
 */
object GeocoderFactory {
    fun get(context: Context): GeocoderProvider {
        // If Google Play Services Geocoder is present, use it.
        // Otherwise, fall back to OpenStreetMap.
        val provider = if (Geocoder.isPresent()) {
            AndroidGeocoderProvider(context)
        } else {
            OpenStreetMapGeocoderProvider()
        }
        // MeshUp: Internet opt-in gate (Decision 013). The platform Geocoder does not use the app's
        // HTTP client, so the gate must be checked here as well.
        return InternetGatedGeocoderProvider(provider)
    }
}

/**
 * Fail-closed wrapper: while Internet features are off, no coordinates leave the app for reverse
 * geocoding and the result is empty. The gate is read on every call, so a lookup queued before the
 * switch turned off does not start afterwards.
 */
internal class InternetGatedGeocoderProvider(
    private val delegate: GeocoderProvider,
    private val internetEnabled: () -> Boolean = InternetGate::isEnabled
) : GeocoderProvider {
    override suspend fun getFromLocation(
        latitude: Double,
        longitude: Double,
        maxResults: Int,
        liveLocationToken: Long?
    ): List<Address> {
        if (!internetEnabled()) return emptyList()
        val result = delegate.getFromLocation(latitude, longitude, maxResults, liveLocationToken)
        return if (internetEnabled()) result else emptyList()
    }
}
