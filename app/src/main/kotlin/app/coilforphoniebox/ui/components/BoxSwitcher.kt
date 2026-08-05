package app.coilforphoniebox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState

/**
 * Top bar element for the active box.
 *
 * With exactly one box configured this collapses to a plain connection indicator: no
 * one should pay UI complexity for a feature they do not use (§7.5). German and Dutch
 * box names run a third longer than English ones, so the label truncates rather than
 * pushing the bar's actions off the edge (§12.3).
 */
@Composable
fun BoxIndicator(
    activeBox: Box?,
    connection: ConnectionState,
    switchable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = boxLabel(activeBox)
    val spokenState = connectionDescription(activeBox, connection)

    Surface(
        modifier = modifier
            .clip(CircleShape)
            .then(if (switchable) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = spokenState },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(
                start = 10.dp,
                end = if (switchable) 4.dp else 12.dp,
                top = 6.dp,
                bottom = 6.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectionDot(connection)
            Spacer(Modifier.size(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (switchable) {
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = stringResource(R.string.boxes_switch),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun boxLabel(activeBox: Box?): String =
    activeBox?.displayName ?: stringResource(R.string.connection_disconnected)

@Composable
private fun connectionDescription(activeBox: Box?, connection: ConnectionState): String {
    val name = activeBox?.displayName ?: return stringResource(R.string.connection_disconnected)
    return when (connection) {
        ConnectionState.CONNECTED -> stringResource(R.string.connection_connected, name)
        ConnectionState.CONNECTING -> stringResource(R.string.connection_connecting, name)
        ConnectionState.DEGRADED -> stringResource(R.string.connection_degraded, name)
        ConnectionState.DISCONNECTED -> stringResource(R.string.connection_disconnected)
    }
}

/** Decorative: the surrounding element carries the spoken state. */
@Composable
private fun ConnectionDot(connection: ConnectionState) {
    val colour = when (connection) {
        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionState.CONNECTING, ConnectionState.DEGRADED -> MaterialTheme.colorScheme.tertiary
        // Not the error colour: a box that is switched off is a normal state (§10.7).
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Spacer(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(colour),
    )
}

/** Bottom sheet with every configured box, its reachability, and a way to add one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxSwitcherSheet(
    boxes: List<Box>,
    activeBoxId: String?,
    reachability: Map<String, Boolean>,
    onSelect: (String) -> Unit,
    onAddBox: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.boxes_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            boxes.forEach { box ->
                ListItem(
                    headlineContent = {
                        Text(box.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(box.host, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { ReachabilityDot(reachable = reachability[box.id]) },
                    trailingContent = {
                        if (box.id == activeBoxId) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = stringResource(R.string.box_active),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onSelect(box.id) },
                )
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.action_add_box)) },
                leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(onClick = onAddBox),
            )
        }
    }
}

/** Null means "still being probed", which is a third state worth showing honestly. */
@Composable
private fun ReachabilityDot(reachable: Boolean?) {
    val colour = when (reachable) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.outlineVariant
        null -> MaterialTheme.colorScheme.outline
    }
    Spacer(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(colour),
    )
}
