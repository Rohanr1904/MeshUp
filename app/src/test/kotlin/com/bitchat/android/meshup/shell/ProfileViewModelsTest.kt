package com.bitchat.android.meshup.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.meshup.profile.DataManagerDisplayNameStore
import com.bitchat.android.meshup.profile.DisplayNameValidator.Reason
import com.bitchat.android.meshup.profile.ProfileManager
import com.bitchat.android.meshup.profile.ProfileRepository
import com.bitchat.android.meshup.service.FakeLegacyChatSource
import com.bitchat.android.meshup.settings.InternetGate
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileViewModelsTest {
    private lateinit var context: Context
    private lateinit var repo: ProfileRepository
    private val src = FakeLegacyChatSource()
    private lateinit var profile: ProfileManager

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("meshup_settings", Context.MODE_PRIVATE).edit().clear().commit()
        repo = ProfileRepository(
            DataManagerDisplayNameStore(DataManager(context)),
            context.getSharedPreferences("meshup_settings", Context.MODE_PRIVATE)
        )
        src.nickname.value = "anon1234"
        profile = ProfileManager(repo, src)
        InternetGate.resetForTesting()
    }

    @After fun tearDown() = InternetGate.resetForTesting()

    @Test fun nameStepPrefillsCurrentNicknameAndIsValid() {
        val vm = NameEditViewModel(profile, confirm = true)
        assertEquals("anon1234", vm.state.value.text)
        assertTrue(vm.state.value.canSave)
        assertFalse(profile.nameConfirmed.value)
    }

    @Test fun validationStatesPerReason() {
        val vm = NameEditViewModel(profile, confirm = true)
        vm.onTextChange("")
        assertEquals(Reason.EMPTY, vm.state.value.error)
        assertFalse(vm.state.value.canSave)
        vm.onTextChange("   ")
        assertEquals(Reason.EMPTY, vm.state.value.error)
        vm.onTextChange("a".repeat(33))
        assertEquals(Reason.TOO_LONG, vm.state.value.error)
        vm.onTextChange("a".repeat(32))
        assertNull(vm.state.value.error)
        vm.onTextChange("bad\nname")
        assertEquals(Reason.CONTROL_CHARS, vm.state.value.error)
    }

    @Test fun invalidInputIsNotSavedOrConfirmed() {
        val vm = NameEditViewModel(profile, confirm = true)
        vm.onTextChange("a".repeat(33))
        assertFalse(vm.save())
        assertTrue(src.nicknamesSet.isEmpty())
        assertFalse(repo.isNameConfirmed())
        assertFalse(profile.nameConfirmed.value)
        assertEquals(Reason.TOO_LONG, vm.state.value.error)
    }

    @Test fun nameStepSaveRoutesThroughLegacySourceAndConfirms() {
        val vm = NameEditViewModel(profile, confirm = true)
        vm.onTextChange("  Alice ")
        assertTrue(vm.save())
        assertEquals(listOf("Alice"), src.nicknamesSet)
        assertEquals("Alice", repo.displayName.value)
        assertTrue(repo.isNameConfirmed())
        assertTrue(profile.nameConfirmed.value)
        assertTrue(vm.state.value.saved)
    }

    @Test fun profileEditSavesWithoutConfirming() {
        val vm = NameEditViewModel(profile, confirm = false)
        vm.onTextChange("Bob")
        assertTrue(vm.save())
        assertEquals(listOf("Bob"), src.nicknamesSet)
        assertFalse(repo.isNameConfirmed())
    }

    @Test fun profileEditInvalidIsNotSaved() {
        val vm = NameEditViewModel(profile, confirm = false)
        vm.onTextChange("")
        assertFalse(vm.save())
        assertTrue(src.nicknamesSet.isEmpty())
        assertEquals("anon1234", src.nickname.value)
    }

    @Test fun fingerprintIsShortGroupedAndNeverFull() {
        src.fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertEquals("0123 4567 89AB CDEF", profile.shortFingerprint())
        src.fingerprint = ""
        assertEquals("", profile.shortFingerprint())
    }

    @Test fun settingsSwitchReflectsGateAndToggleCallsSetEnabled() {
        val flow = MutableStateFlow(false)
        InternetGate.initialize(flow)
        val calls = mutableListOf<Boolean>()
        val vm = SettingsViewModel(InternetGate.enabled, { calls += it; flow.value = it })
        assertFalse(vm.internetEnabled.value)
        vm.setInternetEnabled(true)
        assertEquals(listOf(true), calls)
        assertTrue(vm.internetEnabled.value)
        assertTrue(InternetGate.isEnabled())
        vm.setInternetEnabled(false)
        assertEquals(listOf(true, false), calls)
        assertFalse(vm.internetEnabled.value)
    }

    @Test fun restartRecommendedOnlyAfterInternetWasUsedAndIsOff() {
        var used = false
        val vm = SettingsViewModel(MutableStateFlow(false), {}, { used })
        assertFalse(vm.restartRecommended(internetOn = false))
        used = true
        assertTrue(vm.restartRecommended(internetOn = false))
        assertFalse(vm.restartRecommended(internetOn = true))
    }
}
