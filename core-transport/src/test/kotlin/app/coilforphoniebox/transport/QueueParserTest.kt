package app.coilforphoniebox.transport

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueParserTest {

    private fun parse(raw: String) = Json.parseToJsonElement(raw)

    @Test
    fun `a queue keeps the box's own positions and order`() {
        val raw = """
            [
              {"file":"Audiobooks/Bibi/01.mp3","pos":"0","id":"17","title":"Kapitel 1",
               "albumartist":"Bibi Blocksberg","album":"Hexerei","duration":"243.5"},
              {"file":"Audiobooks/Bibi/02.mp3","pos":"1","id":"18","title":"Kapitel 2",
               "albumartist":"Bibi Blocksberg","album":"Hexerei","duration":"301"}
            ]
        """.trimIndent()

        val queue = QueueParser.queue(parse(raw))

        assertEquals(2, queue.size)
        assertEquals(0, queue[0].position)
        assertEquals("Audiobooks/Bibi/01.mp3", queue[0].url)
        assertEquals("Kapitel 1", queue[0].title)
        assertEquals("Bibi Blocksberg", queue[0].artist)
        assertEquals("Hexerei", queue[0].album)
        assertEquals(243.5, queue[0].durationSeconds!!, 0.001)
        assertEquals("17", queue[0].songId)

        assertEquals(1, queue[1].position)
        assertEquals(301.0, queue[1].durationSeconds!!, 0.001)
    }

    /**
     * python-mpd2 lower-cases the keys, but MPD's own spelling is capitalised and the layer in
     * between is not a contract Coil can rely on.
     */
    @Test
    fun `capitalised MPD keys parse the same as lower-cased ones`() {
        val raw = """
            [{"File":"A/01.mp3","Pos":"3","Id":"9","Title":"Track","Album":"X","Duration":"12.5"}]
        """.trimIndent()

        val entry = QueueParser.queue(parse(raw)).single()

        assertEquals("A/01.mp3", entry.url)
        assertEquals(3, entry.position)
        assertEquals("Track", entry.title)
        assertEquals("X", entry.album)
        assertEquals("9", entry.songId)
        assertEquals(12.5, entry.durationSeconds!!, 0.001)
    }

    /**
     * Most of a Phoniebox library is untagged rips, so this is the common case rather than an
     * edge one — a row with no text at all would be unusable.
     */
    @Test
    fun `an untagged song falls back to its file name`() {
        val raw = """[{"file":"Audiobooks/Folge 12/03 - Track.mp3","pos":"2"}]"""

        val entry = QueueParser.queue(parse(raw)).single()

        assertEquals("03 - Track.mp3", entry.title)
        assertNull(entry.artist)
        assertNull(entry.album)
        assertNull(entry.durationSeconds)
        assertNull(entry.songId)
    }

    /** A stream has no duration, the same way it has no `duration` in `playerstatus`. */
    @Test
    fun `a stream entry parses without a duration`() {
        val raw = """[{"file":"http://host/station.mp3","pos":"0","title":"Radio"}]"""

        val entry = QueueParser.queue(parse(raw)).single()

        assertEquals("Radio", entry.title)
        assertNull(entry.durationSeconds)
    }

    /** The older protocol spells the duration `time`, in whole seconds. */
    @Test
    fun `time stands in for a missing duration`() {
        val raw = """[{"file":"A/01.mp3","pos":"0","time":"180"}]"""

        assertEquals(180.0, QueueParser.queue(parse(raw)).single().durationSeconds!!, 0.001)
    }

    /**
     * The queue arrives in play order, so the index is the position — but only as a fallback,
     * because it is the box's own value that `playerstatus.playlistPosition` is compared to.
     */
    @Test
    fun `a missing position falls back to the index`() {
        val raw = """[{"file":"A/01.mp3"},{"file":"A/02.mp3"}]"""

        val queue = QueueParser.queue(parse(raw))

        assertEquals(0, queue[0].position)
        assertEquals(1, queue[1].position)
    }

    /** The one field with no fallback: without a URL the row cannot be matched or played. */
    @Test
    fun `an entry with no file is dropped rather than shown blank`() {
        val raw = """[{"file":"A/01.mp3"},{"pos":"1","title":"Ghost"},{"file":"A/03.mp3"}]"""

        val queue = QueueParser.queue(parse(raw))

        assertEquals(2, queue.size)
        assertTrue(queue.none { it.title == "Ghost" })
    }

    @Test
    fun `an empty or unusable payload is an empty queue, not a failure`() {
        assertTrue(QueueParser.queue(parse("[]")).isEmpty())
        assertTrue(QueueParser.queue(parse("""{"state":"stop"}""")).isEmpty())
        assertTrue(QueueParser.queue(null).isEmpty())
    }
}
