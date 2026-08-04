package app.coilforphoniebox.data.repository

import app.coilforphoniebox.data.db.FavoriteDao
import app.coilforphoniebox.data.db.toDomain
import app.coilforphoniebox.data.db.toEntity
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.repository.FavoriteRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepositoryImpl @Inject constructor(
    private val dao: FavoriteDao,
) : FavoriteRepository {

    override fun favorites(boxId: String): Flow<List<Favorite>> =
        dao.observeForBox(boxId).map { list -> list.map { it.toDomain() } }

    override fun mostLaunched(boxId: String, limit: Int): Flow<List<Favorite>> =
        dao.observeMostLaunched(boxId, limit).map { list -> list.map { it.toDomain() } }

    override suspend fun favorite(id: Long): Favorite? = dao.find(id)?.toDomain()

    override suspend fun matching(boxId: String, target: PlayTarget): Favorite? = when (target) {
        is PlayTarget.Folder -> dao.findFolder(boxId, target.path)
        is PlayTarget.Album -> dao.findAlbum(boxId, target.albumArtist, target.album)
        // Single tracks are not favouritable, so they never match one.
        is PlayTarget.Track -> null
    }?.toDomain()

    override suspend fun add(favorite: Favorite): Long {
        val sortIndex = dao.maxSortIndex(favorite.boxId) + 1
        return dao.insert(favorite.copy(sortIndex = sortIndex).toEntity())
    }

    override suspend fun remove(id: Long) = dao.delete(id)

    override suspend fun rename(id: Long, label: String) = dao.rename(id, label)

    override suspend fun reorder(ids: List<Long>) = dao.applyOrder(ids)

    override suspend fun recordLaunch(id: Long) = dao.incrementLaunchCount(id)

    override suspend fun setPinned(id: Long, pinned: Boolean) = dao.setPinned(id, pinned)
}
