package com.familyguard.feature.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File

/**
 * P17 — Settings: PIN/biometric, retention & deletion, export, privacy
 * policy, consent record, sign out and account deletion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Hand the freshly written export to whatever can handle JSON.
    val exportPath = state.exportPath
    LaunchedEffect(exportPath) {
        if (exportPath != null) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(exportPath),
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                context.startActivity(Intent.createChooser(send, "FamilyGuard export"))
            }
            viewModel.clearExportPath()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    if (onBack != null) {
                        TextButton(onClick = onBack) { Text("←") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            state.banner?.let { banner ->
                SettingsBanner(
                    message = stringResource(banner),
                    onDismiss = viewModel::dismissBanner,
                )
                Spacer(Modifier.height(8.dp))
            }

            SectionTitle(stringResource(R.string.settings_security))

            SettingsRow(
                title = stringResource(
                    if (state.pinSet) R.string.settings_change_pin else R.string.settings_setup_pin,
                ),
                subtitle = stringResource(R.string.settings_pin_hint),
                onClick = viewModel::showPinEditor,
            )

            if (state.pinSet) {
                SettingsToggleRow(
                    title = stringResource(R.string.settings_biometric),
                    checked = state.biometricEnabled,
                    enabled = state.biometricAvailable || state.biometricEnabled,
                    onCheckedChange = viewModel::setBiometricEnabled,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SectionTitle(stringResource(R.string.settings_data))

            SettingsRow(
                title = stringResource(R.string.settings_export),
                subtitle = stringResource(R.string.settings_export_hint),
                enabled = !state.busy,
                onClick = viewModel::exportData,
            )

            SettingsRow(
                title = stringResource(R.string.settings_privacy),
                onClick = { viewModel.showPrivacy(true) },
            )

            SettingsRow(
                title = stringResource(R.string.settings_consent),
                subtitle = state.consent.firstOrNull()?.createdAt
                    ?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.settings_consent_none),
                onClick = { viewModel.showPrivacy(true) },
            )

            SettingsRow(
                title = stringResource(R.string.settings_delete),
                subtitle = stringResource(R.string.settings_delete_hint),
                danger = true,
                enabled = !state.busy,
                onClick = { viewModel.showDeleteConfirm(true) },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            OutlinedButton(
                onClick = viewModel::signOut,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(stringResource(R.string.settings_sign_out))
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (state.showPinEditor) {
        PinEditorDialog(
            onSave = viewModel::savePin,
            onDismiss = viewModel::hidePinEditor,
        )
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { viewModel.showDeleteConfirm(false) },
            title = { Text(stringResource(R.string.settings_delete_title)) },
            text = { Text(stringResource(R.string.settings_delete_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::deleteAccount) {
                    Text(stringResource(R.string.settings_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showDeleteConfirm(false) }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (state.showPrivacy) {
        AlertDialog(
            onDismissRequest = { viewModel.showPrivacy(false) },
            title = { Text(stringResource(R.string.settings_privacy)) },
            text = { Text(stringResource(R.string.settings_privacy_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.showPrivacy(false) }) {
                    Text(stringResource(R.string.settings_dismiss))
                }
            },
        )
    }
}

/** Two-step 6-digit entry: choose, then confirm. Mismatch restarts. */
@Composable
private fun PinEditorDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var first by rememberSaveable { mutableStateOf("") }
    var stage by rememberSaveable { mutableStateOf("choose") }
    var entered by rememberSaveable { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (stage == "choose") R.string.gate_title_setup else R.string.gate_title_confirm,
                ),
            )
        },
        text = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(PinPolicy.LENGTH) { index ->
                        Surface(
                            modifier = Modifier
                                .height(14.dp)
                                .padding(0.dp),
                            shape = MaterialTheme.shapes.small,
                            color = if (index < entered.length) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {}
                    }
                }
                if (mismatch) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.gate_pin_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Keypad(
                    onDigit = { digit ->
                        if (entered.length < PinPolicy.LENGTH) {
                            entered += digit
                            mismatch = false
                            if (entered.length == PinPolicy.LENGTH) {
                                if (stage == "choose") {
                                    first = entered
                                    entered = ""
                                    stage = "confirm"
                                } else if (entered == first) {
                                    onSave(entered)
                                } else {
                                    first = ""
                                    entered = ""
                                    stage = "choose"
                                    mismatch = true
                                }
                            }
                        }
                    },
                    onBackspace = { entered = entered.dropLast(1) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (danger) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        ) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("×") }
        }
    }
}
