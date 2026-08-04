package app.coilforphoniebox.transport

import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.LibraryFolder
import app.coilforphoniebox.domain.model.LibraryTrack
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Parses the RPC results that feed the library cache. */
object LibraryParser {

    /**
     * `get_folder_content` returns one level as
     * `[{ "type": "directory", "name": "Bibi", "path": "/abs/path", "relpath": "Audiobooks/Bibi" }, …]`
     * where `type` is one of `directory`, `file`, `stream`, `podcast`.
     *
     * **`relpath` is the one to keep.** `path` is absolute on the box's filesystem,
     * while `play_folder` and `play_single` expect a path relative to the music
     * library root — which is what `relpath` holds.
     */
    fun folderContent(
        boxId: String,
        parentPath: String,
        result: JsonElement?,
        cachedAt: Long,
    ): FolderContent {
        val entries = (result as? JsonArray).orEmpty()
        val folders = mutableListOf<LibraryFolder>()
        val tracks = mutableListOf<LibraryTrack>()

        for (element in entries) {
            val entry = element as? JsonObject ?: continue
            val name = entry.string("name") ?: continue
            val path = entry.string("relpath") ?: entry.string("path") ?: continue

            when (entry.string("type")) {
                "directory" -> folders += LibraryFolder(
                    boxId = boxId,
                    path = path,
                    parentPath = parentPath,
                    displayName = name,
                    // Whether a subfolder has children of its own is only knowable by
                    // descending, and descending eagerly is exactly what this design
                    // avoids. Folders are always openable, so nothing reads this.
                    hasChildren = false,
                    cachedAt = cachedAt,
                )

                // file, stream and podcast are all playable by URL.
                else -> tracks += LibraryTrack(
                    boxId = boxId,
                    url = path,
                    parentPath = parentPath,
                    // Names come from the box and are shown verbatim: content is user
                    // data and is never rewritten or re-cased (§12.4).
                    title = name,
                    artist = null,
                    album = null,
                )
            }
        }

        return FolderContent(
            path = parentPath,
            folders = folders,
            tracks = tracks,
            cachedAt = cachedAt,
        )
    }

    /**
     * `list_albums` is MPD's `list album group albumartist`, so each entry carries one
     * album artist and either a single album or an array of them.
     */
    fun albums(boxId: String, result: JsonElement?, cachedAt: Long): List<LibraryAlbum> {
        val entries = (result as? JsonArray).orEmpty()
        val albums = mutableListOf<LibraryAlbum>()

        for (element in entries) {
            when (element) {
                is JsonObject -> {
                    val artist = element.string("albumartist")
                        ?: element.string("artist")
                        ?: ""
                    element["album"].asStringList()
                        .filter { it.isNotBlank() }
                        .forEach { album ->
                            albums += LibraryAlbum(boxId, artist, album, cachedAt = cachedAt)
                        }
                }

                // Ungrouped fallback: a plain list of album names.
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { album ->
                    albums += LibraryAlbum(boxId, "", album, cachedAt = cachedAt)
                }

                else -> Unit
            }
        }

        return albums.distinctBy { it.albumArtist to it.album }
    }

    /**
     * `get_single_coverart` and `get_album_coverart` return a bare filename in the
     * box's cover cache, or nothing when there is no artwork. The image itself is
     * fetched over HTTP from the box's web server, never over ZMQ (§5).
     */
    fun coverFile(result: JsonElement?): String? =
        (result as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() && it != "null" && it != "None" }
            ?.substringAfterLast('/')

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
