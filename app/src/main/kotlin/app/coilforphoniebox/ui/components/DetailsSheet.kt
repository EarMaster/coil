package app.coilforphoniebox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.coilforphoniebox.R

/** One label/value line in a [DetailsSheet]. Values are content from the box, shown as-is. */
data class DetailRow(val label: String, val value: String)

/**
 * What Coil knows about one library item.
 *
 * Everything here comes from the cache: opening details must not put a request on the RPC
 * socket the box also uses for its card reader (§6). A field the cache does not have is
 * left out rather than guessed at, which is why [rows] is assembled by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(
    title: String,
    subtitle: String?,
    coverUrl: String?,
    placeholderIcon: ImageVector,
    rows: List<DetailRow>,
    /** Freshness caption, or null when this item has never been fetched. */
    footnote: String?,
    favouriteLabel: String,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverArt(
                    url = coverUrl,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    cornerRadius = 14.dp,
                    placeholderIconSize = 32.dp,
                    placeholderIcon = placeholderIcon,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            rows.forEach { row ->
                DetailLine(row)
                Spacer(Modifier.height(10.dp))
            }

            if (footnote != null) {
                Text(
                    text = footnote,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onPlay, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(text = stringResource(R.string.action_play), maxLines = 1)
                }
                OutlinedButton(onClick = onToggleFavourite, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = if (favourite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = favouriteLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Label above value rather than side by side: a German label and a long folder path both
 * need the full width, and stacking them means neither has to be truncated (§12.3).
 */
@Composable
private fun DetailLine(row: DetailRow) {
    Column {
        Text(
            text = row.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = row.value, style = MaterialTheme.typography.bodyMedium)
    }
}
