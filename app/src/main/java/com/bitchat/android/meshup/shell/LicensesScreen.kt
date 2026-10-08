package com.bitchat.android.meshup.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.util.AppConstants

/** Open-source licences screen (GPLv3 launch requirement). Back calls [onBack]. */
@Composable
fun LicensesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val entries = remember { runCatching { context.assets.open(LicenseData.ASSET_LICENSES).use(LicenseData::parse) }.getOrDefault(emptyList()) }
    var showGpl by remember { mutableStateOf(false) }
    val gplText = remember(showGpl) {
        if (showGpl) runCatching { context.assets.open(LicenseData.ASSET_GPL).bufferedReader().use { it.readText() } }.getOrDefault("") else ""
    }
    val sourceUrl = AppConstants.Release.SOURCE_URL

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("screen_licenses"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("licenses_back")) {
            Text(stringResource(R.string.meshup_licenses_back))
        }
        Text(
            stringResource(R.string.meshup_licenses_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )
        Text(stringResource(R.string.meshup_licenses_app_license))
        Text(stringResource(R.string.meshup_licenses_based_on))
        Text(stringResource(R.string.meshup_licenses_modified))
        Text(stringResource(R.string.meshup_licenses_copyright))
        Text(stringResource(R.string.meshup_licenses_no_warranty), modifier = Modifier.testTag("licenses_no_warranty"))
        val sourceDesc = stringResource(R.string.meshup_licenses_source_desc)
        TextButton(
            onClick = { context.openUrl(sourceUrl) },
            modifier = Modifier.semantics { contentDescription = sourceDesc }.testTag("licenses_source")
        ) { Text(stringResource(R.string.meshup_licenses_source_link, sourceUrl)) }
        TextButton(onClick = { showGpl = !showGpl }, modifier = Modifier.testTag("licenses_gpl_toggle")) {
            Text(stringResource(if (showGpl) R.string.meshup_licenses_hide_gpl else R.string.meshup_licenses_show_gpl))
        }
        if (showGpl) {
            Text(gplText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("licenses_gpl_text"))
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            stringResource(R.string.meshup_licenses_third_party),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
        entries.forEach { e ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(e.name, style = MaterialTheme.typography.titleSmall)
                Text(e.license, style = MaterialTheme.typography.bodyMedium)
                e.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (e.url.isNotBlank()) {
                    val desc = stringResource(R.string.meshup_licenses_project_desc, e.name)
                    TextButton(
                        onClick = { context.openUrl(e.url) },
                        modifier = Modifier.semantics { contentDescription = desc }
                    ) { Text(e.url, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

/** Opens [url] externally; only ever invoked from an explicit user tap. */
private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
