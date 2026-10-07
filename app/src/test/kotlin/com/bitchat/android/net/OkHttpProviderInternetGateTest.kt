package com.bitchat.android.net

import com.bitchat.android.meshup.settings.InternetGate
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Request
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class OkHttpProviderInternetGateTest {
    private val server = MockWebServer()

    @Before fun setUp() {
        server.start()
        OkHttpProvider.reset()
    }

    @After fun tearDown() {
        TorPreferenceManager.set(RuntimeEnvironment.getApplication(), TorMode.ON)
        InternetGate.resetForTesting()
        OkHttpProvider.reset()
        server.close()
    }

    @Test fun gateOffBlocksHttpBeforeAnyConnection() {
        InternetGate.initialize(MutableStateFlow(false))
        val req = Request.Builder().url(server.url("/x")).build()
        try {
            OkHttpProvider.httpClient().newCall(req).execute().close()
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("turned off"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun gateOffBlocksWebSocketHandshake() {
        InternetGate.initialize(MutableStateFlow(false))
        val req = Request.Builder().url(server.url("/ws")).build()
        val latch = java.util.concurrent.CountDownLatch(1)
        OkHttpProvider.webSocketClient().newWebSocket(req, object : okhttp3.WebSocketListener() {
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                latch.countDown()
            }
        })
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, server.requestCount)
    }

    /** B1: gate ON + Tor pref ON but SOCKS route not (yet) published must never go direct. */
    @Test fun gateOnTorOnWithoutRouteFailsClosed() {
        TorPreferenceManager.set(RuntimeEnvironment.getApplication(), TorMode.ON)
        InternetGate.initialize(MutableStateFlow(true))
        assertEquals(null, ArtiTorManager.getInstance().currentSocksAddress())
        server.enqueue(MockResponse.Builder().body("leak").build())
        val routed = OkHttpProvider.routedHttpClient()
        assertEquals(OkHttpProvider.Route.TOR, routed.route)
        try {
            routed.client.newCall(Request.Builder().url(server.url("/x")).build()).execute().close()
            throw AssertionError("expected connection failure")
        } catch (_: IOException) { }
        assertEquals(0, server.requestCount)
    }

    /** B1: the effective Tor mode is derived from current pref + gate, not from any caller argument. */
    @Test fun effectiveModeFollowsCurrentState() {
        val app = RuntimeEnvironment.getApplication()
        val tor = ArtiTorManager.getInstance()
        TorPreferenceManager.set(app, TorMode.ON)
        InternetGate.initialize(MutableStateFlow(false))
        assertEquals(TorMode.OFF, tor.effectiveMode(app))
        InternetGate.initialize(MutableStateFlow(true))
        assertEquals(TorMode.ON, tor.effectiveMode(app))
        TorPreferenceManager.set(app, TorMode.OFF)
        assertEquals(TorMode.OFF, tor.effectiveMode(app))
    }

    @Test fun gateOnAllowsHttp() {
        TorPreferenceManager.set(RuntimeEnvironment.getApplication(), TorMode.OFF)
        InternetGate.initialize(MutableStateFlow(true))
        server.enqueue(MockResponse.Builder().body("ok").build())
        val req = Request.Builder().url(server.url("/x")).build()
        OkHttpProvider.httpClient().newCall(req).execute().use { assertEquals("ok", it.body.string()) }
        assertEquals(1, server.requestCount)
    }
}
