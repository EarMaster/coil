package app.coilforphoniebox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {

    // ------------------------------------------------------------------ read

    @Query(
        "SELECT * FROM library_folders WHERE boxId = :boxId AND parentPath = :parentPath " +
            "ORDER BY displayName COLLATE NOCASE ASC",
    )
    fun observeFolders(boxId: String, parentPath: String): Flow<List<LibraryFolderEntity>>

    @Query(
        "SELECT * FROM library_tracks WHERE boxId = :boxId AND parentPath = :parentPath " +
            "ORDER BY trackNo IS NULL, trackNo ASC, title COLLATE NOCASE ASC",
    )
    fun observeTracks(boxId: String, parentPath: String): Flow<List<LibraryTrackEntity>>

    @Query("SELECT contentCachedAt FROM library_folders WHERE boxId = :boxId AND path = :path")
    fun observeContentCachedAt(boxId: String, path: String): Flow<Long?>

    @Query(
        "SELECT * FROM library_albums WHERE boxId = :boxId " +
            "ORDER BY albumArtist COLLATE NOCASE ASC, album COLLATE NOCASE ASC",
    )
    fun observeAlbums(boxId: String): Flow<List<LibraryAlbumEntity>>

    @Query("SELECT MAX(cachedAt) FROM library_albums WHERE boxId = :boxId")
    fun observeAlbumsCachedAt(boxId: String): Flow<Long?>

    @Query("SELECT MAX(cachedAt) FROM library_albums WHERE boxId = :boxId")
    suspend fun albumsCachedAt(boxId: String): Long?

    // ---------------------------------------------------------------- search

    /**
     * Searches the cache, never the box: there is no search RPC in the Phoniebox protocol,
     * and the alternative — walking the tree on every keystroke — is precisely the traffic
     * that delays card detection (§6).
     *
     * [pattern] is a folded `LIKE` pattern from `SearchText.pattern`; a contains-match cannot
     * use an index, so [limit] is what keeps a one-letter query cheap.
     */
    @Query(
        "SELECT * FROM library_folders WHERE boxId = :boxId AND searchText LIKE :pattern ESCAPE '\\' " +
            "ORDER BY displayName COLLATE NOCASE ASC LIMIT :limit",
    )
    fun searchFolders(boxId: String, pattern: String, limit: Int): Flow<List<LibraryFolderEntity>>

    @Query(
        "SELECT * FROM library_tracks WHERE boxId = :boxId AND searchText LIKE :pattern ESCAPE '\\' " +
            "ORDER BY title COLLATE NOCASE ASC LIMIT :limit",
    )
    fun searchTracks(boxId: String, pattern: String, limit: Int): Flow<List<LibraryTrackEntity>>

    @Query(
        "SELECT * FROM library_albums WHERE boxId = :boxId AND searchText LIKE :pattern ESCAPE '\\' " +
            "ORDER BY album COLLATE NOCASE ASC LIMIT :limit",
    )
    fun searchAlbums(boxId: String, pattern: String, limit: Int): Flow<List<LibraryAlbumEntity>>

    /** Folders whose own contents have never been fetched — the crawl's work list. */
    @Query(
        "SELECT * FROM library_folders WHERE boxId = :boxId AND contentCachedAt IS NULL " +
            "ORDER BY path ASC LIMIT :limit",
    )
    suspend fun foldersNeedingContent(boxId: String, limit: Int): List<LibraryFolderEntity>

    @Query("SELECT COUNT(*) FROM library_folders WHERE boxId = :boxId")
    suspend fun folderCount(boxId: String): Int

    // ----------------------------------------------------------------- write

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(folders: List<LibraryFolderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<LibraryTrackEntity>)

    @Query("DELETE FROM library_folders WHERE boxId = :boxId AND parentPath = :parentPath")
    suspend fun deleteFoldersIn(boxId: String, parentPath: String)

    @Query("DELETE FROM library_tracks WHERE boxId = :boxId AND parentPath = :parentPath")
    suspend fun deleteTracksIn(boxId: String, parentPath: String)

    @Query("UPDATE library_folders SET contentCachedAt = :at WHERE boxId = :boxId AND path = :path")
    suspend fun stampContent(boxId: String, path: String, at: Long)

    @Query("SELECT * FROM library_folders WHERE boxId = :boxId AND path = :path")
    suspend fun findFolder(boxId: String, path: String): LibraryFolderEntity?

    /**
     * Replaces exactly one level. Anything that disappeared on the box disappears here
     * too, which is what makes a refresh after a rescan show removals as well as
     * additions.
     */
    @Transaction
    suspend fun replaceLevel(
        boxId: String,
        path: String,
        folders: List<LibraryFolderEntity>,
        tracks: List<LibraryTrackEntity>,
        at: Long,
    ) {
        deleteFoldersIn(boxId, path)
        deleteTracksIn(boxId, path)
        upsertFolders(folders)
        upsertTracks(tracks)

        // The row for the level itself carries its freshness stamp. The library root has
        // no parent to have introduced it, so it is created here on first refresh.
        val own = findFolder(boxId, path)
        if (own == null) {
            upsertFolders(
                listOf(
                    LibraryFolderEntity(
                        boxId = boxId,
                        path = path,
                        parentPath = path.substringBeforeLast('/', missingDelimiterValue = "")
                            .takeIf { it != path },
                        displayName = path.substringAfterLast('/'),
                        hasChildren = folders.isNotEmpty(),
                        searchText = SearchText.haystack(path.substringAfterLast('/')),
                        cachedAt = at,
                        contentCachedAt = at,
                    ),
                ),
            )
        } else {
            stampContent(boxId, path, at)
        }
    }

    @Transaction
    suspend fun replaceAlbums(boxId: String, albums: List<LibraryAlbumEntity>) {
        // Covers are fetched one at a time as the grid scrolls, so keep the ones already
        // resolved rather than making the whole grid ask again after every refresh.
        val existingCovers = coversFor(boxId).associate { (it.albumArtist to it.album) to it.coverFile }
        deleteAlbums(boxId)
        upsertAlbums(
            albums.map { album ->
                album.copy(coverFile = album.coverFile ?: existingCovers[album.albumArtist to album.album])
            },
        )
    }

    @Query("SELECT * FROM library_albums WHERE boxId = :boxId AND coverFile IS NOT NULL")
    suspend fun coversFor(boxId: String): List<LibraryAlbumEntity>

    @Query("DELETE FROM library_albums WHERE boxId = :boxId")
    suspend fun deleteAlbums(boxId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbums(albums: List<LibraryAlbumEntity>)

    @Query(
        "UPDATE library_albums SET coverFile = :coverFile " +
            "WHERE boxId = :boxId AND albumArtist = :albumArtist AND album = :album",
    )
    suspend fun setAlbumCover(boxId: String, albumArtist: String, album: String, coverFile: String)

    @Query("SELECT * FROM library_albums WHERE boxId = :boxId AND albumArtist = :albumArtist AND album = :album")
    suspend fun findAlbum(boxId: String, albumArtist: String, album: String): LibraryAlbumEntity?
}
