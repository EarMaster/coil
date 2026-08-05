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

data class LibraryAlbum(
    val boxId: String,
    val albumArtist: String,
    val album: String,
    val coverFile: String? = null,
    val cachedAt: Long = 0L,
)

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
