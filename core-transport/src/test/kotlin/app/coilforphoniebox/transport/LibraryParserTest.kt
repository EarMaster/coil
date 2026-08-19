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

    /**
     * A box predating the provider-neutral player sends none of the three new fields, and
     * what it means by that is the only thing it has ever had: the local library.
     */
    @Test
    fun `an entry with no provider fields reads as a local album`() {
        val albums = LibraryParser.albums(
            "box-1",
            parse("""[{"albumartist":"Bibi","album":"Hexerei"}]"""),
            0L,
        )

        assertEquals("mpd", albums.single().provider)
        assertNull(albums.single().contentUri)
        assertEquals("album", albums.single().contentType)
    }

    /**
     * Once a box has two backends it returns both catalogues in one list, and these three
     * fields are all that separate them. Losing them is what sends a streaming album to MPD,
     * which finds nothing and clears the queue on the way.
     */
    @Test
    fun `provider, handle and kind are kept`() {
        val raw = """
            [
              {"albumartist":"Bibi","album":"Hexerei","provider":"mpd",
               "content_uri":null,"content_type":"album"},
              {"albumartist":"Nico","album":"Chill Mix","provider":"spotify",
               "content_uri":"spotify:playlist:42","content_type":"playlist"}
            ]
        """.trimIndent()

        val albums = LibraryParser.albums("box-1", parse(raw), 0L)

        val local = albums.single { it.provider == "mpd" }
        assertNull("MPD addresses its own albums by artist and album", local.contentUri)

        val remote = albums.single { it.provider == "spotify" }
        assertEquals("spotify:playlist:42", remote.contentUri)
        assertEquals("playlist", remote.contentType)
        assertEquals("Chill Mix", remote.album)
    }

    /**
     * Owning a record on disc *and* saving it in a streaming account is ordinary, and the two
     * are played by different calls — so they have to stay two rows. Keying the de-duplication
     * on artist and album alone would hide whichever the box listed second.
     */
    @Test
    fun `the same album from two backends stays two entries`() {
        val raw = """
            [
              {"albumartist":"Bibi","album":"Hexerei","provider":"mpd"},
              {"albumartist":"Bibi","album":"Hexerei","provider":"spotify",
               "content_uri":"spotify:album:9"}
            ]
        """.trimIndent()

        val albums = LibraryParser.albums("box-1", parse(raw), 0L)

        assertEquals(2, albums.size)
        assertEquals(setOf(null, "spotify:album:9"), albums.map { it.contentUri }.toSet())
    }

    /** A genuine duplicate — same backend, same handle — is still collapsed. */
    @Test
    fun `a repeated entry is still de-duplicated`() {
        val raw = """
            [
              {"albumartist":"Bibi","album":"Hexerei"},
              {"albumartist":"Bibi","album":"Hexerei"}
            ]
        """.trimIndent()

        assertEquals(1, LibraryParser.albums("box-1", parse(raw), 0L).size)
    }

    @Test
    fun `library sources are read as id and label`() {
        val raw = """
            [
              {"id":"mpd","label":"Local","views":[{"id":"albums","kind":"items"}]},
              {"id":"spotify","label":"Spotify","views":[]}
            ]
        """.trimIndent()

        val sources = LibraryParser.librarySources(parse(raw))

        assertEquals(listOf("mpd", "spotify"), sources.map { it.id })
        assertEquals(listOf("Local", "Spotify"), sources.map { it.label })
    }

    /**
     * The id is what a play call is routed by, so an entry without one is dropped rather than
     * given a made-up id that would send content to the wrong backend. A missing *label* is
     * only a naming problem, so the id stands in.
     */
    @Test
    fun `a source without an id is dropped, one without a label keeps its id`() {
        val raw = """[{"label":"Nameless"},{"id":"other"}]"""

        val sources = LibraryParser.librarySources(parse(raw))

        assertEquals(1, sources.size)
        assertEquals("other", sources.single().id)
        assertEquals("other", sources.single().label)
    }

    @Test
    fun `cover art returns a bare file name`() {
        assertEquals(
            LibraryParser.CoverArt.Available("abc123.jpg"),
            LibraryParser.coverArt(parse("\"abc123.jpg\"")),
        )
        assertEquals(
            LibraryParser.CoverArt.Available("abc123.jpg"),
            LibraryParser.coverArt(parse("\"/cache/abc123.jpg\"")),
        )
    }

    /**
     * The box queues extraction on a worker thread and answers the *first* request for any
     * song with this sentinel. Treating it as a file name is why no cover ever appeared.
     */
    @Test
    fun `CACHE_PENDING is a retry signal, not a file name`() {
        assertEquals(
            LibraryParser.CoverArt.Pending,
            LibraryParser.coverArt(parse("\"CACHE_PENDING\"")),
        )
    }

    @Test
    fun `an empty answer means there is no artwork`() {
        assertEquals(LibraryParser.CoverArt.Missing, LibraryParser.coverArt(parse("\"\"")))
        assertEquals(LibraryParser.CoverArt.Missing, LibraryParser.coverArt(parse("null")))
        assertEquals(LibraryParser.CoverArt.Missing, LibraryParser.coverArt(null))
    }

    /**
     * A provider-neutral box answers for a Spotify track with the artwork's own URL. Keeping
     * only the last path segment — right for the box's own `cache/hash.jpg` — would reduce it
     * to a hash, and `Box.coverUrl` would then have no way to tell it was ever external and
     * would hang it off the box's address, where it 404s.
     */
    @Test
    fun `an absolute url survives whole`() {
        assertEquals(
            LibraryParser.CoverArt.Available("https://i.scdn.co/image/ab67616d0000b273"),
            LibraryParser.coverArt(parse("\"https://i.scdn.co/image/ab67616d0000b273\"")),
        )
        assertEquals(
            LibraryParser.CoverArt.Available("http://cdn.example/art/1.jpg"),
            LibraryParser.coverArt(parse("\"http://cdn.example/art/1.jpg\"")),
        )
    }
}
