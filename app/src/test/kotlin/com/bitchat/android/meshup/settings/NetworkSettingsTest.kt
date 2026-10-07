package com.bitchat.android.meshup.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkSettingsTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("meshup_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun defaultsToFalse() {
        assertFalse(NetworkSettings.create(context).internetEnabled.value)
    }

    @Test fun persistsAcrossInstancesAndEmits() {
        val a = NetworkSettings.create(context)
        a.setInternetEnabled(true)
        assertTrue(a.internetEnabled.value)
        assertTrue(NetworkSettings.create(context).internetEnabled.value)
        a.setInternetEnabled(false)
        assertFalse(NetworkSettings.create(context).internetEnabled.value)
    }
}
