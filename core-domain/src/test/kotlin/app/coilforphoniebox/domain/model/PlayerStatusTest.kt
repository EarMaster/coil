package app.coilforphoniebox.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerStatusTest {

    @Test
    fun `the title tag wins when the box sends one`() {
        val status = PlayerStatus(
            title = "Kapitel 3",
            file = "Petronella/03 - Kapitel 3.mp3",
        )
        assertEquals("Kapitel 3", status.displayTitle)
    }

    /**
     * The case that put "Coil is running" on the lock screen: most of a Phoniebox library is
     * untagged rips, and a null title reaches media3 as a notification with no content title,
     * which the platform fills in with its own text.
     */
    @Test
    fun `an untagged file falls back to its name`() {
        val status = PlayerStatus(file = "Petronella/03 - Kapitel 3.mp3")
        assertEquals("03 - Kapitel 3.mp3", status.displayTitle)
    }

    /** A blank tag is the same as no tag; the parsers map both to null, but not every source does. */
    @Test
    fun `a blank title falls back too`() {
        val status = PlayerStatus(title = "   ", file = "Petronella/03 - Kapitel 3.mp3")
        assertEquals("03 - Kapitel 3.mp3", status.displayTitle)
    }

    /** A file at the library root has no folder to strip off. */
    @Test
    fun `a file with no folder is its own name`() {
        assertEquals("kapitel.mp3", PlayerStatus(file = "kapitel.mp3").displayTitle)
    }

    /** A stream has no file name, so there is nothing to fall back to but the tag. */
    @Test
    fun `a trailing slash leaves nothing to fall back to`() {
        assertNull(PlayerStatus(file = "http://stream.example/").displayTitle)
    }

    /** An idle box names nothing, which is what every caller's own placeholder is for. */
    @Test
    fun `an idle box has no title`() {
        assertNull(PlayerStatus.Idle.displayTitle)
    }

    private fun entry(
        position: Int,
        url: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
    ) = QueueEntry(position = position, url = url, title = title, artist = artist, album = album)

    /**
     * The bug this exists for: the box merges `currentsong` into a dictionary it never clears,
     * so an untagged track keeps the tags of the last tagged one it played — another album's
     * name, with an artist that happens to be right.
     */
    @Test
    fun `tags the file does not have are dropped`() {
        val status = PlayerStatus(
            title = "Kapitel 1",
            artist = "Petronella",
            album = "Petronella Apfelmus 1",
            file = "Bibi/02.mp3",
        )
        val queue = listOf(
            entry(0, "Bibi/01.mp3"),
            entry(1, "Bibi/02.mp3"),
        )

        val reconciled = status.reconciledWith(queue)

        assertNull(reconciled.title)
        assertNull(reconciled.artist)
        assertNull(reconciled.album)
        // The file name, the way an untagged track is named everywhere else.
        assertEquals("02.mp3", reconciled.displayTitle)
    }

    /** A tagged song keeps everything, and keeps it without a copy nobody can tell apart. */
    @Test
    fun `a tagged file is left exactly as it came`() {
        val status = PlayerStatus(
            title = "Kapitel 2",
            artist = "Bibi",
            album = "Folge 12",
            file = "Bibi/02.mp3",
        )
        val queue = listOf(entry(1, "Bibi/02.mp3", "Kapitel 2", "Bibi", "Folge 12"))

        assertSame(status, status.reconciledWith(queue))
    }

    /** The queue is the account of this file's tags, including one the box left out. */
    @Test
    fun `a tag only the queue has is taken from it`() {
        val status = PlayerStatus(title = "Kapitel 2", file = "Bibi/02.mp3")
        val queue = listOf(entry(1, "Bibi/02.mp3", "Kapitel 2", album = "Folge 12"))

        assertEquals("Folge 12", status.reconciledWith(queue).album)
    }

    /**
     * The queue is fetched once per queue change, so for the second between a card tap and the
     * answer the playing file is in no cached row. Leaving it alone keeps a tagged album's
     * title on screen instead of blinking it out on every tap.
     */
    @Test
    fun `a file the cached queue does not have is left alone`() {
        val status = PlayerStatus(title = "Kapitel 1", album = "Folge 11", file = "Bibi/11/01.mp3")

        assertSame(status, status.reconciledWith(listOf(entry(0, "Bibi/12/01.mp3"))))
        assertSame(status, status.reconciledWith(emptyList()))
    }

    /**
     * Web radio is the one case where the published payload is the fresher of the two: ICY
     * metadata reaches `currentsong` live, while the cached queue row still names the station.
     */
    @Test
    fun `a stream keeps the tags the box published`() {
        val status = PlayerStatus(
            title = "Some Song",
            artist = "Some Artist",
            file = "http://stream.example/radio.mp3",
        )
        val queue = listOf(entry(0, "http://stream.example/radio.mp3", "Radio Example"))

        assertSame(status, status.reconciledWith(queue))
    }

    /** Nothing is playing, so there is nothing to check against. */
    @Test
    fun `an idle box is left alone`() {
        assertSame(PlayerStatus.Idle, PlayerStatus.Idle.reconciledWith(emptyList()))
    }
}
