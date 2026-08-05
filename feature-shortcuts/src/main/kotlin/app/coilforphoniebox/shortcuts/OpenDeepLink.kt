package app.coilforphoniebox.shortcuts

import android.content.Intent
import android.net.Uri

/**
 * `coil://open` — open the app, and `coil://open?box=<boxId>` — open it showing that box.
 *
 * The counterpart to [PlayDeepLink], and deliberately a separate host rather than a `play`
 * link with the target left off: the two do different things and are worth being able to tell
 * apart at a glance, in a note or an automation app. A `play` link reaches the box; this one
 * does not touch it at all.
 *
 * That makes this the *less* privileged of the two. All an outside caller can ask for here is
 * the thing tapping the launcher icon already does, plus which of the user's own boxes the app
 * comes up on. It sends no command, so it cannot start, stop or change what is playing —
 * which is the line the RPC surface observes as well (§16).
 *
 * [boxId] is optional, unlike in a `play` link where it is required. A folder path means
 * nothing without the box it came from, so `play` cannot do without one; "just open the app"
 * is a perfectly complete request on its own.
 */
object OpenDeepLink {

    const val SCHEME = "coil"
    const val HOST = "open"

    private const val PARAM_BOX = "box"

    /** [boxId] is null when the link names no box, which means "leave the active box alone". */
    data class Request(val boxId: String?)

    fun uriFor(boxId: String? = null): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .apply { boxId?.takeIf { it.isNotBlank() }?.let { appendQueryParameter(PARAM_BOX, it) } }
        .build()

    /**
     * An implicit VIEW intent limited to this package, for the same reason as
     * [PlayDeepLink.intentFor]: a library module cannot name an activity in the app module, and
     * restricting the package keeps the link from being answerable by anything else.
     */
    fun intentFor(packageName: String, boxId: String? = null): Intent =
        Intent(Intent.ACTION_VIEW, uriFor(boxId)).setPackage(packageName)

    /**
     * Returns null for anything that is not one of these links, so a caller can hand it every
     * intent it receives. A blank `box` is treated as absent rather than as an error: a link
     * built by hand with an empty parameter still means "open the app".
     */
    fun parse(uri: Uri?): Request? {
        if (uri == null || uri.scheme != SCHEME || uri.host != HOST) return null
        return Request(boxId = uri.getQueryParameter(PARAM_BOX)?.takeIf { it.isNotBlank() })
    }
}
