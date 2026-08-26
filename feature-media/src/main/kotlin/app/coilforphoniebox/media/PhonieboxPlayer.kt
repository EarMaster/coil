package app.coilforphoniebox.media

import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.QueueEntry
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.VolumeStatus
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where [status] sits in [queue], or null when the two cannot be reconciled.
 *
 * The queue and the status arrive independently — the status four times a second, the queue once
 * per queue change — so there is a real window in which the box has moved on to a different
 * album and the cached queue still describes the old one. Two things go wrong if that window is
 * not guarded, and they are of different severity:
 *
 * - `SimpleBasePlayer` **throws** if `currentMediaItemIndex` falls outside the playlist, so a
 *   queue shorter than the reported position would crash the media session rather than look odd.
 * - A position that is merely *pointing at the wrong track* puts another album's title on the
 *   lock screen, which is quieter and arguably worse for being believable.
 *
 * So both are checked: the index has to exist, and the entry there has to be the file the box
 * says it is playing. A null sends the caller back to the single-item timeline, which is what
 * shipped before there was a queue at all and is always safe.
 *
 * A top-level function because it is the load-bearing part of this file and worth testing on its
 * own — `getState()` needs a `Looper` and this does not.
 */
internal fun timelineIndexFor(queue: List<QueueEntry>, status: PlayerStatus): Int? {
    val position = status.playlistPosition ?: return null
    if (position !in queue.indices) return null
    if (queue[position].url != status.file) return null
    return position
}

/** What a media3 seek turns into on the box. */
internal sealed interface SeekIntent {
    /** media3 resolved the seek to nothing, so nothing is sent. */
    data object Ignore : SeekIntent
    data object Next : SeekIntent
    data object Previous : SeekIntent

    /** A jump to a *queue position* — never a timeline index unless the two are the same thing. */
    data class ToQueuePosition(val position: Int) : SeekIntent
    data class WithinTrack(val seconds: Double) : SeekIntent
}

/**
 * What the box should be told, for a seek media3 has already resolved against its own timeline.
 *
 * Three things make this more than a `when` on the command, and all three were wrong before it
 * existed:
 *
 * - **Two index spaces.** `mediaItemIndex` is a *timeline* index. It is also a queue position, but
 *   only while the timeline is the queue — when [timelineIndexFor] declined and the timeline is the
 *   playing song alone, the only index there is means "this song", and handing that to `playAt`
 *   walks the box to queue position zero and restarts the album. Hence [queuePosition], which is
 *   that function's answer: non-null means the indices are queue positions.
 * - **`C.INDEX_UNSET` means "do nothing".** `BasePlayer.ignoreSeek` sends it when there is nothing
 *   to seek to, and sending `next` anyway runs the box's own `end_of_playlist_next_action` at the
 *   end of a queue (§"The queue"). It only carries that meaning in the real queue, though: a
 *   one-item timeline has no next or previous item, so media3 says "unset" about every one of them
 *   and the signal is noise. With [shuffle] on it is noise too — media3's timeline has no shuffle
 *   order, so it reports the last *position* as the end even though MPD would pick another track.
 * - **Previous is position-dependent.** Past `maxSeekToPreviousPosition` (media3's default three
 *   seconds) "previous" means restarting the current track, which media3 passes down as position
 *   zero on the current index. In the fallback timeline it cannot make that call for us, so
 *   [elapsedSeconds] makes it here against the same boundary.
 *
 * A top-level function for the same reason [timelineIndexFor] is one: `getState()` needs a `Looper`
 * and this does not.
 */
internal fun seekIntentFor(
    seekCommand: Int,
    mediaItemIndex: Int,
    positionMs: Long,
    queuePosition: Int?,
    shuffle: Boolean,
    elapsedSeconds: Double?,
): SeekIntent {
    val queued = queuePosition != null
    val resolvedToNothing = mediaItemIndex == C.INDEX_UNSET && queued && !shuffle

    return when (seekCommand) {
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
            if (resolvedToNothing) SeekIntent.Ignore else SeekIntent.Next

        Player.COMMAND_SEEK_TO_PREVIOUS -> when {
            resolvedToNothing -> SeekIntent.Ignore
            queued ->
                if (mediaItemIndex == queuePosition && positionMs == 0L) {
                    SeekIntent.WithinTrack(0.0)
                } else {
                    SeekIntent.Previous
                }

            (elapsedSeconds ?: 0.0) > MAX_SEEK_TO_PREVIOUS_SECONDS -> SeekIntent.WithinTrack(0.0)
            else -> SeekIntent.Previous
        }

        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
            if (resolvedToNothing) SeekIntent.Ignore else SeekIntent.Previous

        // A seek naming an item is a jump to that queue position — a tap in Android Auto's
        // queue, or "play track five". Any `positionMs` that came with it is dropped: the box
        // cannot arrive at a position *and* an offset in one command, and getting to the right
        // track matters more than starting it at second 30.
        Player.COMMAND_SEEK_TO_MEDIA_ITEM ->
            if (queued && mediaItemIndex >= 0) {
                SeekIntent.ToQueuePosition(mediaItemIndex)
            } else {
                // The one item of a fallback timeline is the playing song, so this is a seek
                // inside it — commonly back to the start.
                withinTrack(positionMs)
            }

        // COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, and anything later media3 routes the same way.
        else -> withinTrack(positionMs)
    }
}

