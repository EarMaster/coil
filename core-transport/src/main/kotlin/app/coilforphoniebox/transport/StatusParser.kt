package app.coilforphoniebox.transport

import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.SleepTimerStatus
import app.coilforphoniebox.domain.model.VolumeStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns published payloads into domain models.
 *
 * `playerstatus` is MPD's own `status` merged with `currentsong`, with `volume`
 * removed — volume travels on its own topic. Field names come from MPD and are not a
 * contract Coil can rely on, so every one of them is centralised here: if upstream
 * renames something, this file is the only place that needs to change (§15).
 */
object StatusParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseObject(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    /** Returns null when the payload is not usable at all. */
    fun playerStatus(raw: String, receivedAtElapsedMillis: Long): PlayerStatus? =
        parseObject(raw)?.let { playerStatus(it, receivedAtElapsedMillis) }

    /**
     * Also used for the `player.ctrl.playerstatus` RPC result, which returns the very
     * same structure — handy right after a rebuilt connection, where waiting for the
     * publisher's last-value cache would be a guess.
     */
    fun playerStatus(obj: JsonObject, receivedAtElapsedMillis: Long): PlayerStatus {
        return PlayerStatus(
            state = playbackState(obj.string("state")),
            title = obj.string("title"),
            // `albumartist` is the better label for an audiobook; `artist` is the
            // per-track value and can differ track by track within one folder.
            artist = obj.string("albumartist") ?: obj.string("artist"),
            album = obj.string("album"),
            file = obj.string("file"),
            elapsedSeconds = obj.double("elapsed") ?: elapsedFromTimeField(obj),
            durationSeconds = obj.double("duration") ?: totalFromTimeField(obj),
            songId = obj.string("songid") ?: obj.string("id"),
            // `song` is the queue position from `status`, `pos` the one from
            // `currentsong`. They agree; both are read so neither being absent hurts.
            playlistPosition = obj.int("song") ?: obj.int("pos"),
            playlistLength = obj.int("playlistlength") ?: 0,
            repeat = repeatMode(obj),
            shuffle = obj.boolean("random") ?: false,
            receivedAtElapsedMillis = receivedAtElapsedMillis,
        )
    }

    /**
     * `volume.level` publishes `{"volume": 42, "mute": false}`. [previous] carries the
     * soft maximum forward, which only ever arrives over RPC.
     */
    fun volume(raw: String, previous: VolumeStatus): VolumeStatus? {
        val obj = parseObject(raw)
            ?: return (raw.trim().toIntOrNull())?.let { previous.copy(level = it) }

        val level = obj.int("volume") ?: obj.int("level") ?: return null
        return previous.copy(
            level = level,
            muted = obj.boolean("mute") ?: obj.boolean("muted") ?: previous.muted,
        )
    }

    /**
     * `timers.timer_stop_player` publishes, and `get_state` returns,
     * `{"enabled": true, "remaining_seconds": 1187, "wait_seconds": 1800.0,
     * "type": "GenericTimerClass"}`.
     *
     * Read leniently like every other payload here: `enabled` alone is enough to know
     * whether a timer is running, and a missing or stringified `remaining_seconds` gives a
     * running timer with nothing to count down rather than no timer at all.
     */
    fun sleepTimer(payload: JsonElement?, receivedAtElapsedMillis: Long): SleepTimerStatus? {
        val obj = payload as? JsonObject ?: return null
        val enabled = obj.boolean("enabled") ?: return null
        if (!enabled) return SleepTimerStatus.Off

        return SleepTimerStatus(
            running = true,
            remainingSeconds = (obj.int("remaining_seconds") ?: 0).coerceAtLeast(0),
            requestedSeconds = obj.int("wait_seconds")?.takeIf { it > 0 },
            receivedAtElapsedMillis = receivedAtElapsedMillis,
        )
    }

    fun sleepTimer(raw: String, receivedAtElapsedMillis: Long): SleepTimerStatus? =
        sleepTimer(parseObject(raw), receivedAtElapsedMillis)

    /** `core.version` arrives as a bare string on the topic and as a string over RPC. */
    fun version(payload: JsonElement?): String? = when (payload) {
        is JsonPrimitive -> payload.content.takeIf { it.isNotBlank() && it != "null" }
        is JsonObject -> payload.string("version") ?: payload.string("value")
        else -> null
    }

    /**
     * A payload that is a bare JSON string, and nothing else. Structured payloads return
     * null rather than a stringified object, because this ends up on screen.
     */
    fun plainString(raw: String): String? =
        (runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }

    fun versionFromTopic(raw: String): String? =
        runCatching { version(json.parseToJsonElement(raw)) }.getOrNull()
            ?: raw.trim().trim('"').takeIf { it.isNotBlank() }

    /** `get_soft_max_volume` returns a bare number. */
    fun softMaxVolume(payload: JsonElement?): Int? = when (payload) {
        is JsonPrimitive -> payload.content.toDoubleOrNull()?.toInt()
        is JsonObject -> payload.int("volume") ?: payload.int("max")
        else -> null
    }?.takeIf { it in 1..100 }

    private fun playbackState(value: String?): PlaybackState = when (value?.lowercase()) {
        "play" -> PlaybackState.PLAY
        "pause" -> PlaybackState.PAUSE
        else -> PlaybackState.STOP
    }

    /**
     * MPD reports `single` as `"0"`, `"1"` or `"oneshot"`; `repeat` plus `single`
     * together are what the box's own `repeat` option strings map onto.
     */
    private fun repeatMode(obj: JsonObject): RepeatMode {
        val repeat = obj.boolean("repeat") ?: false
        val single = obj.boolean("single") ?: (obj.string("single") == "oneshot")
        return when {
            single -> RepeatMode.ONE
            repeat -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
    }

    /** Older MPD protocol: `"time": "<elapsed>:<total>"`. */
    private fun elapsedFromTimeField(obj: JsonObject): Double? =
        obj.string("time")?.substringBefore(':')?.toDoubleOrNull()

    private fun totalFromTimeField(obj: JsonObject): Double? =
        obj.string("time")?.substringAfter(':', missingDelimiterValue = "")?.toDoubleOrNull()
}
