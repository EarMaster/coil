package app.coilforphoniebox.ui.components

import android.content.Context
import android.content.Intent

/**
 * Hands a `coil://` link to the share sheet. [label] rides along as the sheet's preview title,
 * so the target says what the link is for rather than showing a bare URI — it is a name the
 * user gave a favourite or a box, and goes out as it is (§12.4).
 *
 * A launcher with no app able to take plain text is possible, if unlikely; the link is on the
 * clipboard route in that case, so this stays silent rather than raising an error.
 *
 * Shared between the favourites screen, which hands out `coil://play` links, and settings,
 * which hands out `coil://open` ones.
 */
fun Context.shareLink(link: String, label: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
        putExtra(Intent.EXTRA_TITLE, label)
    }
    runCatching { startActivity(Intent.createChooser(send, null)) }
}
