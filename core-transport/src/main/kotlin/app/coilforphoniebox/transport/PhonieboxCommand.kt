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
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000L

        /**
         * Library calls run synchronously on the box and can take a while on a large
         * collection. They cannot be handed off with `as_thread` — see [Commands].
         */
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
 *
 * **`as_thread` is never sent.** The implementation plan suggests it for slow calls, but
 * `jukebox/plugs.py` starts a daemon thread and returns the `Thread` object rather than
 * the function's result — so any call that carries it answers with something unusable.
 * It is fire-and-forget only, and every call here needs its result. The cost is that a
 * library call occupies the box's sequential RPC loop, and with it card detection, for as
 * long as it runs; the mitigation is to call these rarely and never on a timer (§6).
 */
object Commands {

    // -------------------------------------------------------------------- ping

    /**
     * Reachability check, also used as the watchdog's ping.
     *
     * There is no `core.version` RPC — the box only *publishes* `core.*` topics, and
     * asking for that package answers with an error, which used to make every connection
     * test fail. `playerstatus` returns the poller's cached dict without touching MPD, so
     * it is both cheap and proof that the jukebox app itself is answering.
     */
    val ping = player("playerstatus", retryable = true)

    // ---------------------------------------------------------------- player

    val play = player("play", retryable = true)
    val pause = player("pause", retryable = true)

    /** Relative to the current state, so never retried. */
    val toggle = player("toggle")
    val next = player("next")
    val previous = player("prev")

    /**
     * Start playing the queue entry at [position].
     *
     * **Not part of upstream `future3` yet.** `playermpd.play` is `def play(self)` on both
     * `future3/main` and `future3/develop`, so an unpatched box answers this with
     * `TypeError: play() got an unexpected keyword argument 'pos'` — `jukebox/rpc/server.py`
     * wraps `plugs.call` in a try/except and formats any exception as an error reply. The
     * important part is that the exception happens *before* the body runs, so a rejected
     * call changes nothing on the box and can be used as a free capability probe.
     *
     * MPD itself has always supported it, and the box uses it internally
     * (`_next_in_stopped_state` calls `self.mpd_client.play(pos)`); only the RPC signature is
     * missing. Until it lands upstream, callers fall back to stepping with [next]/[previous]
     * — see `PlayerRepository.playAt`.
     */
    fun playAt(position: Int) = player(
        "play",
        kwargs = mapOf("pos" to JsonPrimitive(position)),
        retryable = true,
    )

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
     * Same call as [ping], named for the other thing it is used for: pulling the status
     * once after a rebuilt connection instead of waiting for the next publish.
     *
     * Never polled — the box publishes at 4 Hz on its own, and the last-value cache
     * covers a fresh subscription (§4.2.1).
     */
    val playerStatus = ping

    // --------------------------------------------------------------- content

    /** One level only. `list_all_dirs` is never used — unbounded memory on the box. */
    fun folderContent(folder: String) = player(
        "get_folder_content",
        kwargs = mapOf("folder" to JsonPrimitive(folder)),
        timeoutMillis = PhonieboxCommand.LIBRARY_TIMEOUT_MILLIS,
        retryable = true,
    )

    val listAlbums = player(
        "list_albums",
        timeoutMillis = PhonieboxCommand.LIBRARY_TIMEOUT_MILLIS,
        retryable = true,
    )

    /**
     * The box's current MPD queue, one entry per track.
     *
     * The queue is **never published** — `playermpd` only ever sends `playerstatus` — so this
     * is the sole way to learn what is queued behind the playing song. The web UI does not
     * call it, which is why `docs/protocol-notes.md` used not to list it, but it is a real
     * `@plugs.tag` method on both `future3/main` and `future3/develop`.
     *
     * It is a synchronous MPD call on the socket the box shares with its card reader, and a
     * recursively added artist folder can be thousands of entries — hence the library
     * timeout, and hence it is asked once per *queue change* and never on a timer (§6).
     */
    val playlistInfo = player(
        "playlistinfo",
        timeoutMillis = PhonieboxCommand.LIBRARY_TIMEOUT_MILLIS,
        retryable = true,
    )

    fun singleCoverArt(songUrl: String) = player(
        "get_single_coverart",
        kwargs = mapOf("song_url" to JsonPrimitive(songUrl)),
        retryable = true,
    )

    fun albumCoverArt(albumArtist: String, album: String) = player(
        "get_album_coverart",
        kwargs = mapOf(
            "albumartist" to JsonPrimitive(albumArtist),
            "album" to JsonPrimitive(album),
        ),
        retryable = true,
    )

    /**
     * Starts the box's MPD database scan. `update_wait` blocks until the scan is
     * finished and is avoided for that reason (§6.4).
     */
    val updateLibrary = player("update", retryable = true)

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

    // ----------------------------------------------------------- sleep timer

    /**
     * The box's timer that *stops the player*, and none of its siblings.
     *
     * `timers` also holds `timer_shutdown`, `timer_idle_shutdown` and `timer_fade_volume`.
     * The two shutdown timers are deliberately unreachable from here: they switch the box
     * off, which is exactly what Coil promises never to do (§1, §16). Adding them would also
     * mean an unauthenticated LAN port could power down the box on a delay.
     *
     * `wait_seconds` it is — the plugin's own default is `default_timeout_sec`.
     */
    fun startSleepTimer(seconds: Int) = timer(
        "start",
        kwargs = mapOf("wait_seconds" to JsonPrimitive(seconds)),
    )

    val cancelSleepTimer = timer("cancel")

    /**
     * Asked once when the timer UI opens. The state *is* published, but only when it
     * changes, so a box that has not touched its timer since booting has nothing in the
     * last-value cache to hand over on subscribe.
     */
    val sleepTimerState = timer("get_state", retryable = true)

    // --------------------------------------------------------------- helpers

    private fun player(
        method: String,
        kwargs: Map<String, JsonElement> = emptyMap(),
        timeoutMillis: Long = PhonieboxCommand.DEFAULT_TIMEOUT_MILLIS,
        retryable: Boolean = false,
    ) = PhonieboxCommand("player", "ctrl", method, kwargs, timeoutMillis, retryable)

    private fun volume(
        method: String,
        kwargs: Map<String, JsonElement> = emptyMap(),
        retryable: Boolean = false,
    ) = PhonieboxCommand("volume", "ctrl", method, kwargs, retryable = retryable)

    /** Hard-wired to `timer_stop_player`: no other timer plugin is addressable from here. */
    private fun timer(
        method: String,
        kwargs: Map<String, JsonElement> = emptyMap(),
        retryable: Boolean = false,
    ) = PhonieboxCommand(TIMER_PACKAGE, TIMER_STOP_PLAYER, method, kwargs, retryable = retryable)

    private const val TIMER_PACKAGE = "timers"
    private const val TIMER_STOP_PLAYER = "timer_stop_player"

    /** The topic that plugin publishes its state on: `<package>.<plugin>`. */
    const val TIMER_STOP_PLAYER_TOPIC = "$TIMER_PACKAGE.$TIMER_STOP_PLAYER"
}
