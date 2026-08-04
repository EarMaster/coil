package app.coilforphoniebox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        BoxEntity::class,
        LibraryFolderEntity::class,
        LibraryTrackEntity::class,
        LibraryAlbumEntity::class,
        FavoriteEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class CoilDatabase : RoomDatabase() {
    abstract fun boxDao(): BoxDao
    abstract fun libraryDao(): LibraryDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        const val NAME = "coil.db"
    }
}
