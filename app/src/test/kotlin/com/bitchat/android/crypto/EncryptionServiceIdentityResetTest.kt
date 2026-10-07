package com.bitchat.android.crypto

import android.content.Context
import android.content.SharedPreferences
import com.bitchat.android.identity.CommitFailingPrefs
import com.bitchat.android.identity.ThrowingGetPrefs
import com.bitchat.android.identity.IdentityHealth
import com.bitchat.android.identity.IdentityIssue
import com.bitchat.android.identity.IdentityIssueReason
import com.bitchat.android.identity.IdentityKeyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/** Injects plain prefs; read during the superclass constructor, so state lives outside the instance. */
private object Inject {
    lateinit var secure: SharedPreferences
    lateinit var legacy: SharedPreferences
}

private class InjectedEncryptionService(context: Context) : EncryptionService(context) {
    override fun createSecurePrefs() = Inject.secure
    override fun legacyPrefs() = Inject.legacy
}

private const val KEY = "ed25519_signing_private_key"

@RunWith(RobolectricTestRunner::class)
class EncryptionServiceIdentityResetTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        Inject.secure = context.getSharedPreferences("secure-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        Inject.legacy = context.getSharedPreferences("legacy-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        IdentityHealth.simulateProcessDeath()
    }

    @Test
    fun `absent key creates a new one with no event`() {
        InjectedEncryptionService(context)
        assertTrue(IdentityHealth.issues.value.isEmpty())
        assertTrue(Inject.secure.contains(KEY))
        assertFalse(Inject.secure.contains(KEY + "_unreadable_backup"))
    }

    @Test
    fun `corrupt blob is backed up exactly and reported`() {
        Inject.secure.edit().putString(KEY, "garbage").commit()

        InjectedEncryptionService(context)

        assertEquals("garbage", Inject.secure.getString(KEY + "_unreadable_backup", null))
        assertTrue(Inject.secure.getLong(KEY + "_unreadable_backup_at", 0L) > 0L)
        assertNotEquals("garbage", Inject.secure.getString(KEY, null))
        assertEquals(
            listOf(IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.ED25519_SIGNING, backedUp = true)),
            IdentityHealth.issues.value
        )
    }

    @Test
    fun `failed save raises NOT_PERSISTED`() {
        Inject.secure = CommitFailingPrefs(Inject.secure)

        val service = InjectedEncryptionService(context)

        assertEquals(32, service.getSigningPublicKey()!!.size)
        assertEquals(
            listOf(IdentityIssue(IdentityIssueReason.NOT_PERSISTED, IdentityKeyKind.ED25519_SIGNING)),
            IdentityHealth.issues.value
        )
    }

    @Test
    fun `legacy plaintext key is removed when encrypted key already exists`() {
        Inject.legacy.edit().putString(KEY, "legacy-plain").commit()
        val encoded = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
        Inject.secure.edit().putString(KEY, encoded).commit()

        InjectedEncryptionService(context)

        assertNull(Inject.legacy.getString(KEY, null))
        assertEquals(encoded, Inject.secure.getString(KEY, null))
        assertTrue(IdentityHealth.issues.value.isEmpty())
    }

    @Test
    fun `legacy plaintext key is migrated then removed when encrypted key absent`() {
        val legacyKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 9 })
        Inject.legacy.edit().putString(KEY, legacyKey).commit()

        InjectedEncryptionService(context)

        assertNull(Inject.legacy.getString(KEY, null))
        assertEquals(legacyKey, Inject.secure.getString(KEY, null))
    }

    @Test
    fun `corrupt encrypted value with valid legacy key recovers identity without event`() {
        val legacyKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 5 })
        Inject.legacy.edit().putString(KEY, legacyKey).commit()
        Inject.secure.edit().putString(KEY, "garbage").commit()

        val service = InjectedEncryptionService(context)

        val expected = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(ByteArray(32) { 5 }, 0)
            .generatePublicKey().encoded
        assertTrue(expected.contentEquals(service.getSigningPublicKey()!!))
        assertEquals(legacyKey, Inject.secure.getString(KEY, null))
        assertEquals("garbage", Inject.secure.getString(KEY + "_unreadable_backup", null))
        assertNull(Inject.legacy.getString(KEY, null))
        assertTrue(IdentityHealth.issues.value.isEmpty())
    }

    @Test
    fun `undecryptable encrypted value gives new key UNREADABLE and backedUp false`() {
        Inject.secure.edit().putString(KEY, "garbage").commit()
        Inject.secure = ThrowingGetPrefs(Inject.secure, setOf(KEY))

        val service = InjectedEncryptionService(context)

        assertEquals(32, service.getSigningPublicKey()!!.size)
        assertEquals(
            listOf(IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.ED25519_SIGNING, backedUp = false)),
            IdentityHealth.issues.value
        )
    }

    @Test
    fun `unreadable encrypted value keeps first backup`() {
        Inject.secure.edit().putString(KEY, "garbage").putString(KEY + "_unreadable_backup", "first").commit()

        InjectedEncryptionService(context)

        assertEquals("first", Inject.secure.getString(KEY + "_unreadable_backup", null))
    }

    @Test
    fun `panic clears the legacy plaintext prefs`() {
        val service = InjectedEncryptionService(context)
        Inject.legacy.edit().putString(KEY, java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 3 })).commit()

        service.clearPersistentIdentity()

        assertFalse(Inject.legacy.contains(KEY))
    }
}
