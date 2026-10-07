package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSignerTrustTest {
    private val own = "a".repeat(64)
    private val foreign = "b".repeat(64)
    private val pin = "c".repeat(64)

    @Test fun `empty pin and own cert is trusted`() =
        assertTrue(isTrustedApkSigner(setOf(own), setOf(own), ""))

    @Test fun `empty pin and foreign cert is rejected`() =
        assertFalse(isTrustedApkSigner(setOf(foreign), setOf(own), ""))

    @Test fun `empty pin and no own certs is rejected`() =
        assertFalse(isTrustedApkSigner(setOf(foreign), emptySet(), ""))

    @Test fun `valid pin matching apk with different own cert is trusted`() =
        assertTrue(isTrustedApkSigner(setOf(pin), setOf(own), pin.uppercase()))

    @Test fun `malformed pin is treated as unset`() {
        assertNull(normalizeApkPin("abc"))
        assertNull(normalizeApkPin("  "))
        assertEquals(pin, normalizeApkPin(pin.chunked(2).joinToString(":")))
        assertFalse(isTrustedApkSigner(setOf(foreign), setOf(own), "abc"))
    }

    @Test fun `apk with no certs is rejected`() =
        assertFalse(isTrustedApkSigner(emptySet(), setOf(own), pin))
}
