package app.coilforphoniebox.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.Favorite
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
        /** The favourite matching what is playing, if there is one. */
        val currentFavorite: Favorite? = null,
    ) {
        val canFavourite: Boolean get() = activeBox != null && status.folder != null
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
    private val favoriteForCurrentFolder: Flow<Favorite?> = combine(
        boxes.activeBox.map { it?.id }.distinctUntilChanged(),
        player.status.map { it.folder }.distinctUntilChanged(),
    ) { boxId, folder -> boxId to folder }
        .flatMapLatest { (boxId, folder) ->
            if (boxId == null || folder == null) flowOf(null)
            else favorites.favorites(boxId).map { list ->
                list.firstOrNull { it.folder == folder }
            }
        }

    val state: StateFlow<State> = combine(
        player.status,
        player.volume,
        player.connectionState,
        player.coverUrl,
        combine(boxes.activeBox, boxes.boxes, favoriteForCurrentFolder) { box, all, favorite ->
            Triple(box, all.size, favorite)
        },
    ) { status, volume, connection, coverUrl, boxInfo ->
        State(
            status = status,
            volume = volume,
            connection = connection,
            coverUrl = coverUrl,
            activeBox = boxInfo.first,
            boxCount = boxInfo.second,
            currentFavorite = boxInfo.third,
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
     * Saves what is playing as a favourite, or removes it again. The label is the folder's
     * own name — content from the box, so it is used exactly as it comes (§12.4).
     */
    fun toggleFavorite() {
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

            val label = folder.substringAfterLast('/').ifBlank { folder }
            val favorite = Favorite.of(boxId, label, PlayTarget.Folder(folder)) ?: return@launch
            favorites.add(favorite)
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
