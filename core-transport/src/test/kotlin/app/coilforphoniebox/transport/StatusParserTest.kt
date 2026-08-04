package app.coilforphoniebox.transport

import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.VolumeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The box publishes MPD's own status, which means numbers arrive as strings and whole
 * fields go missing depending on the content. These are the shapes seen in practice.
 */
class StatusParserTest {

    @Test
    fun `parses a typical playing status with stringified numbers`() {
        val raw = """
            {
              "state": "play", "elapsed": "12.345", "duration": "204.6",
              "song": "3", "songid": "17", "playlistlength": "12",
              "repeat": "0", "random": "0", "single": "0",
              "file": "Audiobooks/Bibi/01.mp3", "title": "Chapter 1",
              "artist": "Bibi Blocksberg", "albumartist": "Bibi Blocksberg",
              "album": "Hexerei"
            }
        """.trimIndent()

        val status = StatusParser.playerStatus(raw, receivedAtElapsedMillis = 42L)!!

        assertEquals(PlaybackState.PLAY, status.state)
        assertEquals(12.345, status.elapsedSeconds!!, 0.001)
        assertEquals(204.6, status.durationSeconds!!, 0.001)
        assertEquals(3, status.playlistPosition)
        assertEquals("17", status.songId)
        assertEquals(12, status.playlistLength)
        assertEquals(RepeatMode.OFF, status.repeat)
        assertFalse(status.shuffle)
        assertEquals("Chapter 1", status.title)
        assertEquals("Audiobooks/Bibi", status.folder)
        assertEquals(42L, status.receivedAtElapsedMillis)
    }

    @Test
    fun `web radio has neither duration nor album`() {
        val raw = """{"state":"play","elapsed":"91.0","file":"http://stream.example/live","title":"Some show"}"""

        val status = StatusParser.playerStatus(raw, 0L)!!

        assertNull(status.durationSeconds)
        assertNull(status.album)
        assertTrue(status.hasContent)
    }

    @Test
    fun `an idle box reports stop and stays parseable`() {
        val status = StatusParser.playerStatus("""{"state":"stop","playlistlength":"0"}""", 0L)!!

        assertEquals(PlaybackState.STOP, status.state)
        assertEquals(0, status.playlistLength)
        assertFalse(status.hasContent)
    }

    @Test
    fun `repeat and single together decide the repeat mode`() {
        fun mode(repeat: String, single: String) = StatusParser
            .playerStatus("""{"state":"play","repeat":"$repeat","single":"$single","file":"a.mp3"}""", 0L)!!
            .repeat

        assertEquals(RepeatMode.OFF, mode("0", "0"))
        assertEquals(RepeatMode.ALL, mode("1", "0"))
        assertEquals(RepeatMode.ONE, mode("1", "1"))
        // MPD also has a one-shot single mode, which is closer to "one" than to "off".
        assertEquals(RepeatMode.ONE, mode("1", "oneshot"))
    }

    @Test
    fun `falls back to the old time field when duration is absent`() {
        val status = StatusParser.playerStatus("""{"state":"play","time":"30:180","file":"a.mp3"}""", 0L)!!

        assertEquals(30.0, status.elapsedSeconds!!, 0.001)
        assertEquals(180.0, status.durationSeconds!!, 0.001)
    }

    @Test
    fun `prefers the album artist over the per-track artist`() {
        val raw = """{"state":"play","file":"a.mp3","artist":"Narrator","albumartist":"Series"}"""

        assertEquals("Series", StatusParser.playerStatus(raw, 0L)!!.artist)
    }

    @Test
    fun `rubbish payloads return null rather than throwing`() {
        assertNull(StatusParser.playerStatus("not json at all", 0L))
        assertNull(StatusParser.playerStatus("", 0L))
    }

    @Test
    fun `volume arrives as an object and keeps the soft maximum`() {
        val previous = VolumeStatus(level = 10, maxLevel = 80, muted = false)

        val volume = StatusParser.volume("""{"volume": 42, "mute": true}""", previous)!!

        assertEquals(42, volume.level)
        assertTrue(volume.muted)
        assertEquals(80, volume.maxLevel)
    }

    @Test
    fun `volume also tolerates a bare number`() {
        val volume = StatusParser.volume("37", VolumeStatus(level = 10, maxLevel = 90))!!

        assertEquals(37, volume.level)
        assertEquals(90, volume.maxLevel)
    }

    @Test
    fun `soft maximum outside the plausible range is ignored`() {
        assertEquals(75, StatusParser.softMaxVolume(jsonPrimitive("75")))
        assertNull(StatusParser.softMaxVolume(jsonPrimitive("0")))
        assertNull(StatusParser.softMaxVolume(jsonPrimitive("140")))
    }

    @Test
    fun `version is read from a topic payload with or without quotes`() {
        assertEquals("v3.6.0", StatusParser.versionFromTopic("\"v3.6.0\""))
        assertEquals("v3.6.0", StatusParser.versionFromTopic("v3.6.0"))
    }

    private fun jsonPrimitive(value: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(value)
}
