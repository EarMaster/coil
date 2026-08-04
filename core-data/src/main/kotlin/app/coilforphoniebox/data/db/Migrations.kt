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

internal val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
