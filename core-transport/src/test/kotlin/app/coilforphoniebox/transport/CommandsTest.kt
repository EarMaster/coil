package app.coilforphoniebox.transport

import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.RepeatMode
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Argument names and values here have to match the box's plugin signatures exactly — a
 * wrong kwarg name fails silently, because the box answers either way.
 */
class CommandsTest {

    @Test
    fun `payload carries id, package, plugin, method and kwargs`() {
        val json = Commands.play(PlayTarget.Folder("Audiobooks/Bibi")).toJson("abc-123")

        assertEquals("abc-123", json["id"]?.jsonPrimitive?.content)
        assertEquals("player", json["package"]?.jsonPrimitive?.content)
        assertEquals("ctrl", json["plugin"]?.jsonPrimitive?.content)
        assertEquals("play_folder", json["method"]?.jsonPrimitive?.content)
        assertEquals(
            "Audiobooks/Bibi",
            json["kwargs"]?.jsonObject?.get("folder")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `kwargs are omitted when there are none`() {
        val json = Commands.toggle.toJson("id")

        assertNull(json["kwargs"])
        assertNull(json["as_thread"])
    }

    @Test
    fun `library calls run on their own thread on the box`() {
        assertTrue(Commands.listAlbums.asThread)
        assertTrue(Commands.folderContent("Audiobooks").asThread)
        assertTrue(Commands.folderContent("Audiobooks").toJson("id")["as_thread"] != null)
    }

    @Test
    fun `shuffle takes an option string rather than a flag`() {
        assertEquals("enable", Commands.shuffle(true).kwargs["option"]?.jsonPrimitive?.content)
        assertEquals("disable", Commands.shuffle(false).kwargs["option"]?.jsonPrimitive?.content)
    }

    @Test
    fun `repeat maps each mode onto the box's own option names`() {
        fun option(mode: RepeatMode) = Commands.repeat(mode).kwargs["option"]?.jsonPrimitive?.content

        assertEquals("disable", option(RepeatMode.OFF))
        assertEquals("enable_repeat", option(RepeatMode.ALL))
        assertEquals("enable_repeat_single", option(RepeatMode.ONE))
    }

    @Test
    fun `mute is absolute, not a toggle`() {
        assertEquals("true", Commands.mute(true).kwargs["mute"]?.jsonPrimitive?.content)
        assertEquals("false", Commands.mute(false).kwargs["mute"]?.jsonPrimitive?.content)
    }

    @Test
    fun `seek uses new_time`() {
        assertEquals("12.5", Commands.seek(12.5).kwargs["new_time"]?.jsonPrimitive?.content)
    }

    @Test
    fun `album playback needs both keys`() {
        val command = Commands.play(PlayTarget.Album("Bibi Blocksberg", "Hexerei"))

        assertEquals("play_album", command.method)
        assertEquals("Bibi Blocksberg", command.kwargs["albumartist"]?.jsonPrimitive?.content)
        assertEquals("Hexerei", command.kwargs["album"]?.jsonPrimitive?.content)
    }

    /**
     * The distinction that keeps a lost reply from skipping two tracks: absolute commands
     * may be retried, relative ones may not.
     */
    @Test
    fun `only absolute commands are retryable`() {
        assertTrue(Commands.play.retryable)
        assertTrue(Commands.setVolume(30).retryable)
        assertTrue(Commands.play(PlayTarget.Folder("x")).retryable)
        assertTrue(Commands.version.retryable)

        assertFalse(Commands.next.retryable)
        assertFalse(Commands.previous.retryable)
        assertFalse(Commands.toggle.retryable)
        assertFalse(Commands.changeVolume(5).retryable)
    }

    @Test
    fun `command name is the dotted plugin path`() {
        assertEquals("player.ctrl.play_folder", Commands.play(PlayTarget.Folder("x")).name)
        assertEquals("core.version", Commands.version.name)
    }
}
