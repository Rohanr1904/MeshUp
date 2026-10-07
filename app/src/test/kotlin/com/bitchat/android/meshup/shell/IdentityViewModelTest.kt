package com.bitchat.android.meshup.shell

import com.bitchat.android.identity.IdentityHealth
import com.bitchat.android.identity.IdentityIssue
import com.bitchat.android.identity.IdentityIssueReason
import com.bitchat.android.identity.IdentityKeyKind
import com.bitchat.android.meshup.service.FakeLegacyChatSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IdentityViewModelTest {
    private val src = FakeLegacyChatSource()
    private val vm = IdentityViewModel(reset = src::panicClearAllData)

    @Before fun clear() = IdentityHealth.simulateProcessDeath()

    @Test fun confirmEnabledOnlyForResetWord() {
        assertTrue(vm.canConfirmReset("reset"))
        assertTrue(vm.canConfirmReset(" Reset "))
        assertFalse(vm.canConfirmReset("reset!"))
        assertFalse(vm.canConfirmReset("res"))
        assertFalse(vm.canConfirmReset(""))
    }

    @Test fun confirmCallsPanicClearOnlyWhenTyped() {
        vm.onResetTextChange("res")
        assertFalse(vm.confirmReset())
        assertEquals(0, src.panicClears)
        vm.onResetTextChange(" RESET ")
        assertTrue(vm.confirmReset())
        assertEquals(1, src.panicClears)
        assertEquals("", vm.resetText.value)
    }

    @Test fun bannerReflectsHealthAndAcknowledgeClears() = runTest {
        assertNull(vm.banner.first())
        IdentityHealth.report(IdentityIssue(IdentityIssueReason.NOT_PERSISTED, IdentityKeyKind.NOISE_STATIC))
        assertEquals(IdentityBannerKind.NOT_PERSISTED, vm.banner.first())
        IdentityHealth.report(IdentityIssue(IdentityIssueReason.UNREADABLE, IdentityKeyKind.NOISE_STATIC))
        assertEquals(IdentityBannerKind.UNREADABLE, vm.banner.first())
        vm.acknowledge()
        assertNull(vm.banner.first())
    }
}
