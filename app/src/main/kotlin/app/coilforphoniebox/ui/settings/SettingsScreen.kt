package app.coilforphoniebox.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coilforphoniebox.BuildConfig
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.model.ThemeMode
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch

/**
 * Split into global settings and settings for the active box, which is the same split the
 * data model makes (§7.2).
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onAddBox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showRemoveDialog by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val content = viewModel.exportSettings()
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
            }.onSuccess { viewModel.onExported() }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val content = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (content != null) viewModel.importSettings(content)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        SectionHeader(stringResource(R.string.settings_section_appearance))

        GroupLabel(stringResource(R.string.settings_theme))
        ThemeMode.entries.forEach { mode ->
            RadioRow(
                label = stringResource(
                    when (mode) {
                        ThemeMode.SYSTEM -> R.string.settings_theme_system
                        ThemeMode.LIGHT -> R.string.settings_theme_light
                        ThemeMode.DARK -> R.string.settings_theme_dark
                    },
                ),
                selected = state.settings.themeMode == mode,
                onSelect = { viewModel.setThemeMode(mode) },
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_colour),
                subtitle = stringResource(R.string.settings_dynamic_colour_summary),
                checked = state.settings.dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
        }

        ActionRow(
            title = stringResource(R.string.settings_language),
            subtitle = stringResource(R.string.settings_language_summary),
            onClick = { context.openAppLocaleSettings() },
        )

        SectionDivider()
        SectionHeader(stringResource(R.string.settings_section_notification))

        SessionModeRows(
            current = state.settings.sessionMode,
            onSelect = viewModel::setSessionMode,
        )

        if (state.settings.sessionMode == SessionMode.AUTOMATIC) {
            state.activeBox?.let { box ->
                SwitchRow(
                    title = stringResource(R.string.settings_auto_session_box),
                    subtitle = box.displayName,
                    checked = box.autoSessionEnabled,
                    onCheckedChange = viewModel::setAutoSessionForActiveBox,
                )
            }

            Text(
                text = stringResource(R.string.settings_session_honest_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            ActionRow(
                title = stringResource(R.string.settings_battery_exemption),
                subtitle = stringResource(R.string.settings_battery_exemption_summary),
                onClick = { context.openBatterySettings() },
            )
        }

        SectionDivider()
        SectionHeader(stringResource(R.string.settings_section_box))

        val box = state.activeBox
        if (box == null) {
            Text(
                text = stringResource(R.string.empty_no_box_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAddBox) { Text(stringResource(R.string.action_add_box)) }
        } else {
            BoxFields(
                initialName = box.displayName,
                initialHost = box.host,
                initialRpcPort = box.rpcPort,
                initialPubPort = box.pubPort,
                onSave = viewModel::updateActiveBox,
            )

            ActionRow(
                title = stringResource(R.string.settings_rescan),
                subtitle = stringResource(R.string.settings_rescan_summary),
                onClick = viewModel::rescanLibrary,
            )

            ActionRow(
                title = stringResource(R.string.settings_remove_box),
                onClick = { showRemoveDialog = true },
                destructive = true,
            )
        }

        SectionDivider()
        SectionHeader(stringResource(R.string.settings_section_data))

        ActionRow(
            title = stringResource(R.string.settings_export),
            subtitle = stringResource(R.string.settings_export_summary),
            onClick = { exportLauncher.launch(EXPORT_FILE_NAME) },
        )
        ActionRow(
            title = stringResource(R.string.settings_import),
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
        )

        SectionDivider()
        SectionHeader(stringResource(R.string.settings_section_about))

        Text(
            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyMedium,
        )
        state.boxVersion?.let { version ->
            Text(
                text = stringResource(R.string.settings_box_version, version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_security_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(32.dp))
    }

    if (showRemoveDialog && state.activeBox != null) {
        val name = state.activeBox?.displayName.orEmpty()
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.settings_remove_box)) },
            text = { Text(stringResource(R.string.settings_remove_box_confirm, name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        viewModel.removeActiveBox()
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SessionModeRows(current: SessionMode, onSelect: (SessionMode) -> Unit) {
    GroupLabel(stringResource(R.string.settings_session_mode))
    RadioRow(
        label = stringResource(R.string.settings_session_mode_app_open),
        subtitle = stringResource(R.string.settings_session_mode_app_open_summary),
        selected = current == SessionMode.APP_ONLY,
        onSelect = { onSelect(SessionMode.APP_ONLY) },
    )
    RadioRow(
        label = stringResource(R.string.settings_session_mode_automatic),
        subtitle = stringResource(R.string.settings_session_mode_automatic_summary),
        selected = current == SessionMode.AUTOMATIC,
        onSelect = { onSelect(SessionMode.AUTOMATIC) },
    )
}

@Composable
private fun BoxFields(
    initialName: String,
    initialHost: String,
    initialRpcPort: Int,
    initialPubPort: Int,
    onSave: (host: String, rpcPort: Int, pubPort: Int, displayName: String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var host by remember(initialHost) { mutableStateOf(initialHost) }
    var rpcPort by remember(initialRpcPort) { mutableStateOf(initialRpcPort.toString()) }
    var pubPort by remember(initialPubPort) { mutableStateOf(initialPubPort.toString()) }

    val changed = name != initialName ||
        host != initialHost ||
        rpcPort != initialRpcPort.toString() ||
        pubPort != initialPubPort.toString()

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.field_display_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text(stringResource(R.string.settings_box_address)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = rpcPort,
            onValueChange = { rpcPort = it },
            label = { Text(stringResource(R.string.field_rpc_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = pubPort,
            onValueChange = { pubPort = it },
            label = { Text(stringResource(R.string.field_pub_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            onSave(
                host,
                rpcPort.toIntOrNull() ?: initialRpcPort,
                pubPort.toIntOrNull() ?: initialPubPort,
                name,
            )
        },
        enabled = changed,
    ) { Text(stringResource(R.string.action_save)) }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

/** Label above a group of radio rows, so each group says what it is choosing. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
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
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ActionRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    destructive: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            // Error is reserved for things that actually go wrong; removing a box is one
            // of the few genuinely destructive actions in the app (§10.7).
            color = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
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

/** Android 13 and later have a per-app language screen; earlier versions do not. */
private fun Context.openAppLocaleSettings() {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", packageName, null))
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    }
    runCatching { startActivity(intent) }
}

private fun Context.openBatterySettings() {
    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

private const val EXPORT_FILE_NAME = "coil-settings.json"
