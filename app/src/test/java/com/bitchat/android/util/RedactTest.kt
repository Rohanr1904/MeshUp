package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {
    private val fp = "a3f5c9d17e8b2046a3f5c9d17e8b2046a3f5c9d17e8b2046a3f5c9d17e8b2046"

    @Test
    fun idIsStableShortAndNonReversible() {
        val a = Redact.id(fp)
        assertEquals(a, Redact.id(fp))
        assertTrue(a.length <= 8)
        assertEquals("/${fp.length}", a.substring(4))
        assertFalse(a.contains(fp))
    }

    @Test
    fun differentInputsDiffer() {
        assertNotEquals(Redact.id("alice"), Redact.id("bob"))
    }

    @Test
    fun nullAndEmpty() {
        assertEquals("∅", Redact.id(null))
        assertEquals("∅", Redact.id(""))
        assertEquals("∅", Redact.text(null))
        assertEquals("∅", Redact.ids(null))
    }

    @Test
    fun idsAndText() {
        val s = Redact.ids(setOf("one", "two"))
        assertFalse(s.contains("one"))
        assertTrue(s.startsWith("[") && s.endsWith("]"))
        assertEquals("<5 chars>", Redact.text("hello"))
    }

    @Test
    fun fixedKeyGivesKnownTagAndKeysDiffer() {
        Redact.setKeyForTesting(ByteArray(32) { it.toByte() })
        val a = Redact.id("alice")
        assertEquals(a, Redact.id("alice"))
        Redact.setKeyForTesting(ByteArray(32) { (it + 1).toByte() })
        val b = Redact.id("alice")
        assertNotEquals("different keys must give different tags", a, b)
        assertTrue(a.matches(Regex("[0-9a-f]{4}/5")))
    }
}
