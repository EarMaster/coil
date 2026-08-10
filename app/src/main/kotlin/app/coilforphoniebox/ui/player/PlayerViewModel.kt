package app.coilforphoniebox.ui.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FavoriteType
import app.coilforphoniebox.domain.model.JumpOutcome
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.QueueEntry
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.SleepTimerStatus
import app.coilforphoniebox.domain.model.VolumeStatus
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.ui.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlayerRepository,
    private val boxes: BoxRepository,
    private val favorites: FavoriteRepository,
) : ViewModel() {

    data class State(
        val status: PlayerStatus = PlayerStatus.Idle,
        val volume: VolumeStatus = VolumeStatus.Unknown,
        val connection: ConnectionState = ConnectionState.DISCONNECTED,
        val coverUrl: String? = null,
        /** Whether the cover lookup for the playing song is still outstanding. */
        val coverPending: Boolean = false,
        val activeBox: Box? = null,
        val boxCount: Int = 0,
        /** The folder favourite matching what is playing, if there is one. */
        val currentFavorite: Favorite? = null,
        /** The favourite for the playing file itself, if there is one. */
        val currentTrackFavorite: Favorite? = null,
        val sleepTimer: SleepTimerStatus = SleepTimerStatus.Off,
    ) {
        /** Whether any playback option is on, so the menu's button can say so at a glance. */
        val anyOptionActive: Boolean
            get() = status.shuffle || status.repeat != RepeatMode.OFF || sleepTimer.running

        val canFavourite: Boolean get() = activeBox != null && (status.folder != null || status.file != null)

        /** The folder is the star's single-tap target, so it needs its own condition. */
        val canFavouriteFolder: Boolean get() = activeBox != null && status.folder != null

        val canFavouriteTrack: Boolean get() = activeBox != null && status.file != null

        /** Folder name as shown in the menu, so it is clear what a folder star saves. */
        val folderLabel: String? get() = status.folder?.let { folderLabelFor(it) }

        /** What stand-in artwork is keyed on while this is playing — see [coverNameOf]. */
        val coverName: String? get() = coverNameOf(status)

        /** Track name as shown in the menu; the tags first, the file name as a fallback. */
        val trackLabel: String? get() = status.file?.let { file ->
            status.title?.takeIf { it.isNotBlank() } ?: file.substringAfterLast('/')
        }
    }

    private val messageChannel = MutableSharedFlow<UiMessage>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<UiMessage> = messageChannel

    /**
     * Recomputed only when the fields shown here change. The box publishes four status
     * messages a second whether or not anything moved, so narrowing before combining
     * keeps recomposition off the idle path (§4.2.3).
     */
    private val favoritesForCurrentContent: Flow<Pair<Favorite?, Favorite?>> = combine(
        boxes.activeBox.map { it?.id }.distinctUntilChanged(),
        player.status.map { it.folder to it.file }.distinctUntilChanged(),
    ) { boxId, content -> boxId to content }
        .flatMapLatest { (boxId, content) ->
            val (folder, file) = content
            if (boxId == null || (folder == null && file == null)) {
                flowOf(null to null)
            } else {
                favorites.favorites(boxId).map { list ->
                    val folderFavorite = folder?.let { path ->
                        list.firstOrNull { it.type == FavoriteType.FOLDER && it.folder == path }
                    }
                    val trackFavorite = file?.let { url ->
                        list.firstOrNull { it.type == FavoriteType.TRACK && it.trackUrl == url }
                    }
                    folderFavorite to trackFavorite
                }
            }
        }

    /** The cover and whether it is still being looked for, bundled for the same reason. */
    private data class Cover(val url: String? = null, val pending: Boolean = false)

    private val cover: Flow<Cover> =
        combine(player.coverUrl, player.coverPending) { url, pending -> Cover(url, pending) }

    /** The box-side inputs, bundled so the outer [combine] stays within five sources. */
    private data class BoxInfo(
        val box: Box? = null,
        val boxCount: Int = 0,
        val folderFavorite: Favorite? = null,
        val trackFavorite: Favorite? = null,
        val sleepTimer: SleepTimerStatus = SleepTimerStatus.Off,
    )

    val state: StateFlow<State> = combine(
        player.status,
        player.volume,
        player.connectionState,
        cover,
        combine(
            boxes.activeBox,
            boxes.boxes,
            favoritesForCurrentContent,
            player.sleepTimer,
        ) { box, all, favorites, timer ->
            BoxInfo(box, all.size, favorites.first, favorites.second, timer)
        },
    ) { status, volume, connection, cover, boxInfo ->
        State(
            status = status,
            volume = volume,
            connection = connection,
            coverUrl = cover.url,
            coverPending = cover.pending,
            activeBox = boxInfo.box,
            boxCount = boxInfo.boxCount,
            currentFavorite = boxInfo.folderFavorite,
            currentTrackFavorite = boxInfo.trackFavorite,
            sleepTimer = boxInfo.sleepTimer,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    /**
     * What the queue sheet shows.
     *
     * A [StateFlow] of its own rather than fields on [State], and deliberately so: reading the
     * queue takes a round trip, and anything folded into that `combine` holds up the title,
     * progress and controls until it has emitted — the trap the cover lookup is kept out of for
     * the same reason. Nothing here can delay the player.
     */
    data class QueueState(
        val entries: List<QueueEntry> = emptyList(),
        /** An answer is on its way, so an empty [entries] is not yet a failure. */
        val loading: Boolean = false,
        /**
         * Position the box is being sent to, while it is being sent there.
         *
         * On a box that cannot jump outright this is a walk of one `next` per track, which takes
         * a visible moment — so the row being aimed at says so and can be called off. Null when
         * nothing is in flight.
         */
        val jumpTarget: Int? = null,
    ) {
        /** Nothing to show and nothing coming: the box was asked and did not answer. */
        val failed: Boolean get() = entries.isEmpty() && !loading
    }

    private val _jumpTarget = MutableStateFlow<Int?>(null)
    private var jumpJob: Job? = null

    val queue: StateFlow<QueueState> = combine(
        player.queue,
        player.queueLoading,
        _jumpTarget,
    ) { entries, loading, jumpTarget -> QueueState(entries, loading, jumpTarget) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QueueState())

    /**
     * Sends the box to queue position [position].
     *
     * The box has no command for this, so the repository may have to walk the queue there one
     * track at a time — which is why this tracks a target the UI can show and cancel, and why
     * three different outcomes are worth telling the user apart.
     */
    fun jumpTo(position: Int) {
        jumpJob?.cancel()
        _jumpTarget.value = position
        jumpJob = viewModelScope.launch {
            try {
                player.playAt(position)
                    .onFailure { messageChannel.emit(UiMessage(commandError())) }
                    .onSuccess { outcome ->
                        when (outcome) {
                            // Arriving is what was asked for; saying so would be noise.
                            JumpOutcome.Arrived -> Unit
                            JumpOutcome.BlockedByShuffle ->
                                messageChannel.emit(UiMessage(R.string.queue_needs_shuffle_off))
                            // The box is playing *something*, just not this. Reporting success
                            // would leave the highlighted row lying about where it is.
                            is JumpOutcome.Incomplete ->
                                messageChannel.emit(UiMessage(R.string.queue_jump_incomplete))
                        }
                    }
            } finally {
                // Guarded, because a newer jump may already have claimed the field.
                if (_jumpTarget.value == position) _jumpTarget.value = null
            }
        }
    }

    /** Abandons a walk in progress. The box keeps playing wherever it got to. */
    fun cancelJump() {
        jumpJob?.cancel()
        _jumpTarget.value = null
    }

    /** For a sheet showing a list that failed to arrive; the ordinary case needs no help. */
    fun refreshQueue() {
        viewModelScope.launch {
            player.refreshQueue().onFailure { messageChannel.emit(UiMessage(commandError())) }
        }
    }

    /**
     * Seconds left on the timer, recomputed once a second while one is running.
     *
     * The box publishes its timer state only when it changes, so the countdown has to be
     * local — and it only ticks while there is something to count, which keeps an idle
     * player from recomposing every second for nothing.
     */
    val sleepTimerRemaining: StateFlow<Int?> = state
        .map { it.sleepTimer }
        .distinctUntilChanged()
        .flatMapLatest { timer ->
            if (!timer.running) {
                flowOf<Int?>(null)
            } else {
                flow<Int?> {
                    while (true) {
                        val left = timer.remainingSecondsAt(SystemClock.elapsedRealtime())
                        emit(left)
                        if (left <= 0) break
                        delay(TIMER_TICK_MILLIS)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Set while the user drags the progress bar, so the 4 Hz updates do not fight it. */
    private val _scrubPosition = MutableStateFlow<Float?>(null)
    val scrubPosition: StateFlow<Float?> = _scrubPosition

    fun onScrub(seconds: Float) {
        _scrubPosition.value = seconds
    }

    fun onScrubFinished() {
        val target = _scrubPosition.value ?: return
        run(clearScrub = true) { player.seekTo(target.toDouble()) }
    }

    fun toggle() = run { player.toggle() }

    fun next() = run { player.next() }

    fun previous() = run { player.previous() }

    /**
     * Volume the user is currently dragging towards, or null when the slider is simply
     * showing what the box reports. Same idea as [scrubPosition]: the 4 Hz status stream
     * must not fight the finger.
     */
    private val _volumeTarget = MutableStateFlow<Int?>(null)
    val volumeTarget: StateFlow<Int?> = _volumeTarget

    init {
        // A slider drag produces a value per frame; sending each one would put dozens of
        // commands on the socket the box also uses for card detection (§6). Waiting for
        // the drag to settle sends one — and still gives audible feedback mid-drag,
        // because a pause of a few frames is enough to trigger it.
        viewModelScope.launch {
            _volumeTarget
                .filterNotNull()
                .debounce(VOLUME_SETTLE_MILLIS)
                .distinctUntilChanged()
                .collect { level -> send(level) }
        }
    }

    fun onVolumeChange(level: Int) {
        _volumeTarget.value = level
    }

    /** Lifting the finger sends immediately rather than waiting out the debounce. */
    fun onVolumeChangeFinished() {
        val target = _volumeTarget.value ?: return
        viewModelScope.launch {
            send(target)
            // Hold the local value briefly so the slider does not jump back to a status
            // message that was published before the box applied the change.
            delay(VOLUME_RELEASE_MILLIS)
            if (_volumeTarget.value == target) _volumeTarget.value = null
        }
    }

    private suspend fun send(level: Int) {
        player.setVolume(level).onFailure { messageChannel.emit(UiMessage(commandError())) }
    }

    fun toggleShuffle() = run { player.setShuffle(!state.value.status.shuffle) }

    /** off → all → one → off, matching what the box itself cycles through. */
    fun cycleRepeat() = run {
        val next = when (state.value.status.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        player.setRepeat(next)
    }

    /**
     * Asks the box what its timer is doing. Called when the timer sheet opens: the state is
     * published on change, but a box that has not been asked for a timer since booting has
     * nothing in the last-value cache to hand over.
     */
    fun refreshSleepTimer() {
        viewModelScope.launch { player.refreshSleepTimer() }
    }

    /** Stops playback after [minutes]. The box is left running — Coil never switches it off. */
    fun startSleepTimer(minutes: Int) = run { player.startSleepTimer(minutes) }

    fun cancelSleepTimer() = run { player.cancelSleepTimer() }

    /**
     * Saves the folder the playing song sits in, or removes it again. This is what the
     * star's single tap does; the long press menu offers the track as well, because from
     * a playing song alone "save this" is ambiguous between the two.
     *
     * The label is the folder's own name — content from the box, so it is used exactly as
     * it comes (§12.4).
     *
     * The playing song's cover is stored with it: it is already resolved, it is a picture of
     * what is in that folder, and saving it here is what spares the favourites tab a lookup
     * of its own later.
     */
    fun toggleFolderFavorite() {
        val current = state.value
        val boxId = current.activeBox?.id ?: return
        val folder = current.status.folder ?: return

        viewModelScope.launch {
            val existing = current.currentFavorite
            if (existing != null) {
                favorites.remove(existing.id)
                messageChannel.emit(UiMessage(R.string.favourites_removed))
                return@launch
            }

            favorites.add(
                Favorite.of(
                    boxId = boxId,
                    label = folderLabelFor(folder),
                    target = PlayTarget.Folder(folder),
                    coverFile = player.currentCoverFile(),
                ),
            )
            messageChannel.emit(UiMessage(R.string.favourites_added))
        }
    }

    /** Saves the playing file itself, or removes it again. */
    fun toggleTrackFavorite() {
        val current = state.value
        val boxId = current.activeBox?.id ?: return
        val file = current.status.file ?: return
        val label = current.trackLabel ?: return

        viewModelScope.launch {
            val existing = current.currentTrackFavorite
            if (existing != null) {
                favorites.remove(existing.id)
                messageChannel.emit(UiMessage(R.string.favourites_removed))
                return@launch
            }

            favorites.add(
                Favorite.of(
                    boxId = boxId,
                    label = label,
                    target = PlayTarget.Track(file),
                    coverFile = player.currentCoverFile(),
                ),
            )
            messageChannel.emit(UiMessage(R.string.favourites_added))
        }
    }

    private fun run(clearScrub: Boolean = false, command: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            val result = command()
            if (clearScrub) _scrubPosition.value = null
            result.onFailure { messageChannel.emit(UiMessage(commandError())) }
        }
    }

    /**
     * A command that failed while the connection looked fine is a real failure; one that
     * failed with no connection is just the box being off, which is a normal state.
     */
    private fun commandError(): Int =
        if (state.value.connection.isUsable) R.string.error_command_failed
        else R.string.error_not_connected

    private companion object {
        /** Long enough to swallow a drag, short enough to feel like a live control. */
        const val VOLUME_SETTLE_MILLIS = 150L

        /** Two status publishes at 4 Hz, so the box has certainly reported back. */
        const val VOLUME_RELEASE_MILLIS = 600L

        /** The countdown is shown to the minute, but a second's resolution reads as alive. */
        const val TIMER_TICK_MILLIS = 1_000L
    }
}

/**
 * Last segment of a folder path, which is the name the user recognises. A path with no
 * separator in it is already the name.
 */
internal fun folderLabelFor(folder: String): String =
    folder.substringAfterLast('/').ifBlank { folder }

/**
 * The name stand-in artwork is keyed on while [status] is playing.
 *
 * **The folder, never the song.** A Phoniebox plays folders, a favourite saves folders, and a
 * folder is what a listener thinks of as "the thing that is on" — key this on the track and the
 * picture would change with every song inside one audio play, which is the opposite of the
 * point. The album tag is what stands in for content browsed by tag rather than by folder.
 *
 * **A stream has no folder, whatever [PlayerStatus.folder] says.** That property is the file
 * path with its last segment removed, and for `http://stream.example.org/kinderradio.mp3` it
 * yields the server — so every station hosted in one place would share one picture. Dropping
 * to the tags gives a station its own cover, keyed on the name the listener sees.
 *
 * Shared with [app.coilforphoniebox.ui.AppViewModel] rather than worked out twice: the mini
 * player and the player show the same content, and two derivations would eventually disagree
 * and draw two different pictures on one screen.
 */
internal fun coverNameOf(status: PlayerStatus): String? =
    status.folder?.takeUnless { STREAM_SCHEME in it }?.let { folderLabelFor(it) }
        ?: status.album?.takeIf { it.isNotBlank() }
        ?: status.artist?.takeIf { it.isNotBlank() }

private const val STREAM_SCHEME = "://"
