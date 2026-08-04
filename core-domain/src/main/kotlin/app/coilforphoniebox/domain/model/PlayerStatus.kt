package app.coilforphoniebox.domain.model

enum class PlaybackState { PLAY, PAUSE, STOP }

enum class RepeatMode { OFF, ALL, ONE }

/**
 * The box's `playerstatus` topic, normalised.
 *
 * Every field is optional on purpose: MPD omits whole fields depending on the
 * content — web radio has no `duration` and no `album` — and returns some numbers
 * as strings. Nothing here may be treated as a stable contract (§4.2).
 */
data class PlayerStatus(
    val state: PlaybackState = PlaybackState.STOP,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** MPD URL of the current song, relative to the box's music library root. */
    val file: String? = null,
    val elapsedSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val songId: String? = null,
    val playlistPosition: Int? = null,
    val playlistLength: Int = 0,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    /** Elapsed system uptime when this status was received, for interpolation. */
    val receivedAtElapsedMillis: Long = 0L,
) {
    val isPlaying: Boolean get() = state == PlaybackState.PLAY

    /** Whether anything is loaded at all — an empty playlist means an idle box. */
    val hasContent: Boolean get() = file != null || title != null

    /** Folder the current song sits in, used to offer "save this as a favourite". */
    val folder: String? get() = file?.substringBeforeLast('/', missingDelimiterValue = "")?.ifEmpty { null }

    companion object {
        val Idle = PlayerStatus()
    }
}
