package app.coilforphoniebox.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteTest {

    @Test
    fun `a folder favourite plays the folder it stored`() {
        val favorite = Favorite.of("box-1", "Bibi", PlayTarget.Folder("Audiobooks/Bibi"))

        assertEquals(FavoriteType.FOLDER, favorite.type)
        assertEquals(PlayTarget.Folder("Audiobooks/Bibi"), favorite.toPlayTarget())
    }

    @Test
    fun `an album favourite keeps both keys`() {
        val favorite = Favorite.of("box-1", "Hexerei", PlayTarget.Album("Bibi", "Hexerei"))

        assertEquals(FavoriteType.ALBUM, favorite.type)
        assertEquals(PlayTarget.Album("Bibi", "Hexerei"), favorite.toPlayTarget())
    }

    /**
     * A single track is favouritable in its own right: from a playing song, "folder" and
     * "track" are two different intentions and the user picks which one to save.
     */
    @Test
    fun `a track favourite plays the single file it stored`() {
        val favorite = Favorite.of("box-1", "01.mp3", PlayTarget.Track("Audiobooks/Bibi/01.mp3"))

        assertEquals(FavoriteType.TRACK, favorite.type)
        assertEquals(PlayTarget.Track("Audiobooks/Bibi/01.mp3"), favorite.toPlayTarget())
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
        assertNull(Favorite(boxId = "b", label = "x", type = FavoriteType.TRACK).toPlayTarget())
    }
}
