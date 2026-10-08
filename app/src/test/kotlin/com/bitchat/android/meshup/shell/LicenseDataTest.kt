package com.bitchat.android.meshup.shell

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseDataTest {
    private val assets = File("src/main/assets")

    @Test fun licensesJsonParsesWithNameAndLicenseOnEveryEntry() {
        val raw = File(assets, LicenseData.ASSET_LICENSES).inputStream().use(LicenseData::parse)
        assertTrue(raw.isNotEmpty())
        raw.forEach {
            assertTrue(it.name, it.name.isNotBlank())
            assertTrue(it.name, it.license.isNotBlank())
            assertTrue(it.name, it.url.startsWith("https://"))
        }
        assertTrue(raw.any { it.name.contains("OkHttp") })
        assertTrue(raw.any { it.name.contains("Arti") })
    }

    @Test fun bundledGplTextIsPresent() {
        val text = File(assets, LicenseData.ASSET_GPL).readText()
        assertTrue(text.contains("GNU GENERAL PUBLIC LICENSE"))
    }
}