private fun withinTrack(positionMs: Long): SeekIntent =
    if (positionMs == C.TIME_UNSET) {
        // "The default position", which for a box already playing that song is where it is.
        SeekIntent.Ignore
    } else {
        SeekIntent.WithinTrack(positionMs.coerceAtLeast(0L) / 1000.0)
    }

/** media3's own boundary between "previous track" and "start this one again". */
private const val MAX_SEEK_TO_PREVIOUS_SECONDS =
    C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS / 1000.0

/**
 * A timeline item's identity, and it has to be the queue row's rather than the status's.
 *
 * media3 compares UIDs position by position to decide what changed
 * (`SimpleBasePlayer.getTimelineChangeReason`), and refuses a playlist that repeats one
 * (`"Duplicate MediaItemData UID in playlist"`, thrown straight out of `getState()`). The playing
 * item's *metadata* comes from `playerstatus`, so it used to take its uid from there too — from a
 * field the box need not send, in which case every track change rewrote two UIDs and each one read
 * as a wholesale playlist change. Worse, a queue holding the same file twice can leave
 * `playerstatus.songid` pointing at the other copy, and that collision is the crash.
 *
 * MPD's queue id is unique within the queue; without one the position separates two copies of the
 * same file.
 */
internal fun uidFor(entry: QueueEntry): String = entry.songId ?: "${entry.position}:${entry.url}"

/**
 * Identity for the single-item timeline, where there is no queue row to borrow one from. Nothing
 * compares it against a neighbour, so anything stable for as long as the song lasts will do.
 */
internal fun fallbackUid(status: PlayerStatus): String =
    status.songId ?: status.file ?: CURRENT_ITEM_UID

internal const val CURRENT_ITEM_UID = "coil-current"

