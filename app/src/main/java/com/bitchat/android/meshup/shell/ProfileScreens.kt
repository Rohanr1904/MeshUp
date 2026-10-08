package com.bitchat.android.meshup.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitchat.android.R
import com.bitchat.android.identity.IdentityHealth
import com.bitchat.android.meshup.profile.DisplayNameValidator.Reason
import com.bitchat.android.meshup.profile.ProfileManager

private fun Reason.messageRes(): Int = when (this) {
    Reason.EMPTY -> R.string.meshup_name_error_empty
    Reason.TOO_LONG -> R.string.meshup_name_error_too_long
    Reason.TOO_MANY_BYTES -> R.string.meshup_name_error_too_many_bytes
    Reason.CONTROL_CHARS -> R.string.meshup_name_error_invalid_chars
}

/** Name text field with live validation error; shared by the name step and Profile edit. */
@Composable
private fun NameField(vm: NameEditViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val s by vm.state.collectAsState()
    OutlinedTextField(
        value = s.text,
        onValueChange = vm::onTextChange,
        modifier = modifier.fillMaxWidth().testTag("name_field"),
        singleLine = true,
        isError = s.error != null,
        label = { Text(stringResource(R.string.meshup_name_hint)) },
        supportingText = {
            s.error?.let { Text(stringResource(it.messageRes()), Modifier.testTag("name_error")) }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (s.canSave) onDone() })
    )
}

/** Shown once, after permissions, instead of the tabs until the name is confirmed. */
@Composable
fun NameStepScreen(profile: ProfileManager, modifier: Modifier = Modifier) {
    val vm = viewModel { NameEditViewModel(profile, confirm = true) }
    val s by vm.state.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp)
            .testTag("screen_name_step"),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(stringResource(R.string.meshup_name_step_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.meshup_name_step_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NameField(vm, onDone = { vm.save() })
        Button(
            onClick = { vm.save() },
            enabled = s.canSave,
            modifier = Modifier.fillMaxWidth().testTag("name_save")
        ) { Text(stringResource(R.string.meshup_name_continue)) }
    }
}

/** Profile tab: display name (editable), own short fingerprint, and the Settings section. */
@Composable
fun ProfileScreen(profile: ProfileManager, onOpenLicences: () -> Unit = {}) {
    val name by profile.displayName.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val fingerprint = remember { profile.shortFingerprint() }
    val settingsVm = viewModel { SettingsViewModel() }
    val internet by settingsVm.internetEnabled.collectAsState()
    val context = LocalContext.current
    remember { IdentityHealth.attach(context) }
    val identityVm = viewModel { IdentityViewModel(reset = profile::resetIdentity) }
    val banner by identityVm.banner.collectAsState(initial = null)
    val backupKept by identityVm.backupKept.collectAsState(initial = false)
    var confirmingReset by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("screen_profile"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        banner?.let { IdentityBanner(it, backupKept, onGotIt = identityVm::acknowledge) }
        Text(stringResource(R.string.meshup_profile_display_name), style = MaterialTheme.typography.labelMedium)
        if (editing) {
            // Fresh editor each time editing starts, prefilled with the current name.
            val vm = remember { NameEditViewModel(profile, confirm = false) }
            val s by vm.state.collectAsState()
            NameField(vm, onDone = { if (vm.save()) editing = false })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (vm.save()) editing = false },
                    enabled = s.canSave,
                    modifier = Modifier.testTag("profile_name_save")
                ) { Text(stringResource(R.string.meshup_name_save)) }
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.meshup_name_cancel)) }
            }
        } else {
            Text(name, style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { editing = true }, modifier = Modifier.testTag("profile_name_edit")) {
                Text(stringResource(R.string.meshup_name_edit))
            }
        }

        if (fingerprint.isNotEmpty()) {
            Text(
                stringResource(R.string.meshup_profile_fingerprint),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                fingerprint,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("profile_fingerprint")
            )
            Text(
                stringResource(R.string.meshup_profile_fingerprint_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        Text(stringResource(R.string.meshup_settings_title), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.meshup_settings_internet_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = internet,
                onCheckedChange = settingsVm::setInternetEnabled,
                modifier = Modifier.testTag("switch_internet")
            )
        }
        Text(
            stringResource(R.string.meshup_settings_internet_off),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            stringResource(R.string.meshup_settings_internet_on),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (settingsVm.restartRecommended(internet)) {
            Text(
                stringResource(R.string.meshup_settings_restart_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(
                onClick = { restartApp(context) },
                modifier = Modifier.testTag("button_restart")
            ) { Text(stringResource(R.string.meshup_settings_restart_button)) }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        TextButton(
            onClick = { identityVm.clearResetText(); confirmingReset = true },
            modifier = Modifier.testTag("profile_reset_identity")
        ) {
            Text(stringResource(R.string.meshup_identity_reset_button), color = MaterialTheme.colorScheme.error)
        }
        if (confirmingReset) {
            ResetIdentityDialog(
                vm = identityVm,
                onDismiss = { confirmingReset = false },
                onConfirmed = { confirmingReset = false }
            )
        }

        Text(
            stringResource(R.string.meshup_settings_battery_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        TextButton(onClick = { context.openSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) {
            Text(stringResource(R.string.meshup_settings_battery_optimization))
        }
        TextButton(onClick = { context.openSystemSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS) }) {
            Text(stringResource(R.string.meshup_settings_power_saver))
        }
        TextButton(onClick = onOpenLicences, modifier = Modifier.testTag("profile_licences_row")) {
            Text(stringResource(R.string.meshup_settings_licenses))
        }
    }
}

/** Non-blocking warning shown on Profile when an identity key could not be loaded or saved. */
@Composable
fun IdentityBanner(kind: IdentityBannerKind, backupKept: Boolean, onGotIt: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().testTag("identity_banner")
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    when (kind) {
                        IdentityBannerKind.UNREADABLE -> R.string.meshup_identity_banner_unreadable
                        IdentityBannerKind.NOT_PERSISTED -> R.string.meshup_identity_banner_not_persisted
                    }
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            if (kind == IdentityBannerKind.UNREADABLE && backupKept) {
                Text(
                    stringResource(R.string.meshup_identity_banner_backup_kept),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onGotIt, modifier = Modifier.testTag("identity_banner_got_it")) {
                Text(stringResource(R.string.meshup_identity_banner_got_it))
            }
        }
    }
}

/** Confirm stays disabled until the user types `reset`. Confirming runs the existing panic wipe. */
@Composable
fun ResetIdentityDialog(vm: IdentityViewModel, onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    val text by vm.resetText.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.meshup_identity_reset_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.meshup_identity_reset_body))
                OutlinedTextField(
                    value = text,
                    onValueChange = vm::onResetTextChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.meshup_identity_reset_type_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("identity_reset_field")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (vm.confirmReset()) onConfirmed() },
                enabled = vm.canConfirmReset(text),
                modifier = Modifier.testTag("identity_reset_confirm")
            ) { Text(stringResource(R.string.meshup_identity_reset_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.meshup_identity_reset_cancel)) }
        }
    )
}

/** Opens a system settings screen; falls back to the app details page if none can handle it. */
private fun Context.openSystemSettings(action: String) {
    val primary = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(primary)
    } catch (_: Exception) {
        runCatching { startActivity(fallback) }
    }
}
