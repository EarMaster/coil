package app.coilforphoniebox.ui.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeMute
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.ui.components.CoverArt
import app.coilforphoniebox.ui.components.FavoriteMenuItem
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
    val timerRemaining by viewModel.sleepTimerRemaining.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    var timerSheetOpen by remember { mutableStateOf(false) }
    var queueSheetOpen by remember { mutableStateOf(false) }

    if (queueSheetOpen) {
        QueueSheet(
            queue = queue,
            currentPosition = state.status.playlistPosition,
            onJumpTo = viewModel::jumpTo,
            onCancelJump = viewModel::cancelJump,
            onRetry = viewModel::refreshQueue,
            onDismiss = {
                queueSheetOpen = false
                // Closing the sheet abandons a walk in progress: it is the only place its
                // progress is visible, and a jump nobody can see arriving or cancel is worse
                // than one that stops where it is.
                viewModel.cancelJump()
            },
        )
    }

    if (timerSheetOpen) {
        SleepTimerSheet(
            remainingSeconds = timerRemaining,
            onPick = { minutes ->
                timerSheetOpen = false
                viewModel.startSleepTimer(minutes)
            },
            onCancel = {
                timerSheetOpen = false
                viewModel.cancelSleepTimer()
            },
            onDismiss = { timerSheetOpen = false },
        )
    }

    // One RPC on opening, so the sheet shows the truth even if the timer was set from the
    // box's own web UI before Coil connected.
    val openTimer = {
        viewModel.refreshSleepTimer()
        timerSheetOpen = true
    }

    // No RPC here, unlike the timer: the queue is already resolved and kept in step in the
    // background, once per queue change rather than once per opening (§6).
    val openQueue = { queueSheetOpen = true }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Two panes on a large screen, and also on any window that is merely wider than it is
        // tall: a phone on its side has width to spare and no height at all, which is the same
        // problem a tablet has and wants the same answer. A 7-inch tablet held upright stays in
        // one column, where two panes would only be two cramped ones.
        val wide = maxWidth >= WIDE_WIDTH || (maxWidth >= MEDIUM_WIDTH && maxWidth > maxHeight)

        if (wide) {
            WidePlayer(
                // Bounded by the height as well as the width: on a landscape tablet the
                // limiting dimension is the short one, and a square that ignores it is exactly
                // how this screen used to push its own controls off the bottom.
                coverSize = minOf(maxWidth * WIDE_COVER_FRACTION, maxHeight - WIDE_COVER_INSET),
                state = state,
                scrub = scrub,
                volumeTarget = volumeTarget,
                timerRemaining = timerRemaining,
                viewModel = viewModel,
                onOpenTimer = openTimer,
                onOpenQueue = openQueue,
            )
        } else {
            CompactPlayer(
                // The width still decides on a phone. The height only takes over when the
                // window is short — a phone in landscape, or a small floating window — where a
                // full-width cover would leave no room for anything else.
                coverSize = minOf(
                    maxWidth - COMPACT_PADDING * 2,
                    maxHeight * COMPACT_COVER_FRACTION,
                ),
                state = state,
                scrub = scrub,
                volumeTarget = volumeTarget,
                timerRemaining = timerRemaining,
                viewModel = viewModel,
                onOpenTimer = openTimer,
                onOpenQueue = openQueue,
            )
        }
    }
}

