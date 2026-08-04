package app.coilforphoniebox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT * FROM favorites WHERE boxId = :boxId ORDER BY sortIndex ASC, id ASC")
    fun observeForBox(boxId: String): Flow<List<FavoriteEntity>>

    /** Feeds the launcher's dynamic shortcuts: the ones actually used (§9). */
    @Query("SELECT * FROM favorites WHERE boxId = :boxId ORDER BY launchCount DESC, sortIndex ASC LIMIT :limit")
    fun observeMostLaunched(boxId: String, limit: Int): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites WHERE id = :id")
    suspend fun find(id: Long): FavoriteEntity?

    @Query(
        "SELECT * FROM favorites WHERE boxId = :boxId AND type = 'FOLDER' AND folder = :folder LIMIT 1",
    )
    suspend fun findFolder(boxId: String, folder: String): FavoriteEntity?

    @Query(
        "SELECT * FROM favorites WHERE boxId = :boxId AND type = 'ALBUM' " +
            "AND albumArtist = :albumArtist AND album = :album LIMIT 1",
    )
    suspend fun findAlbum(boxId: String, albumArtist: String, album: String): FavoriteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteEntity): Long

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE favorites SET label = :label WHERE id = :id")
    suspend fun rename(id: Long, label: String)

    @Query("UPDATE favorites SET sortIndex = :sortIndex WHERE id = :id")
    suspend fun setSortIndex(id: Long, sortIndex: Int)

    @Query("UPDATE favorites SET launchCount = launchCount + 1 WHERE id = :id")
    suspend fun incrementLaunchCount(id: Long)

    @Query("UPDATE favorites SET shortcutPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM favorites WHERE boxId = :boxId")
    suspend fun maxSortIndex(boxId: String): Int

    @Transaction
    suspend fun applyOrder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> setSortIndex(id, index) }
    }
}
