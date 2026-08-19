package app.coilforphoniebox.transport

import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.LibraryContentType
import app.coilforphoniebox.domain.model.LibraryProvider
import app.coilforphoniebox.domain.model.LibraryFolder
import app.coilforphoniebox.domain.model.LibraryTrack
import app.coilforphoniebox.domain.model.isExternalCoverRef
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
     *
     * A provider-neutral box adds `provider`, `content_uri` and `content_type` to every
     * entry — MPD's own included — and, once the box has more than one backend, returns all
     * of their catalogues concatenated. Those three fields are then the only thing telling
     * two entries apart, so they are read here and kept: **dropping them is what makes
     * `play_album` fall through to MPD, find nothing, and clear the queue.**
     *
     * A box without the provider-neutral player sends none of them, which reads as MPD, no
     * content URI, and an album — exactly what such a box only ever has.
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
                    val provider = element.string("provider")
                        ?.takeIf { it.isNotBlank() }
                        ?: LibraryProvider.MPD
                    val contentUri = element.string("content_uri")?.takeIf { it.isNotBlank() }
                    val contentType = element.string("content_type")
                        ?.takeIf { it.isNotBlank() }
                        ?: LibraryContentType.ALBUM

                    element["album"].asStringList()
                        .filter { it.isNotBlank() }
                        .forEach { album ->
                            albums += LibraryAlbum(
                                boxId = boxId,
                                albumArtist = artist,
                                album = album,
                                cachedAt = cachedAt,
                                provider = provider,
                                contentUri = contentUri,
                                contentType = contentType,
                            )
                        }
                }

                // Ungrouped fallback: a plain list of album names.
                is JsonPrimitive -> element.content.takeIf { it.isNotBlank() }?.let { album ->
                    albums += LibraryAlbum(boxId, "", album, cachedAt = cachedAt)
                }

                else -> Unit
            }
        }

        // Keyed on the content URI as well, so a record owned on CD *and* saved on a
        // streaming service stays two rows. Collapsing them would hide whichever the box
        // listed second, and the box lists its default backend first.
        return albums.distinctBy { Triple(it.albumArtist, it.album, it.contentUri) }
    }

    /** Outcome of a cover art request. */
    sealed interface CoverArt {
        /**
         * Where the artwork is: a file name in the box's cover cache, or an absolute URL
         * when the backend that owns the content serves its own artwork.
         *
         * Not "a file name" any more, which is why it is no longer called one —
         * [Box.coverUrl] is what decides which of the two this is and whether it may be
         * loaded at all.
         */
        data class Available(val coverRef: String) : CoverArt

        /**
         * The box has queued the extraction on its own worker thread and has nothing to
         * hand over yet. Ask again shortly — this is what the *first* request for any song
         * returns, so treating it as a file name means no cover ever appears.
         */
        data object Pending : CoverArt

        /** The box looked and there is no artwork for this song. */
        data object Missing : CoverArt
    }

    /**
     * `get_single_coverart` and `get_album_coverart` return a bare file name in the box's
     * cover cache. The image itself is fetched over HTTP from the box's web server, never
     * over ZMQ (§5).
     *
     * Two sentinel values matter, both from `coverart_cache_manager.py`: `CACHE_PENDING`
     * while extraction is queued, and an empty string for "no artwork".
     *
     * **A provider-neutral box can answer with an absolute URL instead**, when the backend
     * that owns the content serves its own artwork — a Spotify track answers with
     * `https://i.scdn.co/image/…`. Those are kept whole: taking the last path segment, which
     * is right for the box's own `some/dir/hash.jpg`, would reduce such a URL to its hash and
     * leave [Box.coverUrl] no way to tell it was ever external.
     */
    fun coverArt(result: JsonElement?): CoverArt {
        val value = (result as? JsonPrimitive)?.content?.trim()
            ?: return CoverArt.Missing

        return when {
            value == CACHE_PENDING -> CoverArt.Pending
            value.isBlank() || value == "null" || value == "None" -> CoverArt.Missing
            value.isExternalCoverRef() -> CoverArt.Available(value)
            else -> CoverArt.Available(value.substringAfterLast('/'))
        }
    }

    /** `CACHE_PENDING` in `coverart_cache_manager.py`. */
    private const val CACHE_PENDING = "CACHE_PENDING"

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
