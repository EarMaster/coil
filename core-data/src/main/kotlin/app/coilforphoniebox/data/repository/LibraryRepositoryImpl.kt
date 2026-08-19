package app.coilforphoniebox.data.repository

import android.util.Log
import app.coilforphoniebox.data.db.LibraryDao
import app.coilforphoniebox.data.db.SearchText
import app.coilforphoniebox.data.db.toDomain
import app.coilforphoniebox.data.db.toEntity
import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.LibraryIndexResult
import app.coilforphoniebox.domain.model.LibraryIndexState
import app.coilforphoniebox.domain.model.LibrarySearchResults
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.repository.LibraryRepository
import app.coilforphoniebox.transport.Commands
import app.coilforphoniebox.transport.ConnectionManager
import app.coilforphoniebox.transport.LibraryParser
import app.coilforphoniebox.transport.PhonieboxCommand
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Library data, with Room as the single source of truth (§6.1).
 *
 * Reads always come from the database, so the library stays browsable while the box is
 * off — only playback fails then, with a clear message. A refresh writes to the
 * database and the UI updates as a consequence, never directly from an RPC result.
 */
@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val dao: LibraryDao,
    private val transport: ConnectionManager,
) : LibraryRepository {

    /**
     * Cover lookups are one RPC each. The box processes RPCs strictly sequentially and
     * shares that socket with its own card reader, so a fast scroll through a big album
     * grid must not turn into fifty queued calls.
     */
    private val coverPermits = Semaphore(permits = 2)

    override fun folderContent(boxId: String, path: String): Flow<FolderContent> = combine(
        dao.observeFolders(boxId, path),
        dao.observeTracks(boxId, path),
        dao.observeContentCachedAt(boxId, path),
    ) { folders, tracks, cachedAt ->
        FolderContent(
            path = path,
            folders = folders.filter { it.path != path }.map { it.toDomain() },
            tracks = tracks.map { it.toDomain() },
            cachedAt = cachedAt,
        )
    }

    override suspend fun refreshFolder(boxId: String, path: String): Result<Unit> =
        transport.call(Commands.folderContent(path)).mapCatching { result ->
            val now = System.currentTimeMillis()
            val content = LibraryParser.folderContent(boxId, path, result, now)
            dao.replaceLevel(
                boxId = boxId,
                path = path,
                folders = content.folders.map { it.toEntity(contentCachedAt = null) },
                tracks = content.tracks.map { it.toEntity() },
                at = now,
            )
        }

    override fun albums(boxId: String): Flow<List<LibraryAlbum>> =
        dao.observeAlbums(boxId).map { albums -> albums.map { it.toDomain() } }

    override fun albumsCachedAt(boxId: String): Flow<Long?> = dao.observeAlbumsCachedAt(boxId)

    override suspend fun refreshAlbums(boxId: String): Result<Unit> =
        transport.call(Commands.listAlbums).mapCatching { result ->
            val now = System.currentTimeMillis()
            val albums = LibraryParser.albums(boxId, result, now)
            dao.replaceAlbums(boxId, albums.map { it.toEntity() })
        }

    override suspend fun ensureAlbumCover(boxId: String, albumArtist: String, album: String) {
        val existing = dao.findAlbum(boxId, albumArtist, album)
        if (existing == null || existing.coverFile != null) return

        coverPermits.withPermit {
            // Another caller may have resolved it while this one waited for a permit.
            if (dao.findAlbum(boxId, albumArtist, album)?.coverFile != null) return
            val coverFile = resolveCover(Commands.albumCoverArt(albumArtist, album)) ?: return
            dao.setAlbumCover(boxId, albumArtist, album, coverFile)
        }
    }

    override suspend fun coverFileFor(boxId: String, target: PlayTarget): String? = when (target) {
        is PlayTarget.Album ->
            dao.findAlbum(boxId, target.albumArtist, target.album)?.coverFile
                ?: coverPermits.withPermit {
                    resolveCover(Commands.albumCoverArt(target.albumArtist, target.album))
                        // The album grid asks the same question, so answer it here too. A
                        // missing row makes this a no-op rather than an error.
                        ?.also { dao.setAlbumCover(boxId, target.albumArtist, target.album, it) }
                }

        is PlayTarget.Track -> coverPermits.withPermit { resolveCover(Commands.singleCoverArt(target.url)) }

        is PlayTarget.Folder -> firstTrackUrlIn(boxId, target.path, descend = true)
            ?.let { url -> coverPermits.withPermit { resolveCover(Commands.singleCoverArt(url)) } }
    }

    /**
     * URL of the track whose artwork stands in for a folder's.
     *
     * There is no folder cover in the protocol, so a track inside it has to do. The cache
     * answers for a level that has been browsed; one that has not costs a
     * `get_folder_content`. A folder holding nothing but subfolders — an artist above its
     * albums, which is a common layout — is followed one level down and no further, because
     * each level is another call on the socket the box shares with its card reader (§6).
     */
    private suspend fun firstTrackUrlIn(boxId: String, path: String, descend: Boolean): String? {
        if (dao.findFolder(boxId, path)?.contentCachedAt == null) {
            refreshFolder(boxId, path).getOrElse { return null }
        }
        dao.firstTrackIn(boxId, path)?.let { return it.url }
        if (!descend) return null
        val child = dao.firstFolderIn(boxId, path) ?: return null
        return firstTrackUrlIn(boxId, child.path, descend = false)
    }

    /**
     * Asks the box for one cover and waits out its extraction.
     *
     * The first request for a song or album the box has not cached yet answers
     * `CACHE_PENDING` and queues the work on a worker thread, so asking once never yields a
     * file name. Null means there is no artwork — or that it did not arrive in the time this
     * is prepared to wait, which the caller must not record as "none".
     */
    private suspend fun resolveCover(command: PhonieboxCommand): String? {
        repeat(COVER_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(COVER_RETRY_MILLIS)

            val payload = transport.call(command).getOrNull() ?: return null

            when (val art = LibraryParser.coverArt(payload)) {
                is LibraryParser.CoverArt.Available -> return art.coverRef

                // Nothing to show, and nothing to gain from asking again.
                LibraryParser.CoverArt.Missing -> return null

                LibraryParser.CoverArt.Pending -> Unit
            }
        }
        return null
    }

    override suspend fun rescanBoxLibrary(boxId: String): Result<Unit> =
        transport.call(Commands.updateLibrary).mapCatching {
            // `update` hands the scan to MPD and returns a job id straight away.
            // `update_wait` would block the box's RPC loop until the scan finished, which
            // is exactly what to avoid (§6.4) — so wait a moment and reload, and let the
            // user pull to refresh if that was too quick.
            delay(RESCAN_SETTLE_MILLIS)
            refreshAlbums(boxId)
            refreshFolder(boxId, FolderContent.ROOT)
            Unit
        }

    /**
     * Opportunistic refresh at app start: only when the cache is genuinely old *and*
     * the box is idle, because a blocking call bothers nobody when nothing is playing
     * but delays card detection when something is (§6.4).
     */
    override suspend fun refreshIfStaleAndIdle(boxId: String) {
        if (transport.currentStatus().state != PlaybackState.STOP) return

        val cachedAt = dao.albumsCachedAt(boxId)
        val stale = cachedAt == null || System.currentTimeMillis() - cachedAt > STALE_AFTER_MILLIS
        if (!stale) return

        Log.i(TAG, "Album cache is stale and the box is idle; refreshing")
        refreshAlbums(boxId)
    }

    override fun search(boxId: String, query: String): Flow<LibrarySearchResults> {
        val trimmed = query.trim()
        // A blank query means "not searching", not "everything": the second would pull the
        // whole cache into memory to display a list nobody asked for.
        if (trimmed.isEmpty()) return flowOf(LibrarySearchResults())

        val pattern = SearchText.pattern(trimmed)
        return combine(
            dao.searchFolders(boxId, pattern, SEARCH_LIMIT),
            dao.searchAlbums(boxId, pattern, SEARCH_LIMIT),
            dao.searchTracks(boxId, pattern, SEARCH_LIMIT),
        ) { folders, albums, tracks ->
            LibrarySearchResults(
                query = trimmed,
                // The row a level carries for itself has a blank name at the library root
                // only; every other one is a real folder and belongs in the results.
                folders = folders.filter { it.displayName.isNotBlank() }.map { it.toDomain() },
                albums = albums.map { it.toDomain() },
                tracks = tracks.map { it.toDomain() },
            )
        }
    }

    private val _indexState = MutableStateFlow(LibraryIndexState())
    override val indexState: StateFlow<LibraryIndexState> = _indexState.asStateFlow()

    override suspend fun indexLibrary(boxId: String): LibraryIndexResult {
        // Nothing here is worth a card read that does not register while a story is playing.
        if (transport.currentStatus().state != PlaybackState.STOP) return LibraryIndexResult.BoxBusy

        _indexState.value = LibraryIndexState(running = true, foldersScanned = 0)
        var scanned = 0
        // A level that fails keeps its null stamp, so without this it would be handed back
        // by the next query for ever.
        val attempted = mutableSetOf<String>()

        try {
            if (dao.folderCount(boxId) == 0) {
                refreshFolder(boxId, FolderContent.ROOT)
                    .onFailure { return LibraryIndexResult.Interrupted(scanned) }
            }

            while (scanned < INDEX_FOLDER_CAP) {
                val batch = dao.foldersNeedingContent(boxId, INDEX_BATCH)
                    .filter { it.path !in attempted }
                if (batch.isEmpty()) {
                    Log.i(TAG, "Library index complete after $scanned folders")
                    return LibraryIndexResult.Finished(scanned, stoppedAtCap = false)
                }

                for (folder in batch) {
                    if (scanned >= INDEX_FOLDER_CAP) break
                    attempted += folder.path

                    // The pause is the whole reason this is tolerable: it hands the box's
                    // sequential RPC loop back between levels so its card reader gets a turn.
                    delay(INDEX_PAUSE_MILLIS)
                    val result = refreshFolder(boxId, folder.path)
                    scanned++
                    _indexState.value = LibraryIndexState(running = true, foldersScanned = scanned)

                    // One failure is the box going away, not a bad folder — every path here
                    // came from the box itself.
                    if (result.isFailure) {
                        Log.i(TAG, "Library index interrupted after $scanned folders")
                        return LibraryIndexResult.Interrupted(scanned)
                    }
                }
            }

            // Said out loud rather than passed off as complete: a library bigger than the cap
            // is partly unsearchable and the user needs to know which half they have.
            Log.i(TAG, "Library index stopped at its cap of $INDEX_FOLDER_CAP folders")
            return LibraryIndexResult.Finished(scanned, stoppedAtCap = true)
        } finally {
            _indexState.value = LibraryIndexState(running = false, foldersScanned = scanned)
        }
    }

    private companion object {
        const val TAG = "CoilLibrary"
        const val COVER_ATTEMPTS = 3
        const val COVER_RETRY_MILLIS = 1_000L
        const val RESCAN_SETTLE_MILLIS = 3_000L
        const val STALE_AFTER_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** Per kind, so one very common word cannot produce a list of thousands. */
        const val SEARCH_LIMIT = 60

        /** How many unvisited folders to claim per database round trip. */
        const val INDEX_BATCH = 32

        /**
         * A backstop for a pathological library, not a target: 5000 levels at the pause
         * below is already the better part of an hour.
         */
        const val INDEX_FOLDER_CAP = 5_000

        /** Long enough for the box's own RFID poll to get between two levels. */
        const val INDEX_PAUSE_MILLIS = 250L
    }
}