/** Phone-shaped: cover on top, everything else stacked under it. */
@Composable
private fun CompactPlayer(
    coverSize: Dp,
    state: PlayerViewModel.State,
    scrub: Float?,
    volumeTarget: Int?,
    timerRemaining: Int?,
    viewModel: PlayerViewModel,
    onOpenTimer: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = COMPACT_PADDING),
    ) {
        CoverArt(
            url = state.coverUrl,
            contentDescription = stringResource(R.string.player_cover_art),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .size(coverSize),
            placeholderIconSize = 64.dp,
            fallbackName = state.coverName,
            coverPending = state.coverPending,
        )

        Spacer(Modifier.height(20.dp))

        TitleBlock(state = state, style = MaterialTheme.typography.titleLarge, viewModel = viewModel)

        Spacer(Modifier.height(16.dp))

        PlayerControls(
            state = state,
            scrub = scrub,
            volumeTarget = volumeTarget,
            timerRemaining = timerRemaining,
            viewModel = viewModel,
            onOpenTimer = onOpenTimer,
            onOpenQueue = onOpenQueue,
        )

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Tablet-shaped: cover beside the controls rather than above them.
 *
 * A family tablet is one of the places this app belongs — propped in a kitchen, tapped by
 * whoever walks past — and there the single column was actively bad: the cover took the whole
 * width, which on a landscape screen is more than the whole height, and the transport controls
 * ended up below the fold on the one screen whose entire job is the transport controls.
 */
@Composable
private fun WidePlayer(
    coverSize: Dp,
    state: PlayerViewModel.State,
    scrub: Float?,
    volumeTarget: Int?,
    timerRemaining: Int?,
    viewModel: PlayerViewModel,
    onOpenTimer: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(
            url = state.coverUrl,
            contentDescription = stringResource(R.string.player_cover_art),
            modifier = Modifier.size(coverSize),
            placeholderIconSize = 96.dp,
            fallbackName = state.coverName,
            coverPending = state.coverPending,
        )

        Spacer(Modifier.width(32.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                // Scrollable in its own right, so a short window loses the bottom of the
                // controls rather than clipping them out of reach.
                .verticalScroll(rememberScrollState()),
        ) {
            // The title carries the pane, so it steps up a size — at this width `titleLarge`
            // reads as a caption rather than as the name of what is playing.
            TitleBlock(state = state, style = MaterialTheme.typography.headlineSmall, viewModel = viewModel)

            Spacer(Modifier.height(24.dp))

            PlayerControls(
                state = state,
                scrub = scrub,
                volumeTarget = volumeTarget,
                timerRemaining = timerRemaining,
                viewModel = viewModel,
                onOpenTimer = onOpenTimer,
                onOpenQueue = onOpenQueue,
            )
        }
    }
}

/** Title, artist and album, with the favourite star beside them. */
@Composable
private fun TitleBlock(
    state: PlayerViewModel.State,
    style: TextStyle,
    viewModel: PlayerViewModel,
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(
                text = state.status.title
                    ?: if (state.status.hasContent) {
                        stringResource(R.string.player_no_title)
                    } else {
                        stringResource(R.string.player_nothing_playing)
                    },
                style = style,
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
            FavouriteControl(
                folderSaved = state.currentFavorite != null,
                trackSaved = state.currentTrackFavorite != null,
                folderLabel = state.folderLabel.takeIf { state.canFavouriteFolder },
                trackLabel = state.trackLabel.takeIf { state.canFavouriteTrack },
                onToggleFolder = viewModel::toggleFolderFavorite,
                onToggleTrack = viewModel::toggleTrackFavorite,
            )
        }
    }
}

/**
 * Progress, transport, the timer line and volume — identical in both layouts.
 *
 * Kept as one composable rather than repeated in each: these four belong together, and a
 * control that appeared on a phone but not on a tablet would be a bug nobody notices for
 * months.
 */
