package app.coilforphoniebox.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteTest {

    @Test
    fun `a folder favourite plays the folder it stored`() {
        val favorite = Favorite.of("box-1", "Bibi", PlayTarget.Folder("Audiobooks/Bibi"))!!

        assertEquals(FavoriteType.FOLDER, favorite.type)
        assertEquals(PlayTarget.Folder("Audiobooks/Bibi"), favorite.toPlayTarget())
    }

    @Test
    fun `an album favourite keeps both keys`() {
        val favorite = Favorite.of("box-1", "Hexerei", PlayTarget.Album("Bibi", "Hexerei"))!!

        assertEquals(FavoriteType.ALBUM, favorite.type)
        assertEquals(PlayTarget.Album("Bibi", "Hexerei"), favorite.toPlayTarget())
    }

    /** One tap should start something worth listening to, not a single file. */
    @Test
    fun `single tracks cannot be favourited`() {
        assertNull(Favorite.of("box-1", "01.mp3", PlayTarget.Track("A/01.mp3")))
    }

    /**
     * Rows like these can only arrive through an imported file. They stay visible and
     * removable instead of throwing when the list is drawn.
     */
    @Test
    fun `a favourite with missing keys has no target rather than crashing`() {
        assertNull(Favorite(boxId = "b", label = "x", type = FavoriteType.FOLDER).toPlayTarget())
        assertNull(
            Favorite(boxId = "b", label = "x", type = FavoriteType.ALBUM, albumArtist = "only artist")
                .toPlayTarget(),
        )
    }
}
