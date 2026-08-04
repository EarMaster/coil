package app.coilforphoniebox.data.repository

import android.util.Log
import app.coilforphoniebox.data.db.LibraryDao
import app.coilforphoniebox.data.db.toDomain
import app.coilforphoniebox.data.db.toEntity
import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.repository.LibraryRepository
import app.coilforphoniebox.transport.Commands
import app.coilforphoniebox.transport.ConnectionManager
import app.coilforphoniebox.transport.LibraryParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

            // The box answers the first request with CACHE_PENDING and extracts the artwork
            // on a worker thread, so asking once never yields a file name.
            repeat(COVER_ATTEMPTS) { attempt ->
                if (attempt > 0) delay(COVER_RETRY_MILLIS)

                val payload = transport.call(Commands.albumCoverArt(albumArtist, album))
                    .getOrNull() ?: return

                when (val art = LibraryParser.coverArt(payload)) {
                    is LibraryParser.CoverArt.Available -> {
                        dao.setAlbumCover(boxId, albumArtist, album, art.fileName)
                        return
                    }

                    // Nothing to show, and nothing to gain from asking again.
                    LibraryParser.CoverArt.Missing -> return

                    LibraryParser.CoverArt.Pending -> Unit
                }
            }
        }
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

    private companion object {
        const val TAG = "CoilLibrary"
        const val COVER_ATTEMPTS = 3
        const val COVER_RETRY_MILLIS = 1_000L
        const val RESCAN_SETTLE_MILLIS = 3_000L
        const val STALE_AFTER_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
