package app.coilforphoniebox.domain.repository

import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.PlayTarget
import kotlinx.coroutines.flow.Flow

interface FavoriteRepository {
    fun favorites(boxId: String): Flow<List<Favorite>>

    /** Ordered by [Favorite.launchCount], for the launcher's dynamic shortcuts. */
    fun mostLaunched(boxId: String, limit: Int): Flow<List<Favorite>>

    suspend fun favorite(id: Long): Favorite?

    suspend fun matching(boxId: String, target: PlayTarget): Favorite?

    suspend fun add(favorite: Favorite): Long

    suspend fun remove(id: Long)

    suspend fun rename(id: Long, label: String)

    /** Persists the given order as [Favorite.sortIndex] values. */
    suspend fun reorder(ids: List<Long>)

    suspend fun recordLaunch(id: Long)

    /**
     * Records the cover art file name for a favourite that was saved without one.
     *
     * A favourite is the one thing here that cannot be rebuilt from the box, so its cover
     * is stored beside it rather than being looked up again every time the tab opens.
     */
    suspend fun setCover(id: Long, coverFile: String)

    suspend fun setPinned(id: Long, pinned: Boolean)
}
