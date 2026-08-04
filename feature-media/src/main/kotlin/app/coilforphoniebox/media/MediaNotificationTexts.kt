package app.coilforphoniebox.media

import androidx.annotation.DrawableRes

/**
 * Text and icons the media notification needs, supplied by the app module.
 *
 * The indirection exists so that every translatable string in the project stays in one
 * `res/values/strings.xml`: translations arrive as pull requests against that one file
 * per locale, and a second string file in a library module would be exactly the kind of
 * drift §12.5 is trying to avoid.
 *
 * Values are read on each call rather than cached, so a locale change is picked up
 * without restarting the service.
 */
interface MediaNotificationTexts {
    val playbackChannelName: String
    val playbackChannelDescription: String
    val statusChannelName: String
    val statusChannelDescription: String

    val readyTitle: String

    /** "Connected to <box>" — takes the box's display name. */
    fun connectedTo(boxName: String): String

    @get:DrawableRes
    val smallIcon: Int
}
