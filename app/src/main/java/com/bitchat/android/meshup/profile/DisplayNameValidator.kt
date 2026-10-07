package com.bitchat.android.meshup.profile

/**
 * Pure validation for the user-visible display name.
 *
 * Rules: trimmed, 1..[MAX_CODE_POINTS] Unicode code points (not UTF-16 units, not graphemes),
 * at most [MAX_UTF8_BYTES] UTF-8 bytes (the announce packet limit), no ISO control characters
 * and no bidirectional override/isolate/mark characters (spoofing vectors).
 */
object DisplayNameValidator {
    const val MAX_CODE_POINTS = 32
    const val MAX_UTF8_BYTES = 255

    enum class Reason { EMPTY, TOO_LONG, TOO_MANY_BYTES, CONTROL_CHARS }

    sealed interface Result {
        data class Valid(val normalized: String) : Result
        data class Invalid(val reason: Reason) : Result
    }

    fun validate(raw: String): Result {
        val name = raw.trim()
        if (name.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (containsForbiddenChars(name)) return Result.Invalid(Reason.CONTROL_CHARS)
        if (name.codePointCount(0, name.length) > MAX_CODE_POINTS) {
            return Result.Invalid(Reason.TOO_LONG)
        }
        if (utf8Length(name) > MAX_UTF8_BYTES) return Result.Invalid(Reason.TOO_MANY_BYTES)
        return Result.Valid(name)
    }

    internal fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    private fun containsForbiddenChars(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (Character.isISOControl(cp) || isBidiControl(cp)) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun isBidiControl(cp: Int): Boolean =
        cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0x200E || cp == 0x200F || cp == 0x061C
}
