package com.bitchat.android.util

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Log redaction helpers (R-5.5). Release builds strip Log.v/d/i via R8, but Log.w/Log.e survive,
 * so anything identifying (fingerprints, npubs, peer IDs, nicknames, geohashes, device addresses,
 * message content) must go through these helpers before reaching a w/e log line.
 *
 * Output is a short tag: the first 4 hex chars of HMAC-SHA256 plus the input length, e.g.
 * "a1b2/64". The HMAC key is 32 random bytes generated once per process and never persisted, so
 * tags correlate lines within one run but cannot be matched across restarts, devices or bug
 * reports, and cannot be confirmed by hashing candidate IDs (unlike an unsalted hash).
 */
object Redact {
    private const val EMPTY = "∅"
    private const val ALGORITHM = "HmacSHA256"
    private val HEX = "0123456789abcdef".toCharArray()

    @Volatile
    private var key: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    fun id(value: String?): String {
        if (value.isNullOrEmpty()) return EMPTY
        val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(key, ALGORITHM)) }
        val digest = mac.doFinal(value.toByteArray(Charsets.UTF_8))
        val b0 = digest[0].toInt() and 0xFF
        val b1 = digest[1].toInt() and 0xFF
        val tag = charArrayOf(HEX[b0 ushr 4], HEX[b0 and 0xF], HEX[b1 ushr 4], HEX[b1 and 0xF])
        return "${String(tag)}/${value.length}"
    }

    /** Redacts every element of a collection, e.g. a set of fingerprints. */
    fun ids(values: Collection<String?>?): String {
        if (values == null) return EMPTY
        return "[" + values.joinToString(",") { id(it) } + "]"
    }

    /** Message content / free text: only the length is revealed. */
    fun text(value: String?): String = if (value.isNullOrEmpty()) EMPTY else "<${value.length} chars>"

    /** Test hook: use a fixed key so tags are reproducible in tests. */
    internal fun setKeyForTesting(testKey: ByteArray) {
        key = testKey.copyOf()
    }
}
