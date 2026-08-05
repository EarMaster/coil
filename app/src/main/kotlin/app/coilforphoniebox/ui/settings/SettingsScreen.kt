package app.coilforphoniebox.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coilforphoniebox.BuildConfig
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.model.ThemeMode
import app.coilforphoniebox.ui.components.ActionRow
import app.coilforphoniebox.ui.components.GroupLabel
import app.coilforphoniebox.ui.components.RadioRow
import app.coilforphoniebox.ui.components.SectionDivider
import app.coilforphoniebox.ui.components.SectionHeader
import app.coilforphoniebox.ui.components.SwitchRow
import app.coilforphoniebox.ui.components.formatNumber
import kotlinx.coroutines.launch

/**
 * Global settings, plus the actions that operate on whichever box is active.
 *
 * Configuring the boxes themselves is one row from here and a screen of its own (§7.5): mixing
 * "which box" with "this box's address" in one list was the part people read twice.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onAddBox: () -> Unit,
    onManageBoxes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
        SectionHeader(stringResource(R.string.boxes_title))

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
            // One row for all of it — every box, its address and ports, its link, removing it.
            // The names are the summary: with one box that reads as "Boxes — Living room", and
            // with three it says which three without opening anything.
            ActionRow(
                title = stringResource(R.string.settings_manage_boxes),
                subtitle = state.boxes.joinToString(separator = ", ") { it.displayName },
                onClick = onManageBoxes,
            )

            SectionDivider()
            // Library actions, not box configuration: they run against the box the app is
            // controlling right now, which is why they stay here rather than moving to a box's
            // own page, where three of four pages could not offer them.
            SectionHeader(stringResource(R.string.settings_section_library))

            if (state.boxes.size > 1) {
                Text(
                    text = stringResource(R.string.settings_library_active_box, box.displayName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ActionRow(
                title = stringResource(R.string.settings_rescan),
                subtitle = stringResource(R.string.settings_rescan_summary),
                onClick = viewModel::rescanLibrary,
            )

            // Says plainly what it costs. A crawl shares the socket the box uses for its card
            // reader, so this is the user's decision to make, not something to slip in (§6).
            val indexState by viewModel.indexState.collectAsStateWithLifecycle()
            ActionRow(
                title = stringResource(
                    if (indexState.running) R.string.settings_index_stop
                    else R.string.settings_index,
                ),
                subtitle = if (indexState.running) {
                    stringResource(
                        R.string.settings_index_progress,
                        formatNumber(indexState.foldersScanned),
                    )
                } else {
                    stringResource(R.string.settings_index_summary)
                },
                onClick = {
                    if (indexState.running) viewModel.stopIndexing() else viewModel.indexLibrary()
                },
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
