package app.coilforphoniebox.domain.model

/**
 * One entry of the box's current MPD queue.
 *
 * The queue is live playback state, in the same category as [PlayerStatus]: it comes from the
 * box, it is never persisted, and it is thrown away when the active box changes (§6.2). It is
 * *not* library data — the tracks in it may sit in folders Coil has never browsed, and the
 * order is MPD's, not the library cache's.
 *
 * Every field except [position] and [url] is optional, for the same reason every field of
 * [PlayerStatus] is: MPD omits whatever the file has no tag for, and a Phoniebox library is
 * largely untagged rips.
 */
data class QueueEntry(
    /** Zero-based position in the queue, matching [PlayerStatus.playlistPosition]. */
    val position: Int,
    /** MPD URL of the song, relative to the box's music library root. */
    val url: String,
    /**
     * What to show. Falls back to the file name when the song carries no title tag, which is
     * what the library rows do too — a row with no text at all would be unusable.
     */
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationSeconds: Double? = null,
    /**
     * MPD's queue id, unique within the queue and stable while it lasts — which is what the
     * media session needs for a timeline item's UID. Null when the box did not send one.
     */
    val songId: String? = null,
)
