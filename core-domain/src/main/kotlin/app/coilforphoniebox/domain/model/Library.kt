package app.coilforphoniebox.domain.model

/**
 * One directory in the box's music library.
 *
 * [path] is relative to the library root and is exactly what `play_folder` takes,
 * which is why a favourite needs no cache lookup to start playing (§6.5).
 */
data class LibraryFolder(
    val boxId: String,
    val path: String,
    val parentPath: String?,
    val displayName: String,
    val hasChildren: Boolean = false,
    val cachedAt: Long = 0L,
)

data class LibraryTrack(
    val boxId: String,
    /** MPD URL, unique per box. */
    val url: String,
    val parentPath: String?,
    val title: String?,
    val artist: String?,
    val album: String?,
    val trackNo: Int? = null,
    val durationSeconds: Double? = null,
) {
    /** Falls back to the file name so a track with no tags still shows something. */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')
}

/**
 * Which backend on the box an item came from.
 *
 * Deliberately a plain string rather than an enum: a box registers its backends by name at
 * startup, so the set is open — a Phoniebox could gain a third one Coil has never heard of,
 * and the right thing to do with an unknown name is to hand it back to the box unchanged
 * rather than fail to model it. [MPD] is the only one Coil can assume, because it is the
 * default backend and the only one a box before the provider-neutral player had at all.
 */
object LibraryProvider {
    const val MPD = "mpd"
}

/**
 * What kind of thing a library entry is.
 *
 * A provider-neutral box returns playlists and saved-track collections through the same call
 * as albums, so "album" is no longer the only answer. Open for the same reason as
 * [LibraryProvider], and [ALBUM] is what an entry that does not say is taken to be.
 */
object LibraryContentType {
    const val ALBUM = "album"
    const val PLAYLIST = "playlist"
    const val COLLECTION = "collection"
    const val TRACK = "track"
}

/**
 * One entry in the albums list — which, on a box with more than one backend, is not always
 * an album: see [contentType].
 *
 * **Identity is all four of [boxId], [albumArtist], [album] and [contentUri]**, not the
 * artist and album alone. Once a box has two backends the same pair can name two different
 * things, and telling them apart is the difference between playing the right one and
 * clearing the queue to play nothing.
 */
data class LibraryAlbum(
    val boxId: String,
    val albumArtist: String,
    val album: String,
    val coverFile: String? = null,
    val cachedAt: Long = 0L,
    val provider: String = LibraryProvider.MPD,
    /**
     * The backend's own handle for this entry — `spotify:album:…` and the like. Null for
     * anything MPD holds, which is addressed by artist and album instead, and null for every
     * entry from a box predating the provider-neutral player.
     */
    val contentUri: String? = null,
    val contentType: String = LibraryContentType.ALBUM,
) {
    /**
     * The play target for this entry, carrying the provider and content URI with it.
     *
     * Use this rather than building a [PlayTarget.Album] from the artist and album by hand:
     * dropping the other two is exactly what makes a Spotify album clear the queue and play
     * nothing.
     */
    fun toPlayTarget() = PlayTarget.Album(
        albumArtist = albumArtist,
        album = album,
        provider = provider,
        contentUri = contentUri,
    )
}

/**
 * What a search over the cached library found.
 *
 * The three kinds stay apart rather than being merged into one ranked list: a folder is what
 * a listener usually wants to start, and burying it among fifty track hits would be worse
 * than useless.
 */
data class LibrarySearchResults(
    val query: String = "",
    val folders: List<LibraryFolder> = emptyList(),
    val albums: List<LibraryAlbum> = emptyList(),
    val tracks: List<LibraryTrack> = emptyList(),
) {
    val isEmpty: Boolean get() = folders.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

/** Progress of the opt-in library crawl, for the settings screen. */
data class LibraryIndexState(
    val running: Boolean = false,
    val foldersScanned: Int = 0,
)

/** How a crawl ended. Every outcome is something the user is told about. */
sealed interface LibraryIndexResult {
    /** [stoppedAtCap] means the library is larger than the crawl's own limit. */
    data class Finished(val foldersScanned: Int, val stoppedAtCap: Boolean) : LibraryIndexResult

    /**
     * The box is playing. A crawl occupies the RPC socket its card reader shares, so it
     * waits rather than making cards slow to register mid-story (§6).
     */
    data object BoxBusy : LibraryIndexResult

    /** The box stopped answering; whatever was scanned before that is kept. */
    data class Interrupted(val foldersScanned: Int) : LibraryIndexResult
}

/**
 * A single level of the folder tree, cached on first visit. The whole library is
 * never loaded at once — `list_all_dirs` stays off limits (§5).
 */
data class FolderContent(
    val path: String,
    val folders: List<LibraryFolder> = emptyList(),
    val tracks: List<LibraryTrack> = emptyList(),
    /** Null while this level has never been fetched. */
    val cachedAt: Long? = null,
) {
    val isEmpty: Boolean get() = folders.isEmpty() && tracks.isEmpty()

    companion object {
        /** Path of the library root; the box takes an empty string for it. */
        const val ROOT = ""
    }
}
