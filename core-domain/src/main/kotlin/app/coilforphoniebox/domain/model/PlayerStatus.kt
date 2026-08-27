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

    /**
     * What to call the current track. Never blank while a file is loaded, which the media session
     * depends on: media3 posts a notification with no content title for a null one, and the
     * platform fills that hole with its own "<app> is running".
     *
     * The tag first, then the file name — the same fallback `QueueParser` and
     * [LibraryTrack.displayTitle] apply, and for the same reason: most of a Phoniebox library is
     * untagged rips, so a missing title tag is the common case rather than the odd one.
     */
    val displayTitle: String?
        get() = title?.takeIf { it.isNotBlank() }
            ?: file?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /**
     * The same status with [title], [artist] and [album] checked against the box's queue —
     * because `playerstatus` carries the *previous* song's tags for a song that has none.
     *
     * The box builds that payload as `mpd_status.update(status())` then
     * `.update(currentsong())` into one dictionary it created at startup and never clears
     * (`playermpd/__init__.py`, and the same code in the provider-neutral player). MPD omits
     * the tags a file does not have rather than sending them empty, so a `dict.update` only
     * ever *overwrites* tags — an untagged track inherits the title, artist and album of the
     * last tagged one the box played, and keeps them for the whole album. Which is why a rip
     * with no tags shows up under some other album's name, its own artist beside it: the
     * artist happened to be overwritten and the album did not.
     *
     * `playlistinfo` is not merged like that — each entry is MPD's own answer for that file —
     * so the queue row matching [file] is the only account of what this song is really tagged
     * with, and a tag missing there cannot belong to this song.
     *
     * Two things it deliberately does not do:
     * - **Streams keep the published tags.** ICY metadata reaches `currentsong` live while the
     *   cached queue row still describes the station, so there the payload is the fresher of
     *   the two.
     * - **An unmatched file is left alone.** The queue is fetched once per queue change, so
     *   between a card tap and the answer the new song is in no cached row; the previous tags
     *   stay up for that second rather than blinking out on every tagged album too.
     */
    fun reconciledWith(queue: List<QueueEntry>): PlayerStatus {
        val file = file ?: return this
        if (file.contains("://")) return this
        val entry = queue.firstOrNull { it.url == file } ?: return this

        val reconciled = copy(title = entry.title, artist = entry.artist, album = entry.album)
        // The same instance when the box was right, so the four-a-second status path stays
        // free of copies nothing downstream can tell apart.
        return if (reconciled == this) this else reconciled
    }

    companion object {
        val Idle = PlayerStatus()
    }
}
