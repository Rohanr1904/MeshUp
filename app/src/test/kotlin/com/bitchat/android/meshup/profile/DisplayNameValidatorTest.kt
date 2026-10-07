package com.bitchat.android.meshup.profile

import com.bitchat.android.meshup.profile.DisplayNameValidator.Reason
import com.bitchat.android.meshup.profile.DisplayNameValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayNameValidatorTest {
    private fun valid(raw: String, expected: String = raw) =
        assertEquals(Result.Valid(expected), DisplayNameValidator.validate(raw))

    private fun invalid(raw: String, reason: Reason) =
        assertEquals(Result.Invalid(reason), DisplayNameValidator.validate(raw))

    @Test fun emptyRejected() = invalid("", Reason.EMPTY)

    @Test fun whitespaceOnlyRejected() = invalid("   ", Reason.EMPTY)

    @Test fun surroundingWhitespaceTrimmed() = valid("  alice  ", "alice")

    @Test fun exactly32CharsAccepted() = valid("a".repeat(32))

    @Test fun chars33Rejected() = invalid("a".repeat(33), Reason.TOO_LONG)

    @Test fun emojiCountedAsCodePoints() {
        val emoji = "😀" // U+1F600: 2 UTF-16 units, 4 UTF-8 bytes
        val name = emoji.repeat(32)
        valid(name)
        assertEquals(128, DisplayNameValidator.utf8Length(name))
        invalid(emoji.repeat(33), Reason.TOO_LONG)
    }

    @Test fun byteCounterIsUtf8() {
        // 32 code points max 4 bytes each = 128 bytes, so the 255-byte rule is defence in depth.
        assertEquals(3, DisplayNameValidator.utf8Length("€"))
        assertTrue(DisplayNameValidator.MAX_CODE_POINTS * 4 <= DisplayNameValidator.MAX_UTF8_BYTES)
    }

    @Test fun controlCharsRejected() {
        invalid("a\nb", Reason.CONTROL_CHARS)
        invalid("a\tb", Reason.CONTROL_CHARS)
        invalid("a\u0000b", Reason.CONTROL_CHARS)
        invalid("a\u007Fb", Reason.CONTROL_CHARS)
        invalid("a\u0085b", Reason.CONTROL_CHARS)
    }

    @Test fun bidiRejected() {
        invalid("ab‮cd", Reason.CONTROL_CHARS)
        invalid("ab⁦cd", Reason.CONTROL_CHARS)
        invalid("ab‏cd", Reason.CONTROL_CHARS)
        invalid("ab؜cd", Reason.CONTROL_CHARS)
    }

    @Test fun zwjEmojiSequenceAccepted() = valid("👩‍💻")
}
