package app.coilforphoniebox.domain.model

enum class FavoriteType { FOLDER, ALBUM, TRACK }

/**
 * Favourites are scoped per box on purpose (§7.2): a folder path that exists on one
 * box need not exist on another, and a favourite that silently fails is worse than
 * one that is simply not there.
 */
data class Favorite(
    val id: Long = 0L,
    val boxId: String,
    val label: String,
    val type: FavoriteType,
    val folder: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    /** MPD URL of a single file, for a TRACK favourite. */
    val trackUrl: String? = null,
    val coverFile: String? = null,
    val sortIndex: Int = 0,
    val launchCount: Int = 0,
    val shortcutPinned: Boolean = false,
) {
    /**
     * Returns null for a row that cannot be played — a FOLDER without a path, an
     * ALBUM without its two keys, a TRACK without its URL — rather than throwing on
     * data that reached the database through an import.
     */
    fun toPlayTarget(): PlayTarget? = when (type) {
        FavoriteType.FOLDER -> folder?.let { PlayTarget.Folder(it) }
        FavoriteType.ALBUM ->
            if (albumArtist != null && album != null) PlayTarget.Album(albumArtist, album) else null

        FavoriteType.TRACK -> trackUrl?.let { PlayTarget.Track(it) }
    }

    companion object {
        /** Every play target is favouritable, so this always produces a row. */
        fun of(boxId: String, label: String, target: PlayTarget, coverFile: String? = null): Favorite =
            when (target) {
                is PlayTarget.Folder -> Favorite(
                    boxId = boxId,
                    label = label,
                    type = FavoriteType.FOLDER,
                    folder = target.path,
                    coverFile = coverFile,
                )

                is PlayTarget.Album -> Favorite(
                    boxId = boxId,
                    label = label,
                    type = FavoriteType.ALBUM,
                    albumArtist = target.albumArtist,
                    album = target.album,
                    coverFile = coverFile,
                )

                // A single track is a favourite of its own kind. The player and the
                // library both offer folder *and* track explicitly, because from a
                // playing song alone it is not obvious which one a star would save.
                is PlayTarget.Track -> Favorite(
                    boxId = boxId,
                    label = label,
                    type = FavoriteType.TRACK,
                    trackUrl = target.url,
                    coverFile = coverFile,
                )
            }
    }
}
