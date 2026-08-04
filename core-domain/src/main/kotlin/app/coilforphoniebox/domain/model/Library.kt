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
