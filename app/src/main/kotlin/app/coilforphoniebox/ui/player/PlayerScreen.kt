package app.coilforphoniebox.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.ui.components.CoverArt
import app.coilforphoniebox.ui.components.formatDuration
import app.coilforphoniebox.ui.components.formatNumber

/**
 * The start screen: cover, title, progress, transport, volume.
 *
 * Primary carries the transport controls, tertiary marks the favourite star — the warm
 * counterpoint keeps an all-green interface from becoming fatiguing (§10.7).
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrub by viewModel.scrubPosition.collectAsStateWithLifecycle()
    val volumeTarget by viewModel.volumeTarget.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        CoverArt(
            url = state.coverUrl,
            contentDescription = stringResource(R.string.player_cover_art),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            placeholderIconSize = 64.dp,
        )

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.status.title
                        ?: if (state.status.hasContent) {
                            stringResource(R.string.player_no_title)
                        } else {
                            stringResource(R.string.player_nothing_playing)
                        },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(state.status.artist, state.status.album)
                    .distinct()
                    .joinToString(" · ")
                    .ifBlank { null }
                if (subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (state.canFavourite) {
                val saved = state.currentFavorite != null
                IconButton(onClick = viewModel::toggleFavorite) {
                    Icon(
                        imageVector = if (saved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = stringResource(
                            if (saved) R.string.action_favourite_remove else R.string.action_favourite_add,
                        ),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        ProgressRow(
            elapsedSeconds = scrub?.toDouble() ?: state.status.elapsedSeconds,
            durationSeconds = state.status.durationSeconds,
            onScrub = viewModel::onScrub,
            onScrubFinished = viewModel::onScrubFinished,
        )

        Spacer(Modifier.height(8.dp))

        TransportRow(
            isPlaying = state.status.state == PlaybackState.PLAY,
            shuffle = state.status.shuffle,
            repeat = state.status.repeat,
            onToggle = viewModel::toggle,
            onNext = viewModel::next,
            onPrevious = viewModel::previous,
            onShuffle = viewModel::toggleShuffle,
            onRepeat = viewModel::cycleRepeat,
        )

        Spacer(Modifier.height(20.dp))

        VolumeRow(
            // The dragged value wins while a drag is in progress, so the slider follows the
            // finger instead of the box's four-per-second reports.
            level = volumeTarget ?: state.volume.level,
            maxLevel = state.volume.maxLevel,
            muted = state.volume.muted,
            onChange = viewModel::onVolumeChange,
            onChangeFinished = viewModel::onVolumeChangeFinished,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProgressRow(
    elapsedSeconds: Double?,
    durationSeconds: Double?,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
) {
    // Web radio reports no duration at all, in which case there is nothing to seek in.
    val duration = durationSeconds?.takeIf { it > 0 }
    val elapsed = (elapsedSeconds ?: 0.0).coerceIn(0.0, duration ?: 0.0)

    Column {
        Slider(
            value = elapsed.toFloat(),
            onValueChange = onScrub,
            onValueChangeFinished = onScrubFinished,
            valueRange = 0f..(duration?.toFloat() ?: 1f),
            enabled = duration != null,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatDuration(elapsed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TransportRow(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleIcon(
            icon = Icons.Rounded.Shuffle,
            description = stringResource(R.string.action_shuffle),
            active = shuffle,
            onClick = onShuffle,
        )

        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.action_previous),
                modifier = Modifier.size(32.dp),
            )
        }

        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier.size(64.dp),
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(64.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.action_pause else R.string.action_play,
                    ),
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.action_next),
                modifier = Modifier.size(32.dp),
            )
        }

        ToggleIcon(
            icon = if (repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            description = stringResource(
                if (repeat == RepeatMode.ONE) R.string.action_repeat_one else R.string.action_repeat,
            ),
            active = repeat != RepeatMode.OFF,
            onClick = onRepeat,
        )
    }
}

@Composable
private fun ToggleIcon(
    icon: ImageVector,
    description: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

@Composable
private fun VolumeRow(
    level: Int,
    maxLevel: Int,
    muted: Boolean,
    onChange: (Int) -> Unit,
    onChangeFinished: () -> Unit,
) {
    val percent = if (maxLevel <= 0) 0 else (level * 100) / maxLevel
    val spokenVolume = stringResource(R.string.volume_percent, percent)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            // The volume icons are the mirrored variants: no right-to-left locale is
            // planned, but not breaking one is nearly free (§12.3).
            imageVector = if (muted || level == 0) {
                Icons.AutoMirrored.Rounded.VolumeMute
            } else {
                Icons.AutoMirrored.Rounded.VolumeUp
            },
            contentDescription = stringResource(R.string.action_volume),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Slider(
            value = level.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            onValueChangeFinished = onChangeFinished,
            valueRange = 0f..maxLevel.coerceAtLeast(1).toFloat(),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = spokenVolume },
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = formatNumber(level),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 28.dp),
        )
    }
}
