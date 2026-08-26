package app.coilforphoniebox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.PlayerStatus

/** Sits above the navigation bar on every screen except the player itself. */
@Composable
fun MiniPlayer(
    status: PlayerStatus,
    coverUrl: String?,
    /** Same name and same wait as the player's own cover — see `CoverArt`. */
    coverName: String?,
    coverPending: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = progressOf(status)

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(
                    url = coverUrl,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    cornerRadius = 10.dp,
                    placeholderIconSize = 18.dp,
                    fallbackName = coverName,
                    coverPending = coverPending,
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = status.displayTitle ?: stringResource(R.string.player_no_title),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val subtitle = status.artist ?: status.album
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
                IconButton(onClick = onToggle) {
                    Icon(
                        imageVector = if (status.isPlaying) {
                            Icons.Rounded.Pause
                        } else {
                            Icons.Rounded.PlayArrow
                        },
                        contentDescription = stringResource(
                            if (status.isPlaying) R.string.action_pause else R.string.action_play,
                        ),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** Null for content without a duration, such as web radio. */
private fun progressOf(status: PlayerStatus): Float? {
    val duration = status.durationSeconds?.takeIf { it > 0 } ?: return null
    val elapsed = status.elapsedSeconds ?: 0.0
    return (elapsed / duration).coerceIn(0.0, 1.0).toFloat()
}
