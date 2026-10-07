package com.bitchat.android.nostr

import com.bitchat.android.meshup.settings.InternetGate
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Decision 013: with Internet off the relay manager must not open sockets or schedule retries. */
@RunWith(RobolectricTestRunner::class)
class NostrRelayManagerInternetGateTest {
    @After fun tearDown() = InternetGate.resetForTesting()

    @Test
    fun `connect and retry are no-ops while Internet is off`() {
        InternetGate.resetForTesting() // fail-closed default
        val manager = NostrRelayManager.shared
        manager.disconnect()

        manager.connect()
        manager.retryConnection(NostrRelayManager.defaultRelays().first())
        Thread.sleep(300) // any attempted connection would have recorded an error or status by now

        assertFalse(manager.isConnected.value)
        assertTrue(manager.getRelayStatuses().none { it.isConnected })
        assertTrue(manager.getRelayStatuses().all { it.lastError == null && it.nextReconnectTime == null })
        manager.disconnect()
    }
}
