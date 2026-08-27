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
     * The title tag, and null when the file has none — which is most of a Phoniebox library.
     *
     * Nullable rather than pre-filled with the file name because absence is information:
     * [PlayerStatus.reconciledWith] reads these three tags as *the* account of what this file
     * is tagged with, and a fallback baked in here would make an untagged song indistinguishable
     * from one tagged with its own file name. [displayTitle] is what goes on screen.
     */
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationSeconds: Double? = null,
    /**
     * MPD's queue id, unique within the queue and stable while it lasts — which is what the
     * media session needs for a timeline item's UID. Null when the box did not send one.
     */
    val songId: String? = null,
) {
    /**
     * What to show. Falls back to the file name the way [LibraryTrack.displayTitle] and
     * [PlayerStatus.displayTitle] do, and for the same reason: a row with no text at all would
     * be unusable, and a media3 item with no title at all is what the platform answers with
     * "<app> is running".
     */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')
}
