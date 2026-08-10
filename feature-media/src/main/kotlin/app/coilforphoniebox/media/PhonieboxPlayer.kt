package app.coilforphoniebox.media

import android.net.Uri
import android.os.Looper
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

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

/**
 * A media3 player whose playback happens somewhere else entirely.
 *
 * `SimpleBasePlayer` exists precisely for this case (the Cast scenario): [getState] is
 * assembled from the most recent `playerstatus`, and every command fires an RPC and
 * returns immediately. The UI is therefore optimistic and gets corrected by the next
 * published status a quarter of a second later (§8.1).
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
                    // authoritative metadata, and the only item there is cover art for.
                    if (position == index) mediaItemFor(current) else mediaItemFor(entry)
                },
            )
            builder.setCurrentMediaItemIndex(index)
        } else if (status.hasContent) {
            builder.setPlaylist(listOf(mediaItemFor(current)))
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
            ?: androidx.media3.common.C.TIME_UNSET

        val metadata = MediaMetadata.Builder()
            .setTitle(entry.title)
            .setArtist(entry.artist)
            .setAlbumTitle(entry.album)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        // MPD's queue id is unique within the queue; without one, the position keeps two
        // copies of the same file in one queue from colliding — media3 requires unique UIDs.
        return MediaItemData.Builder(entry.songId ?: "${entry.position}:${entry.url}")
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(entry.url)
                    .setMediaMetadata(metadata)
                    .build(),
            )
            .setDurationUs(durationUs)
            .setIsSeekable(durationUs != androidx.media3.common.C.TIME_UNSET)
            .setIsDynamic(false)
            .build()
    }

    private fun mediaItemFor(current: Snapshot): MediaItemData {
        val status = current.status
        val durationUs = status.durationSeconds
            ?.takeIf { it > 0 }
            ?.let { (it * 1_000_000).toLong() }
            ?: androidx.media3.common.C.TIME_UNSET

        val metadata = MediaMetadata.Builder()
            .setTitle(status.title)
            .setArtist(status.artist)
            .setAlbumTitle(status.album)
            // With more than one box, the lock screen has to make clear which device is
            // playing; with one it would just be noise (§8.1).
            .setSubtitle(current.activeBox?.displayName?.takeIf { current.boxCount > 1 })
            .setArtworkUri(current.coverUrl?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()

        return MediaItemData.Builder(status.songId ?: status.file ?: CURRENT_ITEM_UID)
            .setMediaItem(
                MediaItem.Builder()
                    .setMediaId(status.file ?: CURRENT_ITEM_UID)
                    .setMediaMetadata(metadata)
                    .build(),
            )
            .setDurationUs(durationUs)
            .setIsSeekable(durationUs != androidx.media3.common.C.TIME_UNSET)
            .setIsDynamic(false)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> = fireAndForget {
        if (playWhenReady) player.play() else player.pause()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> = fireAndForget {
        // Coil never sends anything beyond playback control; pausing is as far as it goes.
        player.pause()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = fireAndForget {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> player.next()
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM ->
                player.previous()

            // A seek naming an item is a jump to that queue position — a tap in Android Auto's
            // queue, or "play track five". Any `positionMs` that came with it is dropped: the
            // box has no way to arrive at a position *and* an offset in one command, and
            // getting to the right track matters more than starting it at second 30.
            Player.COMMAND_SEEK_TO_MEDIA_ITEM -> player.playAt(mediaItemIndex)

            else -> player.seekTo(positionMs / 1000.0)
        }
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> =
        fireAndForget { player.setVolume(deviceVolume) }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        fireAndForget { player.changeVolume(VOLUME_STEP) }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> =
        fireAndForget { player.changeVolume(-VOLUME_STEP) }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> =
        fireAndForget { player.toggleMute() }

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        fireAndForget { player.setShuffle(shuffleModeEnabled) }

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> = fireAndForget {
        player.setRepeat(
            when (repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                else -> RepeatMode.OFF
            },
        )
    }

    /**
     * Every command returns immediately rather than waiting for the box: a lock screen
     * button that blocks for a network round trip feels broken even when it works.
     */
    private fun fireAndForget(block: suspend () -> Unit): ListenableFuture<*> {
        scope.launch { block() }
        return Futures.immediateVoidFuture()
    }

    private companion object {
        const val CURRENT_ITEM_UID = "coil-current"
        const val VOLUME_STEP = 5

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
