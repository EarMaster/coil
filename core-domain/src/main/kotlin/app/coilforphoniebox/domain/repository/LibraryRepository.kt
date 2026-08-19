package app.coilforphoniebox.domain.repository

import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.LibraryIndexResult
import app.coilforphoniebox.domain.model.LibraryIndexState
import app.coilforphoniebox.domain.model.LibrarySearchResults
import app.coilforphoniebox.domain.model.LibrarySource
import app.coilforphoniebox.domain.model.PlayTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Room is the single source of truth (§6.1): every read is a [Flow] from the
 * database, and a refresh writes to the database rather than returning data. The
 * library therefore stays browsable while the box is off.
 */
interface LibraryRepository {
    fun folderContent(boxId: String, path: String): Flow<FolderContent>

    /** Fetches exactly one level via `get_folder_content` and caches it. */
    suspend fun refreshFolder(boxId: String, path: String): Result<Unit>

    fun albums(boxId: String): Flow<List<LibraryAlbum>>

    /** Most recent successful album refresh, for the freshness hint. */
    fun albumsCachedAt(boxId: String): Flow<Long?>

    /**
     * The backends this box browses with, as it described them; empty until a refresh has
     * asked, and empty for a box that has no such notion.
     *
     * Only good for *naming* a backend. Whether a box has more than one is answered from the
     * cached albums instead, which survive a cold start where this does not.
     */
    fun librarySources(boxId: String): Flow<List<LibrarySource>>

    /**
     * Whether this box's albums came from more than one backend.
     *
     * Read from the cached albums rather than from [librarySources], so it is already right
     * on a cold start — the sources are only known once a refresh has asked, and a UI that
     * flickered from "one source" to "two" a second after opening would be worse than one
     * that never mentioned sources at all.
     */
    fun hasMultipleSources(boxId: String): Flow<Boolean>

    suspend fun refreshAlbums(boxId: String): Result<Unit>

    /**
     * Fetches this album's cover file name if it is not cached yet, and stores it.
     *
     * Deliberately per album and on demand: asking for every cover during a refresh
     * would be one RPC per album on a socket the box also uses for card detection.
     *
     * Takes the whole album rather than its parts, because the lookup has to be routed and
     * stored against the full identity — artist and album alone address a different row on a
     * box with more than one backend.
     */
    suspend fun ensureAlbumCover(album: LibraryAlbum)

    /**
     * Cover file name for [target], from the cache if it is there and from the box if not.
     * Null when the box has no artwork for it, or when it could not be asked.
     *
     * Only a folder needs more than one call: nothing in the protocol returns a folder's
     * cover, so its first track stands in for it — taken from the cache when that level has
     * been browsed, and fetched with `get_folder_content` when it has not. Costly enough
     * that only an explicit, on-screen need should ask (§6).
     */
    suspend fun coverFileFor(boxId: String, target: PlayTarget): String?

    /** Fires `player.ctrl.update` so the box rescans its own MPD database. */
    suspend fun rescanBoxLibrary(boxId: String): Result<Unit>

    /**
     * Refreshes the album list only when the cache is older than a week *and* the box
     * is idle, so a blocking call never delays card detection mid-playback (§6.4).
     */
    suspend fun refreshIfStaleAndIdle(boxId: String)

    /**
     * Searches the cache — the Phoniebox protocol has no search command, so there is nothing
     * to ask the box even if it were free to ask. A blank [query] yields no results rather
     * than everything.
     *
     * What is searchable is therefore what has been fetched: every album once the album tab
     * has loaded, and folders and tracks from levels that have been opened — or from all of
     * them, after [indexLibrary].
     */
    fun search(boxId: String, query: String): Flow<LibrarySearchResults>

    /** Progress of a running [indexLibrary], for the screen that started it. */
    val indexState: StateFlow<LibraryIndexState>

    /**
     * Walks the whole folder tree one level at a time, so that search covers folders nobody
     * has opened yet.
     *
     * Opt-in and never automatic: this is exactly the traffic §6 warns about — the box
     * processes RPCs sequentially and shares that socket with its card reader, so a crawl
     * makes cards slow to register while it runs. It pauses between levels for that reason,
     * refuses to start while the box is playing, and can be cancelled by cancelling the
     * calling coroutine, keeping whatever it managed to scan.
     */
    suspend fun indexLibrary(boxId: String): LibraryIndexResult
}
