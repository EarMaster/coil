package app.coilforphoniebox.transport

import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.RepeatMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One RPC call. Mirrors the payload the box expects:
 *
 * ```json
 * { "id": "<uuid>", "package": "player", "plugin": "ctrl",
 *   "method": "play_folder", "kwargs": { "folder": "Audiobooks/Bibi" } }
 * ```
 */
data class PhonieboxCommand(
    val pkg: String,
    val plugin: String,
    val method: String? = null,
    val kwargs: Map<String, JsonElement> = emptyMap(),
    /**
     * Runs the call on its own thread on the box. Set for anything potentially slow:
     * the RPC socket is shared with the box's own GPIO and RFID handling, so a
     * blocking call delays card detection on the box itself (§6).
     */
    val asThread: Boolean = false,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    /**
     * Whether it is safe to send this command again after a timeout.
     *
     * Absolute commands (`set_volume`, `play_folder`, any getter) are; relative or
     * toggling ones (`next`, `change_volume`, `toggle`) are not — a retried `next`
     * would skip two tracks when the first request did arrive after all.
     */
    val retryable: Boolean = false,
) {
    /** Full dotted name, for logs and error messages. */
    val name: String get() = listOfNotNull(pkg, plugin, method).joinToString(".")

    fun toJson(id: String): JsonObject = buildJsonObject {
        put("id", id)
        put("package", pkg)
        put("plugin", plugin)
        method?.let { put("method", it) }
        if (kwargs.isNotEmpty()) put("kwargs", JsonObject(kwargs))
        if (asThread) put("as_thread", true)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L

        /** Library calls can take a while on a large collection even with `as_thread`. */
        const val LIBRARY_TIMEOUT_MILLIS = 15_000L
    }
}

/**
 * The commands Coil sends, and deliberately no others.
 *
 * Anything beyond playback stays out — `host.shutdown` and friends are never
 * reachable from this app, which limits the damage if the box's unauthenticated RPC
 * port is ever accidentally exposed (§16).
 *
 * Argument names are taken from the plugin signatures in
 * `src/jukebox/components/player/playermpd/__init__.py` and
 * `src/jukebox/components/volume/__init__.py` on `future3/main`, not from the web
 * UI's command table, which omits several of them.
 */
object Commands {

    // ------------------------------------------------------------------ core

    val version = PhonieboxCommand("core", "version", retryable = true)

    // ---------------------------------------------------------------- player

    val play = player("play", retryable = true)
    val pause = player("pause", retryable = true)

    /** Relative to the current state, so never retried. */
    val toggle = player("toggle")
    val next = player("next")
    val previous = player("prev")

    fun seek(positionSeconds: Double) = player(
        "seek",
        kwargs = mapOf("new_time" to JsonPrimitive(positionSeconds)),
        retryable = true,
    )

    /**
     * `shuffle` takes a named option rather than a flag. The box also accepts
     * `toggle`, which is deliberately unused: the app knows the current state from
     * `playerstatus`, and an absolute value survives a retry.
     */
    fun shuffle(enabled: Boolean) = player(
        "shuffle",
        kwargs = mapOf("option" to JsonPrimitive(if (enabled) "enable" else "disable")),
        retryable = true,
    )

    /**
     * `repeat` also takes an option string. The box's own `toggle` cycles
     * off → all → one, which is what the UI does — but it does so by asking for the
     * target mode outright.
     */
    fun repeat(mode: RepeatMode) = player(
        "repeat",
        kwargs = mapOf(
            "option" to JsonPrimitive(
                when (mode) {
                    RepeatMode.OFF -> "disable"
                    RepeatMode.ALL -> "enable_repeat"
                    RepeatMode.ONE -> "enable_repeat_single"
                },
            ),
        ),
        retryable = true,
    )

