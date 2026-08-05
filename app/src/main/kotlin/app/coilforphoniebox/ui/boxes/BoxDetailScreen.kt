package app.coilforphoniebox.ui.boxes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coilforphoniebox.R
import app.coilforphoniebox.ui.components.ActionRow
import app.coilforphoniebox.ui.components.SectionDivider
import app.coilforphoniebox.ui.components.SectionHeader
import app.coilforphoniebox.ui.components.shareLink

/**
 * One box: what it is called, where it is, and how to get rid of it.
 *
 * Everything here addresses this box by id, whether it is the active one or not — which is why
 * it is reachable at all. On the settings screen these fields could only ever describe the
 * active box, so editing a second box meant switching to it first.
 */
@Composable
fun BoxDetailScreen(
    viewModel: BoxesViewModel,
    boxId: String,
    onRemoved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val box = state.boxes.firstOrNull { it.id == boxId }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var showRemoveDialog by remember { mutableStateOf(false) }

    // Null while the flows are still warming up, and after a removal for the frame before the
    // back stack pops. Both are moments, not states worth drawing.
    if (box == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = box.displayName,
            style = MaterialTheme.typography.headlineSmall,
        )

        if (box.id == state.activeBoxId) {
            Text(
                text = stringResource(R.string.boxes_is_active),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            ActionRow(
                title = stringResource(R.string.boxes_make_active),
                subtitle = stringResource(R.string.boxes_make_active_summary),
                onClick = { viewModel.selectBox(box.id) },
            )
        }

        SectionHeader(stringResource(R.string.boxes_section_connection))

        BoxFields(
            initialName = box.displayName,
            initialHost = box.host,
            initialRpcPort = box.rpcPort,
            initialPubPort = box.pubPort,
            onSave = { host, rpcPort, pubPort, displayName ->
                viewModel.updateBox(box.id, host, rpcPort, pubPort, displayName)
            },
        )

        SectionDivider()

        val boxLink = remember(box.id) { viewModel.openLinkFor(box) }
        ActionRow(
            title = stringResource(R.string.settings_box_copy_link),
            subtitle = stringResource(R.string.settings_box_link_summary),
            onClick = {
                clipboard.setText(AnnotatedString(boxLink))
                viewModel.onLinkCopied()
            },
        )
        ActionRow(
            title = stringResource(R.string.settings_box_share_link),
            onClick = { context.shareLink(boxLink, box.displayName) },
        )

        SectionDivider()

        ActionRow(
            title = stringResource(R.string.settings_remove_box),
            onClick = { showRemoveDialog = true },
            destructive = true,
        )

        Spacer(Modifier.height(32.dp))
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.settings_remove_box)) },
            text = {
                Text(stringResource(R.string.settings_remove_box_confirm, box.displayName))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRemoveDialog = false
                        viewModel.removeBox(box.id)
                        onRemoved()
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
}
