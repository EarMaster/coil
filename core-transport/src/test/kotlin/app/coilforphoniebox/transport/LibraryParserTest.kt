package app.coilforphoniebox.transport

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryParserTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw)

    /**
     * The box returns both an absolute `path` and a `relpath`. Only `relpath` is usable:
     * `play_folder` and `play_single` expect a path relative to the music library root.
     */
    @Test
    fun `folder content keeps the relative path, not the absolute one`() {
        val raw = """
            [
              {"type":"directory","name":"Bibi","path":"/home/pi/audiofolders/Audiobooks/Bibi","relpath":"Audiobooks/Bibi"},
              {"type":"file","name":"01.mp3","path":"/home/pi/audiofolders/Audiobooks/01.mp3","relpath":"Audiobooks/01.mp3"}
            ]
        """.trimIndent()

        val content = LibraryParser.folderContent("box-1", "Audiobooks", parse(raw), cachedAt = 5L)

        assertEquals(1, content.folders.size)
        assertEquals("Audiobooks/Bibi", content.folders.first().path)
        assertEquals("Bibi", content.folders.first().displayName)
        assertEquals("Audiobooks", content.folders.first().parentPath)

        assertEquals(1, content.tracks.size)
        assertEquals("Audiobooks/01.mp3", content.tracks.first().url)
        assertEquals(5L, content.cachedAt)
    }

    @Test
    fun `streams and podcasts are treated as playable entries`() {
        val raw = """
            [
              {"type":"stream","name":"Radio","path":"http://a","relpath":"http://a"},
              {"type":"podcast","name":"Show","path":"http://b","relpath":"http://b"}
            ]
        """.trimIndent()

        val content = LibraryParser.folderContent("box-1", "", parse(raw), 0L)

        assertEquals(2, content.tracks.size)
        assertTrue(content.folders.isEmpty())
    }

    @Test
    fun `entries missing a name or a path are skipped rather than fatal`() {
        val raw = """[{"type":"directory"},{"type":"directory","name":"Ok","relpath":"Ok"}]"""

        val content = LibraryParser.folderContent("box-1", "", parse(raw), 0L)

        assertEquals(1, content.folders.size)
    }

    /**
     * `list_albums` groups by album artist, so an artist with several albums comes back
     * with an array under one key.
     */
    @Test
    fun `albums are read from grouped entries with one or many albums`() {
        val raw = """
            [
              {"albumartist":"Bibi Blocksberg","album":["Hexerei","Der Reiterhof"]},
              {"albumartist":"Benjamin","album":"Zoo"}
            ]
        """.trimIndent()

        val albums = LibraryParser.albums("box-1", parse(raw), cachedAt = 9L)

        assertEquals(3, albums.size)
        assertTrue(albums.any { it.albumArtist == "Bibi Blocksberg" && it.album == "Hexerei" })
        assertTrue(albums.any { it.albumArtist == "Bibi Blocksberg" && it.album == "Der Reiterhof" })
        assertTrue(albums.any { it.albumArtist == "Benjamin" && it.album == "Zoo" })
        assertTrue(albums.all { it.cachedAt == 9L })
    }

    @Test
    fun `albums without an artist still come through`() {
        val albums = LibraryParser.albums("box-1", parse("""[{"album":"Untitled"}]"""), 0L)

        assertEquals(1, albums.size)
        assertEquals("", albums.first().albumArtist)
    }

    @Test
    fun `cover art returns a bare file name and rejects the empty answers`() {
        assertEquals("abc123.jpg", LibraryParser.coverFile(parse("\"abc123.jpg\"")))
        assertEquals("abc123.jpg", LibraryParser.coverFile(parse("\"/cache/abc123.jpg\"")))
        assertNull(LibraryParser.coverFile(parse("\"\"")))
        assertNull(LibraryParser.coverFile(parse("null")))
        assertNull(LibraryParser.coverFile(null))
    }
}
