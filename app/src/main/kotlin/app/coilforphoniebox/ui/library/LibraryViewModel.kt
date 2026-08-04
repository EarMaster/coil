package app.coilforphoniebox.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.repository.LibraryRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.ui.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Folder drill-down and album grid.
 *
 * Both read from Room, never from an RPC result directly (§6.1), so the library stays
 * browsable with the box switched off. A level is fetched on first visit and then only
 * when the user asks for it again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val boxes: BoxRepository,
    private val player: PlayerRepository,
    private val favorites: FavoriteRepository,
) : ViewModel() {

    data class FolderState(
        val path: String = FolderContent.ROOT,
        val content: FolderContent = FolderContent(FolderContent.ROOT),
        val refreshing: Boolean = false,
    ) {
        val canGoUp: Boolean get() = path.isNotEmpty()

        /** Breadcrumb segments, in order, for the header. */
        val segments: List<String> get() = path.split('/').filter { it.isNotBlank() }
    }

    data class AlbumState(
        val albums: List<LibraryAlbum> = emptyList(),
        val cachedAt: Long? = null,
        val refreshing: Boolean = false,
    )

    private val messageChannel = MutableSharedFlow<UiMessage>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<UiMessage> = messageChannel

    private val currentPath = MutableStateFlow(FolderContent.ROOT)
    private val folderRefreshing = MutableStateFlow(false)
    private val albumsRefreshing = MutableStateFlow(false)

    /** Levels already fetched in this session, so revisiting one does not re-fetch. */
    private val visitedLevels = mutableSetOf<String>()

    /** Boxes whose album list has been fetched in this session. */
    private val loadedAlbumsFor = mutableSetOf<String>()

    val activeBox: StateFlow<Box?> =
        boxes.activeBox.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val activeBoxId = boxes.activeBox.map { it?.id }.distinctUntilChanged()

    val folderState: StateFlow<FolderState> = combine(
        activeBoxId,
        currentPath,
        folderRefreshing,
    ) { boxId, path, refreshing -> Triple(boxId, path, refreshing) }
        .flatMapLatest { (boxId, path, refreshing) ->
            if (boxId == null) {
                flowOf(FolderState(path = path, refreshing = refreshing))
            } else {
                library.folderContent(boxId, path).map { content ->
                    FolderState(path = path, content = content, refreshing = refreshing)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FolderState())

    val albumState: StateFlow<AlbumState> = combine(
        activeBoxId,
        albumsRefreshing,
    ) { boxId, refreshing -> boxId to refreshing }
        .flatMapLatest { (boxId, refreshing) ->
            if (boxId == null) {
                flowOf(AlbumState(refreshing = refreshing))
            } else {
                combine(library.albums(boxId), library.albumsCachedAt(boxId)) { albums, cachedAt ->
                    AlbumState(albums = albums, cachedAt = cachedAt, refreshing = refreshing)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumState())

    /** Favourited targets on the active box, so the list can mark what is already saved. */
    val favoriteKeys: StateFlow<Set<String>> = activeBoxId
        .flatMapLatest { boxId ->
            if (boxId == null) flowOf(emptyList()) else favorites.favorites(boxId)
        }
        .map { list -> list.mapNotNull { favoriteKey(it) }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        // Fetching a level is a side effect, so it lives here rather than inside a flow
        // transform: opening a folder for the first time loads it, revisiting does not.
        viewModelScope.launch {
            combine(activeBoxId, currentPath) { boxId, path -> boxId to path }
                .collect { (boxId, path) -> if (boxId != null) loadLevelOnce(boxId, path) }
        }
    }

    // ------------------------------------------------------------- navigation

    fun openFolder(path: String) {
        currentPath.value = path
    }

    fun goUp() {
        val path = currentPath.value
        if (path.isEmpty()) return
        currentPath.value = path.substringBeforeLast('/', missingDelimiterValue = FolderContent.ROOT)
    }

    fun openSegment(index: Int) {
        val segments = folderState.value.segments
        currentPath.value = segments.take(index + 1).joinToString("/")
    }

    // ---------------------------------------------------------------- actions

    /** Pull to refresh: only the level on screen, never the whole library (§6.4). */
    fun refreshCurrentFolder() {
        val boxId = activeBox.value?.id ?: return
        val path = currentPath.value
        viewModelScope.launch {
            folderRefreshing.value = true
            library.refreshFolder(boxId, path)
                .onFailure { messageChannel.emit(UiMessage(R.string.error_library_load_failed)) }
            folderRefreshing.value = false
        }
    }

    fun refreshAlbums() {
        val boxId = activeBox.value?.id ?: return
        viewModelScope.launch {
            albumsRefreshing.value = true
            library.refreshAlbums(boxId)
                .onFailure { messageChannel.emit(UiMessage(R.string.error_library_load_failed)) }
            albumsRefreshing.value = false
        }
    }

    /**
     * Called when the album tab is shown. Loads the list once per box per session if it has
     * never been loaded.
     *
     * Deliberately not gated on the box being idle, unlike the opportunistic refresh at app
     * start: §6.4's caution is about background and periodic fetches, and opening the tab is
     * neither. Without this, a first visit while the box was playing showed an empty grid and
     * never asked the box for anything.
     */
    fun onAlbumsShown() {
        val boxId = activeBox.value?.id ?: return
        if (!loadedAlbumsFor.add(boxId)) return
        viewModelScope.launch {
            albumsRefreshing.value = true
            library.refreshAlbums(boxId)
                .onFailure { messageChannel.emit(UiMessage(R.string.error_library_load_failed)) }
            albumsRefreshing.value = false
        }
    }

    /** Called as grid items appear; the repository limits how many run at once. */
    fun requestAlbumCover(album: LibraryAlbum) {
        viewModelScope.launch {
            library.ensureAlbumCover(album.boxId, album.albumArtist, album.album)
        }
    }

    fun play(target: PlayTarget) {
        viewModelScope.launch {
            player.play(target)
                .onFailure { messageChannel.emit(UiMessage(R.string.error_not_connected)) }
        }
    }

    /** From a row's context menu: save that exact target as a favourite, or take it out again. */
    fun toggleFavorite(label: String, target: PlayTarget, coverFile: String? = null) {
        val boxId = activeBox.value?.id ?: return
        viewModelScope.launch {
            val existing = favorites.matching(boxId, target)
            if (existing != null) {
                favorites.remove(existing.id)
                messageChannel.emit(UiMessage(R.string.favourites_removed))
                return@launch
            }
            favorites.add(Favorite.of(boxId, label, target, coverFile))
            messageChannel.emit(UiMessage(R.string.favourites_added))
        }
    }

    private suspend fun loadLevelOnce(boxId: String, path: String) {
        if (!visitedLevels.add("$boxId|$path")) return
        folderRefreshing.value = true
        library.refreshFolder(boxId, path)
            // Say so rather than showing an empty folder and leaving the user to guess
            // whether the box has nothing in it or the request failed.
            .onFailure {
                visitedLevels.remove("$boxId|$path")
                messageChannel.emit(UiMessage(R.string.error_library_load_failed))
            }
        folderRefreshing.value = false
    }

    private fun favoriteKey(favorite: Favorite): String? =
        favorite.toPlayTarget()?.let { keyFor(it) }

    private fun keyFor(target: PlayTarget): String = when (target) {
        is PlayTarget.Folder -> "folder:${target.path}"
        is PlayTarget.Album -> "album:${target.albumArtist}/${target.album}"
        is PlayTarget.Track -> "track:${target.url}"
    }
}
