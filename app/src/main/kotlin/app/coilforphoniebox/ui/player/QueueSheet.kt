package app.coilforphoniebox.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.QueueEntry
import app.coilforphoniebox.ui.components.EmptyState
import app.coilforphoniebox.ui.components.formatDuration
import app.coilforphoniebox.ui.components.formatNumber

/**
 * What the box has queued, and a way into the middle of it.
 *
 * The player shows one song, and `playerstatus` carries only a position and a length — so before
 * this there was no way to see what came next, and no way to reach chapter seven of an audio play
 * except tapping ⏭ six times. That is the case this exists for.
 *
 * **The box cannot jump to a queue position**, which shapes what a tap does. There is no
 * `play(pos)` in the Phoniebox RPC surface, and `play_single` — the one command that starts a
 * named track — clears the queue first, so using it would leave the box silent at the end of
 * whichever chapter was picked. Instead the repository walks the queue with `next`/`prev`, which
 * keeps it whole and takes a visible moment; hence the spinner on the row being aimed at and the
 * cancel beside it.
 *
 * No artwork per row. Resolving a cover is an RPC each on the socket the box shares with its card
 * reader (§6), and a numbered list of chapters reads better as text anyway — this deliberately
 * looks like `TrackRow` in the library rather than like the favourites grid.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: PlayerViewModel.QueueState,
    currentPosition: Int?,
    onJumpTo: (Int) -> Unit,
    onCancelJump: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.queue_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (queue.entries.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = pluralStringResource(
                                R.plurals.queue_track_count,
                                queue.entries.size,
                                queue.entries.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Only while a walk is in flight, which is the only thing here worth calling off.
                if (queue.jumpTarget != null) {
                    TextButton(onClick = onCancelJump) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                queue.entries.isNotEmpty() -> QueueList(
                    entries = queue.entries,
                    currentPosition = currentPosition,
                    jumpTarget = queue.jumpTarget,
                    onJumpTo = onJumpTo,
                )

                queue.loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                // Nothing to show and nothing coming. The box has to be asked for its queue —
                // it is never published — so this is a failed request, not an empty playlist.
                else -> EmptyState(
                    icon = Icons.Rounded.QueueMusic,
                    title = stringResource(R.string.queue_unavailable_title),
                    body = stringResource(R.string.queue_unavailable_body),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onRetry,
                )
            }
        }
    }
}

@Composable
private fun QueueList(
    entries: List<QueueEntry>,
    currentPosition: Int?,
    jumpTarget: Int?,
    onJumpTo: (Int) -> Unit,
) {
    val listState = rememberLazyListState()

    // Opening on track one of a twenty-chapter audio play would hide the whole point of the
    // sheet. Follows the box afterwards too, so a track change while it is open keeps up.
    LaunchedEffect(currentPosition, entries.size) {
        val index = entries.indexOfFirst { it.position == currentPosition }
        if (index >= 0) listState.scrollToItem(index)
    }

    LazyColumn(state = listState) {
        items(entries, key = { it.songId ?: "${it.position}:${it.url}" }) { entry ->
            QueueRow(
                entry = entry,
                playing = entry.position == currentPosition,
                // Only the row being walked to; the rest stay tappable so a mind can be changed
                // mid-walk without cancelling first.
                pending = entry.position == jumpTarget,
                onClick = { onJumpTo(entry.position) },
            )
        }
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    playing: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val colour = if (playing) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (playing) {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A fixed slot for the number, the playing marker and the walk's spinner, so rows do not
        // shift sideways as any of the three appears.
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                pending -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                // The icon carries the description rather than the row: a `semantics` block on
                // the row would replace the merged title and duration instead of adding to
                // them, so "Now playing" would be all a screen reader got.
                playing -> Icon(
                    imageVector = Icons.Rounded.VolumeUp,
                    contentDescription = stringResource(R.string.queue_now_playing),
                    tint = colour,
                    modifier = Modifier.size(18.dp),
                )

                else -> Text(
                    // Positions are zero-based on the box and one-based to a reader.
                    text = formatNumber(entry.position + 1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyLarge,
                color = colour,
                fontWeight = if (playing) FontWeight.Medium else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only when it adds something: within one album every row would otherwise repeat the
            // same artist under the same album, which is noise at this density.
            val subtitle = listOfNotNull(entry.artist, entry.album)
                .distinct()
                .joinToString(" · ")
                .ifBlank { null }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // A stream has no duration, so the column is simply absent rather than showing 0:00.
        entry.durationSeconds?.let { seconds ->
            Spacer(Modifier.width(12.dp))
            Text(
                text = formatDuration(seconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
