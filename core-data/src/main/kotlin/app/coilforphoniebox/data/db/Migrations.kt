package app.coilforphoniebox.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema history.
 *
 * Favourites and box configuration are the only data a user cannot get back from the box,
 * so every schema change is migrated rather than dropped — even before the first release,
 * where a destructive fallback would silently empty a tester's favourites.
 */
internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Single tracks became favouritable; existing rows are folders or albums and
        // leave the new column null.
        db.execSQL("ALTER TABLE favorites ADD COLUMN trackUrl TEXT")
    }
}

/**
 * Adds the folded search columns.
 *
 * The existing library rows are dropped rather than backfilled: folding needs accent removal,
 * which SQLite cannot do, and the library cache is the one thing in this database that *is*
 * disposable — the next visit to a folder or the albums tab fetches it again. Boxes and
 * favourites are untouched, which is the part that matters.
 */
internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE library_folders ADD COLUMN searchText TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE library_tracks ADD COLUMN searchText TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE library_albums ADD COLUMN searchText TEXT NOT NULL DEFAULT ''")

        db.execSQL("DELETE FROM library_tracks")
        db.execSQL("DELETE FROM library_folders")
        db.execSQL("DELETE FROM library_albums")
    }
}

/**
 * Album identity gains the backend that owns it.
 *
 * A Phoniebox can now register more than one player backend, and the albums call returns all
 * of their catalogues in one list. Artist-and-album is no longer unique across it, so the
 * cache is re-keyed on the content URI as well.
 *
 * The two tables are treated differently on purpose:
 *
 * - `library_albums` needs a new primary key, which SQLite cannot alter in place, so the
 *   table is rebuilt. The rows are **copied rather than dropped**, unlike [MIGRATION_2_3]:
 *   every existing row necessarily came from a box with one backend, so `'mpd'` and `''` are
 *   not fallback defaults but the correct values, and copying keeps the resolved covers that
 *   `LibraryDao.replaceAlbums` goes to some trouble to preserve.
 * - `favorites` only gains two nullable columns, the way `trackUrl` arrived in
 *   [MIGRATION_1_2]. Favourites are the one thing here a user cannot get back from the box,
 *   so nothing about that table is rebuilt, and null is the honest value for a row saved
 *   before any of this existed.
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `library_albums_new` (
                `boxId` TEXT NOT NULL,
                `albumArtist` TEXT NOT NULL,
                `album` TEXT NOT NULL,
                `coverFile` TEXT,
                `searchText` TEXT NOT NULL DEFAULT '',
                `cachedAt` INTEGER NOT NULL,
                `contentUri` TEXT NOT NULL DEFAULT '',
                `provider` TEXT NOT NULL DEFAULT 'mpd',
                `contentType` TEXT NOT NULL DEFAULT 'album',
                PRIMARY KEY(`boxId`, `albumArtist`, `album`, `contentUri`),
                FOREIGN KEY(`boxId`) REFERENCES `boxes`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT OR IGNORE INTO `library_albums_new`
                (`boxId`, `albumArtist`, `album`, `coverFile`, `searchText`, `cachedAt`,
                 `contentUri`, `provider`, `contentType`)
            SELECT `boxId`, `albumArtist`, `album`, `coverFile`, `searchText`, `cachedAt`,
                   '', 'mpd', 'album'
              FROM `library_albums`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `library_albums`")
        db.execSQL("ALTER TABLE `library_albums_new` RENAME TO `library_albums`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_library_albums_boxId` ON `library_albums` (`boxId`)")

        db.execSQL("ALTER TABLE `favorites` ADD COLUMN `provider` TEXT")
        db.execSQL("ALTER TABLE `favorites` ADD COLUMN `contentUri` TEXT")
    }
}

internal val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
