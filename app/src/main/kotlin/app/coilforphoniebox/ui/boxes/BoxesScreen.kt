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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.ui.components.SectionDivider

/**
 * Every configured box, one row each, and a way to add another.
 *
 * Its own screen rather than a stretch of the settings list: the box rows used to sit between
 * global settings and the active box's fields, which read as one long list where picking a box,
 * adding a box and editing a box were three neighbouring things that happened to look alike.
 * Tapping a row here opens that box; switching boxes stays where it always was, in the top bar.
 */
@Composable
fun BoxesScreen(
    viewModel: BoxesViewModel,
    onOpenBox: (String) -> Unit,
    onAddBox: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.boxes_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.boxes_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        state.boxes.forEach { box ->
            BoxRow(
                box = box,
                active = box.id == state.activeBoxId,
                onClick = { onOpenBox(box.id) },
            )
        }

        Column(Modifier.padding(horizontal = 20.dp)) { SectionDivider() }

        ListItem(
            headlineContent = { Text(stringResource(R.string.action_add_box)) },
            leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .clickable(onClick = onAddBox)
                .padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun BoxRow(box: Box, active: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(box.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(box.host, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { Icon(Icons.Rounded.Router, contentDescription = null) },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Which box the app is controlling is stated, not offered: the row opens the
                // box rather than switching to it, so nothing here looks like a choice.
                if (active) {
                    Text(
                        text = stringResource(R.string.box_active),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
    )
}
