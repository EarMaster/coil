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

    /**
     * `jukebox/plugs.py` starts a daemon thread for an `as_thread` call and returns the
     * Thread object instead of the function's result, so a call that carries it answers
     * with something unusable. Every command here needs its result.
     */
    @Test
    fun `as_thread is never sent, because it discards the result`() {
        val commands = listOf(
            Commands.ping,
            Commands.listAlbums,
            Commands.folderContent("Audiobooks"),
            Commands.singleCoverArt("A/01.mp3"),
            Commands.albumCoverArt("X", "A"),
            Commands.updateLibrary,
            Commands.play(PlayTarget.Folder("x")),
        )
        commands.forEach { command ->
            assertNull("${command.name} must not set as_thread", command.toJson("id")["as_thread"])
        }
    }

    /**
     * There is no `core` RPC package — `core.*` are published topics only — so asking for
     * `core.version` answers with an error and every connection test failed.
     */
    @Test
    fun `the reachability ping is playerstatus, not core version`() {
        assertEquals("player.ctrl.playerstatus", Commands.ping.name)
        assertEquals("player", Commands.ping.pkg)
        assertTrue(Commands.ping.retryable)
    }

    @Test
    fun `library calls get a longer timeout than the rest`() {
        assertEquals(PhonieboxCommand.LIBRARY_TIMEOUT_MILLIS, Commands.listAlbums.timeoutMillis)
        assertEquals(
            PhonieboxCommand.LIBRARY_TIMEOUT_MILLIS,
            Commands.folderContent("Audiobooks").timeoutMillis,
        )
        assertEquals(PhonieboxCommand.DEFAULT_TIMEOUT_MILLIS, Commands.play.timeoutMillis)
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
        assertTrue(Commands.ping.retryable)

        assertFalse(Commands.next.retryable)
        assertFalse(Commands.previous.retryable)
        assertFalse(Commands.toggle.retryable)
        assertFalse(Commands.changeVolume(5).retryable)
    }

    @Test
    fun `command name is the dotted plugin path`() {
        assertEquals("player.ctrl.play_folder", Commands.play(PlayTarget.Folder("x")).name)
        assertEquals("volume.ctrl.set_volume", Commands.setVolume(30).name)
    }
}