@Composable
private fun ColumnScope.PlayerControls(
    state: PlayerViewModel.State,
    scrub: Float?,
    volumeTarget: Int?,
    timerRemaining: Int?,
    viewModel: PlayerViewModel,
    onOpenTimer: () -> Unit,
    onOpenQueue: () -> Unit,
) {
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
        timerRunning = state.sleepTimer.running,
        anyOptionActive = state.anyOptionActive,
        queueLength = state.status.playlistLength,
        onToggle = viewModel::toggle,
        onNext = viewModel::next,
        onPrevious = viewModel::previous,
        onShuffle = viewModel::toggleShuffle,
        onRepeat = viewModel::cycleRepeat,
        onOpenTimer = onOpenTimer,
        onOpenQueue = onOpenQueue,
    )

    // Only while a timer is running: a countdown to something stopping is worth a line of its
    // own, and there is nothing to say when nothing is scheduled.
    timerRemaining?.let { seconds ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(
                R.string.player_sleep_timer_remaining,
                formatDuration(seconds.toDouble()),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    Spacer(Modifier.height(20.dp))

    VolumeRow(
        // The dragged value wins while a drag is in progress, so the slider follows the finger
        // instead of the box's four-per-second reports.
        level = volumeTarget ?: state.volume.level,
        maxLevel = state.volume.maxLevel,
        muted = state.volume.muted,
        onChange = viewModel::onVolumeChange,
        onChangeFinished = viewModel::onVolumeChangeFinished,
    )
}

/**
 * The favourite star.
 *
 * A tap saves the folder — the thing a listener usually means by "save this", and what a
 * card on the box would hold. A long press opens the menu, where folder and track are two
 * separate, named entries: from a playing song the two are genuinely different intentions,
 * and a single star cannot say which one it means.
 *
 * The icon shows the state of what a *tap* does, which is the folder. What the track's own
 * state is, the menu says.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavouriteControl(
    folderSaved: Boolean,
    trackSaved: Boolean,
    folderLabel: String?,
    trackLabel: String?,
    onToggleFolder: () -> Unit,
    onToggleTrack: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        Icon(
            imageVector = if (folderSaved) Icons.Rounded.Star else Icons.Rounded.StarBorder,
            contentDescription = stringResource(
                if (folderSaved) R.string.action_favourite_remove_folder
                else R.string.action_favourite_add_folder,
            ),
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    // Web radio has no folder to save, so there the tap goes straight to
                    // the menu rather than doing nothing.
                    onClick = { if (folderLabel != null) onToggleFolder() else menuOpen = true },
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(R.string.action_favourite_options),
                )
                // 24 dp icon in 12 dp of padding: the 48 dp touch target an IconButton
                // would have given, kept while swapping in the long press.
                .padding(12.dp)
                .size(24.dp),
        )

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (folderLabel != null) {
                FavoriteMenuItem(
                    text = stringResource(
                        if (folderSaved) R.string.action_favourite_remove_folder
                        else R.string.action_favourite_add_folder,
                    ),
                    detail = folderLabel,
                    saved = folderSaved,
                    onClick = {
                        menuOpen = false
                        onToggleFolder()
                    },
                )
            }
            if (trackLabel != null) {
                FavoriteMenuItem(
                    text = stringResource(
                        if (trackSaved) R.string.action_favourite_remove_track
                        else R.string.action_favourite_add_track,
                    ),
                    detail = trackLabel,
                    saved = trackSaved,
                    onClick = {
                        menuOpen = false
                        onToggleTrack()
                    },
                )
            }
        }
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

/**
 * Previous, play and next, with everything else behind one button.
 *
 * Shuffle, repeat and the sleep timer used to be — and, for a timer, would have been —
 * separate icons flanking the transport controls. Three modes competing with the play button
 * is more than this screen can carry, so they live in a menu whose button is tinted when any
 * of them is on. The empty slot at the end keeps the play button centred.
 */
@Composable
private fun TransportRow(
    isPlaying: Boolean,
    shuffle: Boolean,
    repeat: RepeatMode,
    timerRunning: Boolean,
    anyOptionActive: Boolean,
    queueLength: Int,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenTimer: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaybackOptionsMenu(
            shuffle = shuffle,
            repeat = repeat,
            timerRunning = timerRunning,
            anyOptionActive = anyOptionActive,
            onShuffle = onShuffle,
            onRepeat = onRepeat,
            onOpenTimer = onOpenTimer,
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

        // Balances the options button on the other side, whichever of the two it holds — both
        // are an IconButton's own 48 dp, so the play button stays centred either way. There is
        // nothing to open for a web radio stream or a single track, and `playlistLength` is how
        // the box says so.
        if (queueLength > 1) {
            IconButton(onClick = onOpenQueue) {
                Icon(
                    imageVector = Icons.Rounded.QueueMusic,
                    contentDescription = stringResource(R.string.action_show_queue),
                )
            }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

/**
 * Shuffle, repeat and the sleep timer in one menu.
 *
 * Shuffle and repeat leave the menu open, because seeing the state change *is* the feedback
 * for a toggle. The timer opens a sheet instead, so it closes.
 */
@Composable
private fun PlaybackOptionsMenu(
    shuffle: Boolean,
    repeat: RepeatMode,
    timerRunning: Boolean,
    anyOptionActive: Boolean,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onOpenTimer: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { menuOpen = true },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (anyOptionActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = stringResource(R.string.action_playback_options),
            )
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            OptionMenuItem(
                text = stringResource(R.string.action_shuffle),
                icon = Icons.Rounded.Shuffle,
                active = shuffle,
                onClick = onShuffle,
            )
            OptionMenuItem(
                text = stringResource(
                    when (repeat) {
                        RepeatMode.OFF -> R.string.action_repeat
                        RepeatMode.ALL -> R.string.action_repeat_all
                        RepeatMode.ONE -> R.string.action_repeat_one
                    },
                ),
                icon = if (repeat == RepeatMode.ONE) {
                    Icons.Rounded.RepeatOne
                } else {
                    Icons.Rounded.Repeat
                },
                active = repeat != RepeatMode.OFF,
                onClick = onRepeat,
            )
            OptionMenuItem(
                text = stringResource(
                    if (timerRunning) R.string.action_sleep_timer_on
                    else R.string.action_sleep_timer,
                ),
                icon = Icons.Rounded.Bedtime,
                active = timerRunning,
                onClick = {
                    menuOpen = false
                    onOpenTimer()
                },
            )
        }
    }
}

/** A menu row whose icon and colour carry whether the option is currently on. */
@Composable
private fun OptionMenuItem(
    text: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colour = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    DropdownMenuItem(
        text = { Text(text = text, color = if (active) colour else Color.Unspecified) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null, tint = colour) },
        onClick = onClick,
    )
}

/**
 * Durations for the timer that stops playback.
 *
 * It says out loud that the box stays on. The Phoniebox also has shutdown timers, and someone
 * setting a bedtime timer deserves to know which of the two this is — Coil never switches the
 * box off (§16).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SleepTimerSheet(
    remainingSeconds: Int?,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.player_sleep_timer_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.player_sleep_timer_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (remainingSeconds != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(
                        R.string.player_sleep_timer_remaining,
                        formatDuration(remainingSeconds.toDouble()),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(20.dp))

            // Wraps rather than scrolls: a row of durations that runs off the edge hides the
            // longest one, and German labels are the widest of the five locales (§12.3).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TIMER_PRESETS.forEach { minutes ->
                    AssistChip(
                        onClick = { onPick(minutes) },
                        label = {
                            Text(
                                pluralStringResource(
                                    R.plurals.duration_minutes,
                                    minutes,
                                    formatNumber(minutes),
                                ),
                            )
                        },
                    )
                }
            }

            if (remainingSeconds != null) {
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_sleep_timer_cancel))
                }
            }
        }
    }
}

/** Bedtime-shaped: fifteen minutes to two hours, which is one audiobook either way. */
private val TIMER_PRESETS = listOf(15, 30, 45, 60, 90, 120)

/**
 * Where the player stops stacking and starts sitting side by side.
 *
 * Material's "expanded" width class. A 7-inch tablet in portrait (600 dp) stays in one column,
 * where two panes would be two cramped ones; a 10-inch in landscape (1280 dp) splits.
 */
private val WIDE_WIDTH = 840.dp

/**
 * The narrowest window that may still split, when it is wider than it is tall.
 *
 * Material's "medium" width class. Below this there is not enough room for two of anything.
 */
private val MEDIUM_WIDTH = 600.dp

/** How much of a wide window the cover may claim, leaving the rest for the controls. */
private const val WIDE_COVER_FRACTION = 0.42f

/** Breathing room above and below the cover in the wide layout. */
private val WIDE_COVER_INSET = 48.dp

private val COMPACT_PADDING = 20.dp

/** The most of a short window's height the cover may eat before the controls suffer. */
private const val COMPACT_COVER_FRACTION = 0.55f

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