    /**
     * Returns the current status directly instead of waiting for the next publish.
     *
     * Never polled — the box publishes at 4 Hz on its own and the last-value cache
     * covers a fresh subscription (§4.2.1). This exists only for the uncertain case
     * right after a rebuilt connection.
     */
    val playerStatus = player("playerstatus", retryable = true)

    // --------------------------------------------------------------- content

    /** One level only. `list_all_dirs` is never used — unbounded memory on the box. */
    fun folderContent(folder: String) = player(
        "get_folder_content",
        kwargs = mapOf("folder" to JsonPrimitive(folder)),
        asThread = true,
        timeoutMillis = PhonieboxCommand.LIBRARY_TIMEOUT_MILLIS,
        retryable = true,
    )

    val listAlbums = player(
        "list_albums",
        asThread = true,
        timeoutMillis = PhonieboxCommand.LIBRARY_TIMEOUT_MILLIS,
        retryable = true,
    )

    fun singleCoverArt(songUrl: String) = player(
        "get_single_coverart",
        kwargs = mapOf("song_url" to JsonPrimitive(songUrl)),
        asThread = true,
        retryable = true,
    )

    fun albumCoverArt(albumArtist: String, album: String) = player(
        "get_album_coverart",
        kwargs = mapOf(
            "albumartist" to JsonPrimitive(albumArtist),
            "album" to JsonPrimitive(album),
        ),
        asThread = true,
        retryable = true,
    )

    /**
     * Starts the box's MPD database scan. `update_wait` blocks until the scan is
     * finished and is avoided for that reason (§6.4).
     */
    val updateLibrary = player("update", asThread = true, retryable = true)

    fun play(target: PlayTarget): PhonieboxCommand = when (target) {
        is PlayTarget.Folder -> player(
            "play_folder",
            kwargs = mapOf("folder" to JsonPrimitive(target.path)),
            retryable = true,
        )

        is PlayTarget.Album -> player(
            "play_album",
            kwargs = mapOf(
                "albumartist" to JsonPrimitive(target.albumArtist),
                "album" to JsonPrimitive(target.album),
            ),
            retryable = true,
        )

        is PlayTarget.Track -> player(
            "play_single",
            kwargs = mapOf("song_url" to JsonPrimitive(target.url)),
            retryable = true,
        )
    }

    // ---------------------------------------------------------------- volume

    // No `get_volume`: the level arrives on the `volume.level` topic four times a second,
    // and asking for it would only occupy the socket the box needs for its card reader.

    /** Upper limit configured on the box; used as `deviceVolumeMax` (§8.1). */
    val softMaxVolume = volume("get_soft_max_volume", retryable = true)

    fun setVolume(level: Int) = volume(
        "set_volume",
        kwargs = mapOf("volume" to JsonPrimitive(level)),
        retryable = true,
    )

    /** Relative, so never retried. */
    fun changeVolume(step: Int) = volume(
        "change_volume",
        kwargs = mapOf("step" to JsonPrimitive(step)),
    )

    /**
     * `mute` sets an absolute state despite the name — it is not a toggle. The caller
     * passes the state it wants, which also makes it safe to retry.
     */
    fun mute(muted: Boolean) = volume(
        "mute",
        kwargs = mapOf("mute" to JsonPrimitive(muted)),
        retryable = true,
    )

    // --------------------------------------------------------------- helpers

    private fun player(
        method: String,
        kwargs: Map<String, JsonElement> = emptyMap(),
        asThread: Boolean = false,
        timeoutMillis: Long = PhonieboxCommand.DEFAULT_TIMEOUT_MILLIS,
        retryable: Boolean = false,
    ) = PhonieboxCommand("player", "ctrl", method, kwargs, asThread, timeoutMillis, retryable)

    private fun volume(
        method: String,
        kwargs: Map<String, JsonElement> = emptyMap(),
        retryable: Boolean = false,
    ) = PhonieboxCommand("volume", "ctrl", method, kwargs, retryable = retryable)
}
