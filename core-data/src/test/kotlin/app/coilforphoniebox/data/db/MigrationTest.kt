package app.coilforphoniebox.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every schema change is migrated rather than dropped, and this is where that claim is
 * checked against a real SQLite file instead of being reasoned about.
 *
 * The stakes are lopsided. A box row and a favourite are the only things in this database a
 * user cannot get back by pointing Coil at the box again, so a migration that loses one is a
 * bug nobody can undo. The library cache is the opposite — disposable by design — but it is
 * still not thrown away where it does not have to be.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CoilDatabase::class.java,
    )

    /**
     * Version 4 re-keys `library_albums` on the content URI, which SQLite cannot do in place,
     * so the table is rebuilt. Rebuilding is where rows get lost, hence the fixture: an album
     * with a resolved cover, and a favourite, both of which must come through untouched.
     */
    @Test
    fun `migrating to 4 keeps every album and its cover`() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO boxes (id, displayName, host, rpcPort, pubPort, addedAt, " +
                    "autoSessionEnabled, networkSsid, lastSeenAt, sortIndex) " +
                    "VALUES ('box-1', 'Living room', 'phoniebox.local', 5555, 5558, 0, 0, NULL, NULL, 0)",
            )
            db.execSQL(
                "INSERT INTO library_albums (boxId, albumArtist, album, coverFile, searchText, cachedAt) " +
                    "VALUES ('box-1', 'Bibi', 'Hexerei', 'a1.jpg', 'bibi hexerei', 42)",
            )
            db.execSQL(
                "INSERT INTO library_albums (boxId, albumArtist, album, coverFile, searchText, cachedAt) " +
                    "VALUES ('box-1', 'Benjamin', 'Zoo', NULL, 'benjamin zoo', 42)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4)

        db.query(
            "SELECT albumArtist, album, coverFile, cachedAt, contentUri, provider, contentType " +
                "FROM library_albums ORDER BY albumArtist",
        ).use { cursor ->
            assertEquals("both albums survive the rebuild", 2, cursor.count)

            cursor.moveToFirst()
            assertEquals("Benjamin", cursor.getString(0))
            assertEquals("Zoo", cursor.getString(1))
            assertNull(cursor.getString(2))

            cursor.moveToNext()
            assertEquals("Bibi", cursor.getString(0))
            assertEquals("Hexerei", cursor.getString(1))
            // The cover is the point: `replaceAlbums` goes to some trouble to keep resolved
            // covers, and dropping the table would have thrown them all away.
            assertEquals("a1.jpg", cursor.getString(2))
            assertEquals(42, cursor.getInt(3))
            // Not fallback defaults — every pre-existing row genuinely came from a box with
            // MPD as its only backend.
            assertEquals("", cursor.getString(4))
            assertEquals("mpd", cursor.getString(5))
            assertEquals("album", cursor.getString(6))
        }
    }

    /**
     * `favorites` is additive, the way `trackUrl` arrived in version 2. Null is the honest
     * value for a row saved before a box could have a second backend, and the mapper reads it
     * back as MPD.
     */
    @Test
    fun `migrating to 4 keeps favourites and leaves their new columns null`() {
        helper.createDatabase(DB, 3).use { db ->
            db.execSQL(
                "INSERT INTO boxes (id, displayName, host, rpcPort, pubPort, addedAt, " +
                    "autoSessionEnabled, networkSsid, lastSeenAt, sortIndex) " +
                    "VALUES ('box-1', 'Living room', 'phoniebox.local', 5555, 5558, 0, 0, NULL, NULL, 0)",
            )
            db.execSQL(
                "INSERT INTO favorites (boxId, label, type, folder, albumArtist, album, " +
                    "trackUrl, coverFile, sortIndex, launchCount, shortcutPinned) " +
                    "VALUES ('box-1', 'Hexerei', 'ALBUM', NULL, 'Bibi', 'Hexerei', NULL, 'a1.jpg', 3, 7, 1)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4)

        db.query(
            "SELECT label, albumArtist, album, coverFile, sortIndex, launchCount, " +
                "shortcutPinned, provider, contentUri FROM favorites",
        ).use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Hexerei", cursor.getString(0))
            assertEquals("Bibi", cursor.getString(1))
            assertEquals("Hexerei", cursor.getString(2))
            assertEquals("a1.jpg", cursor.getString(3))
            // Everything a user set by hand comes through: order, launch count, whether it
            // is pinned to the home screen.
            assertEquals(3, cursor.getInt(4))
            assertEquals(7, cursor.getInt(5))
            assertEquals(1, cursor.getInt(6))
            assertTrue("provider is unset, not guessed", cursor.isNull(7))
            assertTrue("content URI is unset, not guessed", cursor.isNull(8))
        }
    }

    /**
     * The whole chain, because a user on the very first release migrates through all of it in
     * one go and the intermediate steps are the ones nobody runs by hand.
     */
    @Test
    fun `a version 1 database migrates all the way to 4`() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO boxes (id, displayName, host, rpcPort, pubPort, addedAt, " +
                    "autoSessionEnabled, networkSsid, lastSeenAt, sortIndex) " +
                    "VALUES ('box-1', 'Living room', 'phoniebox.local', 5555, 5558, 0, 0, NULL, NULL, 0)",
            )
            db.execSQL(
                "INSERT INTO favorites (boxId, label, type, folder, albumArtist, album, " +
                    "coverFile, sortIndex, launchCount, shortcutPinned) " +
                    "VALUES ('box-1', 'Bibi', 'FOLDER', 'Audiobooks/Bibi', NULL, NULL, NULL, 0, 0, 0)",
            )
        }

        val db = helper.runMigrationsAndValidate(DB, 4, true, *ALL_MIGRATIONS)

        db.query("SELECT label, folder FROM favorites").use { cursor ->
            assertEquals("the favourite survives all three migrations", 1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Bibi", cursor.getString(0))
            assertEquals("Audiobooks/Bibi", cursor.getString(1))
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
