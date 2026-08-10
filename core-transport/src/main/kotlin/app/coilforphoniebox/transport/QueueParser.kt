package app.coilforphoniebox.transport

import app.coilforphoniebox.domain.model.QueueEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Parses `player.ctrl.playlistinfo` — the box's current MPD queue.
 *
 * Its own file rather than a method on one of the neighbours, because it belongs to neither:
 * [LibraryParser] handles "RPC results that feed the library cache" and a queue is never
 * cached, while [StatusParser] handles the published topics and the queue is never published.
 *
 * Read as leniently as everything else from the box. Keys arrive lower-cased from python-mpd2
 * (`pos`, `title`, `duration`), but MPD's own spelling is capitalised (`Pos:`, `Title:`) and
 * the layer in between is not a contract Coil can rely on, so both are accepted — the same
 * belt-and-braces [StatusParser] applies when it reads `songid` and `id` (§4.2).
 */
object QueueParser {

    /**
     * `playlistinfo` returns one object per queued song:
     * `[{"file": "Bibi/01.mp3", "pos": "0", "id": "17", "title": "Kapitel 1",
     *   "artist": "…", "album": "…", "duration": "243.1"}, …]`
     *
     * An entry with no `file` is dropped: the URL is the one field the app cannot do
     * without — it identifies the row against `playerstatus.file` and there is nothing to
     * fall back to.
     */
    fun queue(result: JsonElement?): List<QueueEntry> {
        val entries = result as? JsonArray ?: return emptyList()

        return entries.mapIndexedNotNull { index, element ->
            val entry = element as? JsonObject ?: return@mapIndexedNotNull null
            val url = entry.anyString("file", "File") ?: return@mapIndexedNotNull null

            QueueEntry(
                // A missing `pos` falls back to the array index: the queue arrives in play
                // order, so the index *is* the position — but the box's own value wins when
                // it is there, because that is what `playerstatus` will be compared against.
                position = entry.anyInt("pos", "Pos") ?: index,
                url = url,
                // Most of a Phoniebox library is untagged rips, so the title tag is missing
                // far more often than not. The file name is what the library rows show for
                // exactly the same reason (`LibraryParser` keeps the entry's `name`).
                title = entry.anyString("title", "Title") ?: url.substringAfterLast('/'),
                // `albumartist` is the better label within one folder — `artist` is
                // per-track and can differ track by track, same as in `playerstatus`.
                artist = entry.anyString("albumartist", "AlbumArtist", "artist", "Artist"),
                album = entry.anyString("album", "Album"),
                // Absent on streams. `time` is the older protocol's integer seconds.
                durationSeconds = entry.anyDouble("duration", "Duration")
                    ?: entry.anyDouble("time", "Time"),
                songId = entry.anyString("id", "Id"),
            )
        }
    }

    private fun JsonObject.anyString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { string(it) }

    private fun JsonObject.anyInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { int(it) }

    private fun JsonObject.anyDouble(vararg keys: String): Double? =
        keys.firstNotNullOfOrNull { double(it) }
}
