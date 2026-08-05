package app.coilforphoniebox.ui.boxes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coilforphoniebox.R

/**
 * Used both for onboarding and for adding a second box later — the same job, so the same
 * screen. One sentence of explanation, a scan list, a manual field. No carousel, no
 * feature tour, no permission pre-briefing (§11.2).
 */
@Composable
fun AddBoxScreen(
    viewModel: AddBoxViewModel,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startScan() }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.add_box_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.add_box_discovered),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (state.scanning) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }

        Spacer(Modifier.height(4.dp))

        if (state.candidates.isEmpty()) {
            Text(
                text = stringResource(
                    if (state.scanning) R.string.add_box_scanning else R.string.add_box_none_found,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.candidates.forEach { candidate ->
                ListItem(
                    headlineContent = { Text(candidate.serviceName) },
                    supportingContent = { Text(candidate.host) },
                    leadingContent = { Icon(Icons.Rounded.Router, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { viewModel.selectCandidate(candidate) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.add_box_manual),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.host,
            onValueChange = viewModel::onHostChange,
            label = { Text(stringResource(R.string.field_host)) },
            placeholder = { Text(stringResource(R.string.field_host_hint)) },
            isError = state.hostError,
            supportingText = if (state.hostError) {
                { Text(stringResource(R.string.error_host_empty)) }
            } else {
                null
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = { Text(stringResource(R.string.field_display_name)) },
            placeholder = { Text(stringResource(R.string.field_display_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.rpcPort,
                onValueChange = viewModel::onRpcPortChange,
                label = { Text(stringResource(R.string.field_rpc_port)) },
                isError = state.portError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.pubPort,
                onValueChange = viewModel::onPubPortChange,
                label = { Text(stringResource(R.string.field_pub_port)) },
                isError = state.portError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        if (state.portError) {
            Text(
                text = stringResource(R.string.error_port_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(16.dp))

        ConnectionTestRow(state = state, onTest = viewModel::testConnection)

        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onCancel != null) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            }
            Button(onClick = viewModel::save, enabled = state.canSave) {
                Text(stringResource(R.string.action_save))
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ConnectionTestRow(
    state: AddBoxViewModel.State,
    onTest: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onTest, enabled = state.testState != AddBoxViewModel.TestState.RUNNING) {
                Text(stringResource(R.string.action_test_connection))
            }
            if (state.testState == AddBoxViewModel.TestState.RUNNING) {
                Spacer(Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.test_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (state.testState) {
            AddBoxViewModel.TestState.REACHABLE -> Text(
                text = state.reportedVersion?.let { stringResource(R.string.test_success, it) }
                    ?: stringResource(R.string.test_success_unknown_version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            AddBoxViewModel.TestState.UNREACHABLE -> Column {
                Text(
                    text = stringResource(R.string.error_connection_timeout),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                // Client isolation is the failure that looks like a bug rather than a
                // configuration issue, so it is named here and only here (§11.2).
                Text(
                    text = stringResource(R.string.error_connection_timeout_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> Unit
        }
    }
}
