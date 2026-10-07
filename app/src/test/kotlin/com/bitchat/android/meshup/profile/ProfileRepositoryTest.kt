package com.bitchat.android.meshup.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.ui.DataManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileRepositoryTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("meshup_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun repo() = ProfileRepository(
        DataManagerDisplayNameStore(DataManager(context)),
        context.getSharedPreferences("meshup_settings", Context.MODE_PRIVATE)
    )

    private fun storedNickname() =
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).getString("nickname", null)

    @Test fun invalidNameNotSaved() {
        val r = repo()
        val before = r.displayName.value
        val res = r.setDisplayName("a\nb")
        assertTrue(res is DisplayNameValidator.Result.Invalid)
        assertEquals(before, r.displayName.value)
        assertEquals(before, storedNickname())
    }

    @Test fun validNameTrimmedAndSavedUnderNicknameKey() {
        val r = repo()
        val res = r.setDisplayName("  Alice ")
        assertEquals(DisplayNameValidator.Result.Valid("Alice"), res)
        assertEquals("Alice", r.displayName.value)
        assertEquals("Alice", storedNickname())
    }

    @Test fun confirmedFlagDefaultsFalseAndPersists() {
        val r = repo()
        assertFalse(r.isNameConfirmed())
        r.markNameConfirmed()
        assertTrue(r.isNameConfirmed())
        assertTrue(repo().isNameConfirmed())
    }
}
