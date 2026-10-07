package com.bitchat.android.services

import android.content.Context
import android.os.Build
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.nostr.NostrTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** Decision 013: Nostr routes only work after the user opts in to Internet features. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class MessageRouterInternetGateTest {

    private val myPeerID = "1111222233334444"
    private val noiseKey = ByteArray(32) { 0x0B }
    private val peerID = com.bitchat.android.services.ContactIdentityResolver.peerIdForNoiseKey(noiseKey)
    private val noiseHex = com.bitchat.android.services.ContactIdentityResolver.noiseKeyHex(noiseKey)

    private lateinit var context: Context
    private lateinit var mesh: MeshService
    private lateinit var nostr: NostrTransport
    private var internetOn = false
    private lateinit var favorites: FavoritesPersistenceService

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("router-gate-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val identityManager = SecureIdentityStateManager(prefs, testOnly = true)
        ContactDirectory.identityManagerProvider = { identityManager }

        mesh = mock()
        whenever(mesh.myPeerID).thenReturn(myPeerID)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "peer"))
        whenever(mesh.getPeerInfo(peerID)).thenReturn(
            PeerInfo(
                id = peerID, nickname = "peer", isConnected = false, isDirectConnection = false,
                noisePublicKey = noiseKey, signingPublicKey = ByteArray(32) { 0x0A },
                isVerifiedNickname = false, lastSeen = 0L
            )
        )
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(false)
        ContactDirectory.initialize(context) { mesh }

        // The real service needs the Android Keystore (unavailable under Robolectric on Windows), so
        // install a mock holding one mutual favourite with a Nostr key.
        val mutual = FavoriteRelationship(
            peerNoisePublicKey = noiseKey,
            peerNostrPublicKey = "npub1" + "q".repeat(58),
            peerNickname = "peer",
            isFavorite = true,
            theyFavoritedUs = true,
            favoritedAt = java.util.Date(),
            lastUpdated = java.util.Date()
        )
        favorites = mock()
        whenever(favorites.getFavoriteStatus(any<String>())).thenReturn(mutual)
        whenever(favorites.getFavoriteStatus(any<ByteArray>())).thenReturn(mutual)
        whenever(favorites.getAllRelationships()).thenReturn(listOf(mutual))
        installFavorites(favorites)

        nostr = mock()
        MessageRouter.disableSchedulerForTesting = true
        internetOn = false
    }

    @After
    fun tearDown() {
        installFavorites(null)
        MessageRouter.disableSchedulerForTesting = false
        ContactDirectory.identityManagerProvider = { SecureIdentityStateManager(it) }
    }

    private fun installFavorites(service: FavoritesPersistenceService?) {
        val f = FavoritesPersistenceService::class.java.getDeclaredField("INSTANCE")
        f.isAccessible = true
        f.set(null, service)
    }

    private fun router() = MessageRouter(context, mesh, nostr) { internetOn }

    @Test
    fun `offline mutual favourite is queued and Nostr untouched while Internet is off`() {
        val result = router().sendPrivate("hi", peerID, "peer", "m1")
        assertEquals(MessageRouter.RouteResult.QUEUED, result)
        verifyNoInteractions(nostr)
    }

    @Test
    fun `offline mutual favourite goes via Nostr when Internet is on`() {
        internetOn = true
        val result = router().sendPrivate("hi", peerID, "peer", "m1")
        assertEquals(MessageRouter.RouteResult.NOSTR, result)
        verify(nostr).sendPrivateMessage("hi", noiseHex, "peer", "m1")
    }

    @Test
    fun `queued message is sent over Nostr after opting in`() {
        val r = router()
        assertEquals(MessageRouter.RouteResult.QUEUED, r.sendPrivate("hi", peerID, "peer", "m1"))
        r.tickOutbox()
        verifyNoInteractions(nostr)

        internetOn = true
        r.tickOutbox()
        verify(nostr).sendPrivateMessage("hi", noiseHex, "peer", "m1")
    }

    @Test
    fun `receipts acks and favourite notifications do not touch Nostr while Internet is off`() {
        val r = router()
        r.sendReadReceipt(ReadReceipt("orig-1", "me"), peerID)
        r.sendDeliveryAck("orig-1", peerID)
        r.sendFavoriteNotification(peerID, true)
        verifyNoInteractions(nostr)
    }

    @Test
    fun `receipts acks and favourite notifications use Nostr when Internet is on`() {
        internetOn = true
        val r = router()
        r.sendReadReceipt(ReadReceipt("orig-1", "me"), peerID)
        r.sendDeliveryAck("orig-1", peerID)
        r.sendFavoriteNotification(peerID, true)
        verify(nostr).sendReadReceipt(any(), any())
        verify(nostr).sendDeliveryAck(any(), any())
        verify(nostr).sendFavoriteNotification(any(), any())
    }

    @Test
    fun `geohash DM is dropped while Internet is off`() {
        val alias = "nostr_" + "ab".repeat(8)
        com.bitchat.android.nostr.GeohashAliasRegistry.put(alias, "c".repeat(64))
        try {
            assertEquals(MessageRouter.RouteResult.DROPPED, router().sendPrivate("hi", alias, "x", "g1"))
            verify(nostr, never()).sendPrivateMessageGeohash(any(), any(), any(), anyOrNull())
        } finally {
            com.bitchat.android.nostr.GeohashAliasRegistry.clear()
        }
    }
}
