package app.coilforphoniebox.shortcuts

import android.content.Intent
import android.net.Uri
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FavoriteType
import app.coilforphoniebox.domain.model.PlayTarget

/**
 * `coil://play?box=<boxId>&type=folder&path=…`, `…&type=album&albumartist=…&album=…`
 * or `…&type=track&url=…`
 *
 * The box id travels in the link because a home screen shortcut has to start the box it
 * was created for, whatever is currently active (§7.3).
 */
object PlayDeepLink {

    const val SCHEME = "coil"
    const val HOST = "play"

    private const val PARAM_BOX = "box"
    private const val PARAM_TYPE = "type"
    private const val PARAM_PATH = "path"
    private const val PARAM_ALBUM_ARTIST = "albumartist"
    private const val PARAM_ALBUM = "album"
    private const val PARAM_URL = "url"
    private const val PARAM_FAVORITE = "favorite"

    private const val TYPE_FOLDER = "folder"
    private const val TYPE_ALBUM = "album"
    private const val TYPE_TRACK = "track"

    data class Request(
        val boxId: String,
        val target: PlayTarget,
        /** Present for shortcuts, so the launch can be counted. */
        val favoriteId: Long?,
    )

    fun uriFor(favorite: Favorite): Uri? {
        val builder = Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .appendQueryParameter(PARAM_BOX, favorite.boxId)
            .appendQueryParameter(PARAM_FAVORITE, favorite.id.toString())

        when (favorite.type) {
            FavoriteType.FOLDER -> builder
                .appendQueryParameter(PARAM_TYPE, TYPE_FOLDER)
                .appendQueryParameter(PARAM_PATH, favorite.folder ?: return null)

            FavoriteType.ALBUM -> builder
                .appendQueryParameter(PARAM_TYPE, TYPE_ALBUM)
                .appendQueryParameter(PARAM_ALBUM_ARTIST, favorite.albumArtist ?: return null)
                .appendQueryParameter(PARAM_ALBUM, favorite.album ?: return null)

            FavoriteType.TRACK -> builder
                .appendQueryParameter(PARAM_TYPE, TYPE_TRACK)
                .appendQueryParameter(PARAM_URL, favorite.trackUrl ?: return null)
        }
        return builder.build()
    }

    /**
     * An implicit VIEW intent limited to this package: a library module cannot name the
     * activity class in the app module, and restricting the package keeps the link from
     * being answerable by anything else.
     */
    fun intentFor(favorite: Favorite, packageName: String): Intent? =
        uriFor(favorite)?.let { uri ->
            Intent(Intent.ACTION_VIEW, uri).setPackage(packageName)
        }

    fun parse(uri: Uri?): Request? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        val boxId = uri.getQueryParameter(PARAM_BOX)?.takeIf { it.isNotBlank() } ?: return null
        val favoriteId = uri.getQueryParameter(PARAM_FAVORITE)?.toLongOrNull()

        val target = when (uri.getQueryParameter(PARAM_TYPE)) {
            TYPE_FOLDER -> uri.getQueryParameter(PARAM_PATH)?.let { PlayTarget.Folder(it) }
            TYPE_ALBUM -> {
                val artist = uri.getQueryParameter(PARAM_ALBUM_ARTIST)
                val album = uri.getQueryParameter(PARAM_ALBUM)
                if (artist != null && album != null) PlayTarget.Album(artist, album) else null
            }

            TYPE_TRACK -> uri.getQueryParameter(PARAM_URL)?.let { PlayTarget.Track(it) }

            else -> null
        } ?: return null

        return Request(boxId = boxId, target = target, favoriteId = favoriteId)
    }
}
