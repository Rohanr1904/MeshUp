package com.bitchat.android.identity

import android.content.Context
import android.content.SharedPreferences
import com.bitchat.android.noise.NoiseEncryptionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/** Prefs whose every commit reports failure and persists nothing. */
class CommitFailingPrefs(private val delegate: SharedPreferences) : SharedPreferences by delegate {
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = this
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
        override fun remove(key: String?) = this
        override fun clear() = this
        override fun commit() = false
        override fun apply() {}
    }
}

/** Prefs whose getString throws for the given keys (simulates an undecryptable value). */
class ThrowingGetPrefs(
    private val delegate: SharedPreferences,
    private val throwingKeys: Set<String>
) : SharedPreferences by delegate {
    override fun getString(key: String?, defValue: String?): String? {
        if (key in throwingKeys) throw java.security.GeneralSecurityException("cannot decrypt")
        return delegate.getString(key, defValue)
    }
}

@RunWith(RobolectricTestRunner::class)
class IdentityResetDetectionTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        prefs = context.getSharedPreferences("identity-reset-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        IdentityHealth.simulateProcessDeath()
    }

    private fun noise(p: SharedPreferences = prefs) =
        NoiseEncryptionService(context, SecureIdentityStateManager(p, testOnly = true))

    @Test
    fun `absent keys create new identity with no event and no backup`() {
        val manager = SecureIdentityStateManager(prefs, testOnly = true)
        assertTrue(manager.loadStaticKeyResult() is KeyLoadResult.Absent)
        assertTrue(manager.loadSigningKeyResult() is KeyLoadResult.Absent)

        noise()

        assertTrue(IdentityHealth.issues.value.isEmpty())
        assertTrue(manager.loadStaticKeyResult() is KeyLoadResult.Loaded)
        assertTrue(manager.loadSigningKeyResult() is KeyLoadResult.Loaded)
        assertFalse(prefs.all.keys.any { it.contains("_unreadable_backup") })
    }

    @Test
    fun `corrupt static key is backed up exactly and reported`() {
        // Valid base64 but wrong length for both halves.
        prefs.edit().putString("static_private_key", "AAAA").putString("static_public_key", "QkJCQg==").commit()

        noise()

        assertEquals("AAAA", prefs.getString("static_private_key_unreadable_backup", null))
        assertEquals("QkJCQg==", prefs.getString("static_public_key_unreadable_backup", null))
        assertTrue(prefs.getLong("static_private_key_unreadable_backup_at", 0L) > 0L)
        assertNotEquals("AAAA", prefs.getString("static_private_key", null))
        assertEquals(
            listOf(IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.NOISE_STATIC, backedUp = true)),
            IdentityHealth.issues.value
        )
        assertTrue(SecureIdentityStateManager(prefs, testOnly = true).loadStaticKeyResult() is KeyLoadResult.Loaded)
    }

    @Test
    fun `partial signing key pair is unreadable not absent`() {
        prefs.edit().putString("signing_private_key", "AAAA").commit()

        noise()

        assertEquals("AAAA", prefs.getString("signing_private_key_unreadable_backup", null))
        assertTrue(
            IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.NOISE_SIGNING, backedUp = true) in IdentityHealth.issues.value
        )
    }

    @Test
    fun `first backup is kept and never overwritten`() {
        prefs.edit().putString("static_private_key", "AAAA").putString("static_public_key", "AAAA").commit()
        noise()
        prefs.edit().putString("static_private_key", "QUFB").putString("static_public_key", "QUFB").commit()
        noise()
        assertEquals("AAAA", prefs.getString("static_private_key_unreadable_backup", null))
        assertEquals("AAAA", prefs.getString("static_public_key_unreadable_backup", null))
    }

    @Test
    fun `undecryptable value is replaced with UNREADABLE event and backedUp false`() {
        prefs.edit().putString("static_private_key", "AAAA").putString("static_public_key", "AAAA").commit()
        val throwing = ThrowingGetPrefs(prefs, setOf("static_private_key", "static_public_key"))

        val service = noise(throwing)

        assertEquals(32, service.getStaticPublicKeyData().size)
        assertEquals(
            listOf(IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.NOISE_STATIC, backedUp = false)),
            IdentityHealth.issues.value
        )
        assertFalse(prefs.contains("static_private_key_unreadable_backup"))
        assertNotEquals("AAAA", prefs.getString("static_private_key", null))
    }

    @Test
    fun `warning survives process death until acknowledged`() {
        val health = context.getSharedPreferences("health-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        IdentityHealth.attach(health)
        IdentityHealth.report(IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.NOISE_STATIC, true))

        IdentityHealth.simulateProcessDeath()
        assertTrue(IdentityHealth.issues.value.isEmpty())
        IdentityHealth.attach(health)
        assertEquals(1, IdentityHealth.issues.value.size)
        assertTrue(IdentityHealth.issues.value[0].backedUp)

        IdentityHealth.acknowledge()
        IdentityHealth.simulateProcessDeath()
        IdentityHealth.attach(health)
        assertTrue(IdentityHealth.issues.value.isEmpty())

        // A new issue after the ack shows again.
        IdentityHealth.report(IdentityIssue(IdentityIssueReason.NOT_PERSISTED, IdentityKeyKind.NOISE_STATIC))
        IdentityHealth.simulateProcessDeath()
        IdentityHealth.attach(health)
        assertEquals(1, IdentityHealth.issues.value.size)

        IdentityHealth.reset(context)
        IdentityHealth.simulateProcessDeath()
        IdentityHealth.attach(health)
        assertTrue(IdentityHealth.issues.value.isEmpty())
        IdentityHealth.simulateProcessDeath()
    }

    @Test
    fun `failed save raises NOT_PERSISTED and keeps in-memory key`() {
        val service = noise(CommitFailingPrefs(prefs))

        assertEquals(32, service.getStaticPublicKeyData().size)
        val issues = IdentityHealth.issues.value
        assertTrue(IdentityIssue(IdentityIssueReason.NOT_PERSISTED, IdentityKeyKind.NOISE_STATIC) in issues)
        assertTrue(IdentityIssue(IdentityIssueReason.NOT_PERSISTED, IdentityKeyKind.NOISE_SIGNING) in issues)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun `acknowledge clears issues`() {
        IdentityHealth.report(IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.NOISE_STATIC))
        IdentityHealth.report(IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.NOISE_STATIC))
        assertEquals(1, IdentityHealth.issues.value.size)
        IdentityHealth.acknowledge()
        assertTrue(IdentityHealth.issues.value.isEmpty())
    }
}
