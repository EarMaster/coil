package app.coilforphoniebox.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FavoriteType
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.VolumeStatus
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.ui.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
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
        val activeBox: Box? = null,
        val boxCount: Int = 0,
        /** The folder favourite matching what is playing, if there is one. */
        val currentFavorite: Favorite? = null,
        /** The favourite for the playing file itself, if there is one. */
        val currentTrackFavorite: Favorite? = null,
    ) {
        val canFavourite: Boolean get() = activeBox != null && (status.folder != null || status.file != null)

        /** The folder is the star's single-tap target, so it needs its own condition. */
        val canFavouriteFolder: Boolean get() = activeBox != null && status.folder != null

        val canFavouriteTrack: Boolean get() = activeBox != null && status.file != null

        /** Folder name as shown in the menu, so it is clear what a folder star saves. */
        val folderLabel: String? get() = status.folder?.let { folderLabelFor(it) }

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

    /** The box-side inputs, bundled so the outer [combine] stays within five sources. */
    private data class BoxInfo(
        val box: Box? = null,
        val boxCount: Int = 0,
        val folderFavorite: Favorite? = null,
        val trackFavorite: Favorite? = null,
    )

    val state: StateFlow<State> = combine(
        player.status,
        player.volume,
        player.connectionState,
        player.coverUrl,
        combine(boxes.activeBox, boxes.boxes, favoritesForCurrentContent) { box, all, favorites ->
            BoxInfo(box, all.size, favorites.first, favorites.second)
        },
    ) { status, volume, connection, coverUrl, boxInfo ->
        State(
            status = status,
            volume = volume,
            connection = connection,
            coverUrl = coverUrl,
            activeBox = boxInfo.box,
            boxCount = boxInfo.boxCount,
            currentFavorite = boxInfo.folderFavorite,
            currentTrackFavorite = boxInfo.trackFavorite,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

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
     * Saves the folder the playing song sits in, or removes it again. This is what the
     * star's single tap does; the long press menu offers the track as well, because from
     * a playing song alone "save this" is ambiguous between the two.
     *
     * The label is the folder's own name — content from the box, so it is used exactly as
     * it comes (§12.4).
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

            favorites.add(Favorite.of(boxId, folderLabelFor(folder), PlayTarget.Folder(folder)))
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

            favorites.add(Favorite.of(boxId, label, PlayTarget.Track(file)))
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
    }
}

/**
 * Last segment of a folder path, which is the name the user recognises. A path with no
 * separator in it is already the name.
 */
private fun folderLabelFor(folder: String): String =
    folder.substringAfterLast('/').ifBlank { folder }
