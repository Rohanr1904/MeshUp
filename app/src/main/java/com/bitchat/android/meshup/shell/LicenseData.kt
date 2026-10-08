package com.bitchat.android.meshup.shell

import com.google.gson.Gson
import java.io.InputStream

/** One third-party component credited on the licences screen. */
data class LicenseEntry(
    val name: String = "",
    val license: String = "",
    val url: String = "",
    val licenseUrl: String = "",
    val category: String = "",
    val notice: String? = null
)

private data class LicenseFile(val entries: List<LicenseEntry>? = null)

object LicenseData {
    const val ASSET_LICENSES = "licenses.json"
    const val ASSET_GPL = "gpl-3.0.txt"

    /** Parses the checked-in asset; entries missing a name or licence are dropped. */
    fun parse(input: InputStream): List<LicenseEntry> =
        input.bufferedReader().use { Gson().fromJson(it, LicenseFile::class.java) }
            ?.entries.orEmpty()
            .filter { it.name.isNotBlank() && it.license.isNotBlank() }
}
