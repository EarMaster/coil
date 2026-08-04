package app.coilforphoniebox.domain.repository

import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import kotlinx.coroutines.flow.Flow

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

    suspend fun refreshAlbums(boxId: String): Result<Unit>

    /**
     * Fetches this album's cover file name if it is not cached yet, and stores it.
     *
     * Deliberately per album and on demand: asking for every cover during a refresh
     * would be one RPC per album on a socket the box also uses for card detection.
     */
    suspend fun ensureAlbumCover(boxId: String, albumArtist: String, album: String)

    /** Fires `player.ctrl.update` so the box rescans its own MPD database. */
    suspend fun rescanBoxLibrary(boxId: String): Result<Unit>

    /**
     * Refreshes the album list only when the cache is older than a week *and* the box
     * is idle, so a blocking call never delays card detection mid-playback (§6.4).
     */
    suspend fun refreshIfStaleAndIdle(boxId: String)
}
