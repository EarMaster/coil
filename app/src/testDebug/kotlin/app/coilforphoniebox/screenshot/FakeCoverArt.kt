package app.coilforphoniebox.screenshot

import android.content.res.Resources
import android.graphics.drawable.Drawable
import app.coilforphoniebox.ui.components.FallbackCoverArt

/**
 * Stand-in for the box's HTTP cover cache, which is not reachable from CI.
 *
 * It hands back one of the app's own shipped covers rather than artwork drawn for the tests.
 * There is no longer a reason to keep a second set: the app ships thirty-two real pictures,
 * they are already on the classpath, and a fake that looks like the product is a better
 * picture to put in front of a reviewer — and into the Play Store screenshots, which come
 * through this same harness.
 *
 * **What this costs, and it is worth knowing.** The previous fake drew a 3:2 landscape source
 * with a circle in it, so a golden could fail on a stretched image, a `Fit` where `Crop` was
 * meant, or a cover drawn off-centre. The shipped covers are square, which is also the shape
 * of every cover slot in the app, so those particular mistakes are no longer visible in the
 * picture. What the goldens still catch is everything they catch by being byte comparisons:
 * a cover that stops rendering, renders unclipped, or gets replaced by the stand-in artwork
 * changes the file and fails the test — it just takes reading the diff rather than glancing
 * at it to see what went wrong.
 *
 * The choice is a pure function of the URL, which is the one property a golden cannot do
 * without: `String.hashCode` is specified by the JLS, so the same URL is the same picture on
 * every host and every run.
 *
 * Two fixtures can still land on the same cover — one pair in thirty-two, rather than one in
 * five as when this was a palette — and it matters only where they sit side by side in a grid
 * meant to show that covers differ. Check a new fixture's slot rather than assume:
 *
 * ```
 * Math.floorMod(("http://<host>/cover-cache/" + fileName).hashCode(), 32)
 * ```
 *
 * A tile can also draw the *same* cover the app would have stood in for it, since both pick
 * from one set. That is a readability problem in a diff, never a correctness one: the golden
 * is recorded from a known-good run, so a regression still changes the bytes.
 */
object FakeCoverArt {

    fun drawable(resources: Resources, url: String): Drawable =
        requireNotNull(resources.getDrawable(FallbackCoverArt.coverAt(url.hashCode()), null)) {
            "No stand-in cover for $url"
        }
}
