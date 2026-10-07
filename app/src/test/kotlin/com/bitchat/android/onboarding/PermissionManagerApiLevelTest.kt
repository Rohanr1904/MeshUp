package com.bitchat.android.onboarding

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** P2-PR10 (R-9): location / background-location requirements by API level. */
@RunWith(RobolectricTestRunner::class)
class PermissionManagerApiLevelTest {
    private lateinit var application: Application
    private lateinit var manager: PermissionManager

    private val location = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(application).denyPermissions(
            *location.toTypedArray(),
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        manager = PermissionManager(application)
    }

    @Test
    @Config(sdk = [30])
    fun `api 30 requires location and background location`() {
        assertTrue(manager.isLocationRequiredForBle())
        assertTrue(manager.getRequiredPermissions().containsAll(location))
        assertTrue(manager.needsBackgroundLocationPermission())
        assertEquals(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            manager.getBackgroundLocationPermission()
        )
        assertFalse(manager.areRequiredPermissionsGranted())
    }

    @Test
    @Config(sdk = [34])
    fun `api 34 requires neither fine location nor background location`() {
        assertFalse(manager.isLocationRequiredForBle())
        val required = manager.getRequiredPermissions()
        assertFalse(required.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(required.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertTrue(required.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertFalse(manager.needsBackgroundLocationPermission())
        assertNull(manager.getBackgroundLocationPermission())
        assertTrue(manager.isBackgroundLocationGranted())
        assertTrue(manager.getMissingBackgroundLocationPermission().isEmpty())
        assertTrue(manager.getCategorizedPermissions().none { it.type == PermissionType.BACKGROUND_LOCATION })
    }

    @Test
    @Config(sdk = [34])
    fun `api 34 can finish onboarding with location denied`() {
        shadowOf(application).grantPermissions(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        assertTrue(manager.areAllPermissionsGranted())
        assertTrue(manager.getMissingPermissions().isEmpty())
        // Location is still offered once as an optional permission.
        assertTrue(manager.getUnrequestedOptionalPermissions().containsAll(location))
    }
}
