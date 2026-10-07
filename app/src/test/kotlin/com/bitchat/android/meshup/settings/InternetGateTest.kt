package com.bitchat.android.meshup.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InternetGateTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("meshup_settings", Context.MODE_PRIVATE).edit().clear().commit()
        InternetGate.resetForTesting()
    }

    @After fun tearDown() = InternetGate.resetForTesting()

    @Test fun closedBeforeInitialize() {
        assertFalse(InternetGate.isEnabled())
    }

    @Test fun freshInstallIsOffAfterInitialize() {
        InternetGate.initialize(context)
        assertFalse(InternetGate.isEnabled())
    }

    @Test fun followsSettingsImmediately() {
        val settings = InternetGate.initialize(context)
        settings.setInternetEnabled(true)
        assertTrue(InternetGate.isEnabled())
        assertTrue(InternetGate.enabled.value)
        settings.setInternetEnabled(false)
        assertFalse(InternetGate.isEnabled())
    }

    @Test fun publicToggleReachesGateAndController() {
        InternetGate.initialize(context)
        assertSame(InternetGate.settings, InternetGate.initialize(context)) // idempotent, same flow
        val events = mutableListOf<String>()
        val controller = InternetController(InternetGate.enabled, object : InternetController.Actions {
            override fun onEnabled() { events += "on" }
            override fun onDisabled() { events += "off" }
        }, CoroutineScope(Job() + Dispatchers.Unconfined))
        controller.start()
        InternetGate.setEnabled(true)
        assertTrue(InternetGate.isEnabled())
        InternetGate.setEnabled(false)
        assertFalse(InternetGate.isEnabled())
        assertEquals(listOf("on", "off"), events)
        controller.stop()
    }

    @Test fun picksUpPersistedValueOnNextProcess() {
        NetworkSettings.create(context).setInternetEnabled(true)
        InternetGate.initialize(context)
        assertTrue(InternetGate.isEnabled())
    }

    @Test fun controllerReactsOnlyToChanges() {
        val flow = MutableStateFlow(false)
        val events = mutableListOf<String>()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val controller = InternetController(flow, object : InternetController.Actions {
            override fun onEnabled() { events += "on" }
            override fun onDisabled() { events += "off" }
        }, scope)
        controller.start()
        controller.start() // idempotent
        assertEquals(emptyList<String>(), events) // initial state is applied by the Application, not here
        flow.value = true
        flow.value = true
        flow.value = false
        assertEquals(listOf("on", "off"), events)
        controller.stop()
        flow.value = true
        assertEquals(listOf("on", "off"), events)
    }

    @Test fun controllerSurvivesFailingHandler() {
        val flow = MutableStateFlow(false)
        var calls = 0
        val controller = InternetController(flow, object : InternetController.Actions {
            override fun onEnabled() { calls++; throw IllegalStateException("boom") }
            override fun onDisabled() { calls++ }
        }, CoroutineScope(Job() + Dispatchers.Unconfined))
        controller.start()
        flow.value = true
        flow.value = false
        assertEquals(2, calls)
        controller.stop()
    }
}
