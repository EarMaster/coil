package app.coilforphoniebox.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BoxDao {

    @Query("SELECT * FROM boxes ORDER BY sortIndex ASC, addedAt ASC")
    fun observeAll(): Flow<List<BoxEntity>>

    @Query("SELECT * FROM boxes WHERE id = :id")
    suspend fun find(id: String): BoxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(box: BoxEntity)

    @Update
    suspend fun update(box: BoxEntity)

    @Query("DELETE FROM boxes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE boxes SET lastSeenAt = :at WHERE id = :id")
    suspend fun markSeen(id: String, at: Long)

    @Query("SELECT COALESCE(MAX(sortIndex), -1) FROM boxes")
    suspend fun maxSortIndex(): Int

    @Query("SELECT * FROM boxes WHERE host = :host AND rpcPort = :rpcPort LIMIT 1")
    suspend fun findByAddress(host: String, rpcPort: Int): BoxEntity?
}