/**
 * A media3 player whose playback happens somewhere else entirely.
 *
 * `SimpleBasePlayer` exists precisely for this case (the Cast scenario): [getState] is
 * assembled from the most recent `playerstatus`, and every command fires an RPC. No button
 * waits on the network — media3 shows the outcome optimistically the moment it is asked — but
 * the command does stay *pending* until the box has caught up, which is what makes that
 * optimism last long enough to see (§8.1, and [send]).
 *
 * The useful side effect of `COMMAND_SET_DEVICE_VOLUME` is that the phone's hardware
 * volume buttons change the Phoniebox while the session is active.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PhonieboxPlayer(
    private val scope: CoroutineScope,
    private val player: PlayerRepository,
    boxes: BoxRepository,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private data class Snapshot(
        val status: PlayerStatus = PlayerStatus.Idle,
        val volume: VolumeStatus = VolumeStatus.Unknown,
        val coverUrl: String? = null,
        val activeBox: Box? = null,
        val boxCount: Int = 0,
        val queue: List<QueueEntry> = emptyList(),
    )

    @Volatile
    private var snapshot = Snapshot()

    init {
        scope.launch {
            // Nested rather than one call: `combine` is only typed up to five flows, and the
            // queue arrives on its own schedule anyway — once per queue change, not per status.
            combine(
                combine(
                    player.status,
                    player.volume,
                    player.coverUrl,
                    boxes.activeBox,
                    boxes.boxes.map { it.size }.distinctUntilChanged(),
                ) { status, volume, coverUrl, box, boxCount ->
                    Snapshot(status, volume, coverUrl, box, boxCount)
                },
                player.queue,
            ) { base, queue -> base.copy(queue = queue) }
                .collect { next ->
                    snapshot = next
                    // Must happen on the player's own thread, which this scope runs on.
                    invalidateState()
                }
        }
    }

    override fun getState(): State {
        val current = snapshot
        val status = current.status

        val builder = State.Builder()
            .setAvailableCommands(AVAILABLE_COMMANDS)
            .setPlaybackState(if (status.hasContent) Player.STATE_READY else Player.STATE_IDLE)
            .setPlayWhenReady(
                status.state == PlaybackState.PLAY,
                Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE,
            )
            .setShuffleModeEnabled(status.shuffle)
            .setRepeatMode(
                when (status.repeat) {
                    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                },
            )
            .setDeviceInfo(
                DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                    .setMinVolume(0)
                    .setMaxVolume(current.volume.maxLevel.coerceAtLeast(1))
                    .build(),
            )
            .setDeviceVolume(current.volume.level.coerceAtLeast(0))
            .setIsDeviceMuted(current.volume.muted)

        // The real queue when it can be trusted, the single playing song otherwise. The index
        // has to be inside the playlist or `SimpleBasePlayer` throws, which is why the decision
        // lives in one checked function rather than being spelled out here.
        val index = timelineIndexFor(current.queue, status)
        if (index != null) {
            builder.setPlaylist(
                current.queue.mapIndexed { position, entry ->
                    // The playing item keeps being built from `playerstatus`: that is the
                    // authoritative metadata, and the only item there is cover art for. Its
                    // *identity* stays the queue row's, though — see [uidFor].
                    if (position == index) {
                        mediaItemFor(current, uid = uidFor(entry))
                    } else {
                        mediaItemFor(entry)
                    }
                },
            )
            builder.setCurrentMediaItemIndex(index)
        } else if (status.hasContent) {
            builder.setPlaylist(listOf(mediaItemFor(current, uid = fallbackUid(status))))
            builder.setCurrentMediaItemIndex(0)
        }

        if (status.hasContent) {
            val positionMs = ((status.elapsedSeconds ?: 0.0) * 1000).toLong().coerceAtLeast(0L)
            builder.setContentPositionMs(
                // Published four times a second, which is smooth enough on its own —
                // extrapolating is robustness against a brief gap, not the mechanism (§8.1).
                if (status.state == PlaybackState.PLAY) {
                    PositionSupplier.getExtrapolating(positionMs, 1f)
                } else {
                    PositionSupplier.getConstant(positionMs)
                },
            )
        }

        return builder.build()
    }

    /**
     * One queued track that is not the playing one.
     *
     * No artwork: only the current song's cover is ever resolved, because asking the box for a
     * cover per queued track would be exactly the RPC storm §6 forbids. A queue list with no
     * thumbnails is a fair trade for a queue list at all.
     */
    private fun mediaItemFor(entry: QueueEntry): MediaItemData {
        val durationUs = entry.durationSeconds
            ?.takeIf { it > 0 }
            ?.let { (it * 1_000_000).toLong() }
            ?: C.TIME_UNSET

        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.artist)
            .setAlbumTitle(entry.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItemData.Builder(uidFor(entry))
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(entry.url)
                    .setMediaMetadata(metadata)
                    .build(),
            )
            .setDurationUs(durationUs)
            .setIsSeekable(durationUs != C.TIME_UNSET)
            .setIsDynamic(false)
            .build()
    }

    private fun mediaItemFor(current: Snapshot, uid: Any): MediaItemData {
        val status = current.status
        val durationUs = status.durationSeconds
            ?.takeIf { it > 0 }
            ?.let { (it * 1_000_000).toLong() }
            ?: C.TIME_UNSET

        val metadata = MediaMetadata.Builder()
            // Not `status.title`: the box leaves the tag out for an untagged rip, and a null title
            // is not an empty line on the lock screen — media3 posts a notification with no
            // content title and the platform substitutes "<app> is running" for it.
            .setTitle(status.displayTitle)
            .setArtist(status.artist)
            .setAlbumTitle(status.album)
            // With more than one box, the lock screen has to make clear which device is
            // playing; with one it would just be noise (§8.1).
            .setSubtitle(current.activeBox?.displayName?.takeIf { current.boxCount > 1 })
            .setArtworkUri(current.coverUrl?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItemData.Builder(uid)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(status.file ?: CURRENT_ITEM_UID)
                    .setMediaMetadata(metadata)
                    .build(),
            )
            .setDurationUs(durationUs)
            .setIsSeekable(durationUs != C.TIME_UNSET)
            .setIsDynamic(false)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = send {
        if (playWhenReady) player.play() else player.pause()
        awaitStatus { (it.state == PlaybackState.PLAY) == playWhenReady }
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> = send {
        // Coil never sends anything beyond playback control; pausing is as far as it goes.
        player.pause()
        awaitStatus { it.state != PlaybackState.PLAY }
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val current = snapshot
        val intent = seekIntentFor(
            seekCommand = seekCommand,
            mediaItemIndex = mediaItemIndex,
            positionMs = positionMs,
            // The same call [getState] makes, so the handler and the timeline it published
            // cannot disagree about which index space media3 is speaking in.
            queuePosition = timelineIndexFor(current.queue, current.status),
            shuffle = current.status.shuffle,
            elapsedSeconds = current.status.elapsedSeconds,
        )

        return when (intent) {
            SeekIntent.Ignore -> Futures.immediateVoidFuture()
            SeekIntent.Next -> send { player.next() }
            SeekIntent.Previous -> send { player.previous() }
            is SeekIntent.ToQueuePosition -> send { player.playAt(intent.position) }
            is SeekIntent.WithinTrack -> send { player.seekTo(intent.seconds) }
        }
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> = send {
        player.setVolume(deviceVolume)
        awaitVolume { it.level == deviceVolume }
    }

    // No confirmation for the two relative ones: media3's placeholder assumes a step of one, so
    // holding it until the box answers would only show a wrong number for longer.
    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        send { player.changeVolume(VOLUME_STEP) }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        send { player.changeVolume(-VOLUME_STEP) }

    /**
     * Mute is absolute, not a toggle: `VolumeProviderCompat` turns a car stereo's mute key into
     * `setDeviceMuted(true)`, and answering that with a toggle unmutes a box already muted.
     */
    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> = send {
        player.setMuted(muted)
        awaitVolume { it.muted == muted }
    }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        send {
            player.setShuffle(shuffleModeEnabled)
            awaitStatus { it.shuffle == shuffleModeEnabled }
        }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = send {
        val mode = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        player.setRepeat(mode)
        awaitStatus { it.repeat == mode }
    }

    /**
     * Sends a command, and stays pending until the box has caught up.
     *
     * The future is the whole mechanism, and an immediate one throws it away.
     * `SimpleBasePlayer` shows its optimistic placeholder — the pressed pause button, the new
     * volume — only while a returned future is still running: hand it one that is already
     * complete and `updateStateForPendingOperation` takes its `isDone()` short circuit and asks
     * `getState()` straight back, which still describes the box as it was before the command was
     * even sent. The button then does not move until the next published status, a quarter of a
     * second later at best, which is exactly how a working control comes to look broken.
     *
     * So this finishes when the box has been told and, where the outcome is something the box
     * reports, when it says so — [awaitStatus] and [awaitVolume] bound that wait, because
     * `invalidateState` is ignored while anything is pending and a future that never completes
     * would freeze the session.
     */
    private fun send(block: suspend () -> Unit): ListenableFuture<*> {
        // A command arriving as the service goes down: the coroutine would never start, and so
        // would never complete the future either.
        if (!scope.isActive) return Futures.immediateVoidFuture()

        val pending = SettableFuture.create<Unit>()
        scope.launch {
            try {
                block()
            } finally {
                pending.set(Unit)
            }
        }
        return pending
    }

    private suspend fun awaitStatus(reached: (PlayerStatus) -> Boolean) {
        withTimeoutOrNull(CONFIRM_MILLIS) { player.status.first(reached) }
    }

    private suspend fun awaitVolume(reached: (VolumeStatus) -> Boolean) {
        withTimeoutOrNull(CONFIRM_MILLIS) { player.volume.first(reached) }
    }

    private companion object {
        const val VOLUME_STEP = 5

        /**
         * How long a command may hold the optimistic state while waiting for the box to publish
         * the outcome. Long enough for the box's sequential socket to get round to it (§6),
         * short enough that a box which never agrees is not left holding the session.
         */
        const val CONFIRM_MILLIS = 1_000L

        val AVAILABLE_COMMANDS: Player.Commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_PREPARE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                // Offered even though the box has no command for it: `playAt` walks the queue
                // where it must, so a tap in a queue list always does something honest.
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                // Without this the legacy session publishes PLAYBACK_POSITION_UNKNOWN and no
                // duration — `PlayerWrapper.createPlaybackStateCompat` reads it to decide
                // whether positions may be shared at all — so the system's own media control
                // shows no seek bar, and every remote controller sees a null current item.
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_SET_SHUFFLE_MODE,
                Player.COMMAND_SET_REPEAT_MODE,
                // The reason the phone's volume keys reach the box at all.
                Player.COMMAND_GET_DEVICE_VOLUME,
                Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            )
            .build()
    }
}
