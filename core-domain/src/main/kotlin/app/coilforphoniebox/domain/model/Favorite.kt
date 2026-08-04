package app.coilforphoniebox.domain.model

enum class FavoriteType { FOLDER, ALBUM }

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
    val coverFile: String? = null,
    val sortIndex: Int = 0,
    val launchCount: Int = 0,
    val shortcutPinned: Boolean = false,
) {
    /**
     * Returns null for a row that cannot be played — a FOLDER without a path or an
     * ALBUM without its two keys — rather than throwing on data that reached the
     * database through an import.
     */
    fun toPlayTarget(): PlayTarget? = when (type) {
        FavoriteType.FOLDER -> folder?.let { PlayTarget.Folder(it) }
        FavoriteType.ALBUM ->
            if (albumArtist != null && album != null) PlayTarget.Album(albumArtist, album) else null
    }

    companion object {
        fun of(boxId: String, label: String, target: PlayTarget, coverFile: String? = null): Favorite? =
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

                // A single track is playable but deliberately not favouritable:
                // one tap should start something worth listening to, not one file.
                is PlayTarget.Track -> null
            }
    }
}
