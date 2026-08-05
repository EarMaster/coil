package app.coilforphoniebox.screenshot

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Synthetic album art for the screenshot tests.
 *
 * A flat colour used to stand in for every cover, which made the goldens blind to most of the
 * ways cover display actually goes wrong: a stretched image, a `Fit` where `Crop` was meant, a
 * missing rounded-corner clip, or the wrong cover against the wrong title all look identical
 * when the picture is one rectangle of one colour. Each of those now shows up:
 *
 * - **The source is 3:2 landscape, not square.** Every cover in the app is drawn into a square
 *   or fixed box with `ContentScale.Crop`, so a non-square source is what proves the crop is
 *   happening at all — `Fit` would letterbox and leave background showing.
 * - **A circle is the aspect detector.** Scaled correctly it stays round; stretched to fill a
 *   square it becomes a visible ellipse. It sits inside the middle third so a centre crop keeps
 *   it whole.
 * - **The wedge and the base stripe are asymmetric**, so a mirrored, rotated or vertically
 *   offset draw is not a picture that happens to look the same.
 * - **The colour comes from the URL**, so two covers are two colours and a cover shown against
 *   the wrong title is visible rather than plausible.
 *
 * Everything here is a pure function of the URL — `String.hashCode` is specified by the JLS, so
 * the same URL is the same picture on every host and every run, which is the one property a
 * golden cannot do without.
 */
object FakeCoverArt {

    /** 3:2, deliberately — see the note above about proving the crop. */
    private const val WIDTH = 360
    private const val HEIGHT = 240

    /**
     * Muted and brand-adjacent rather than loud: these reach the Play Store screenshots through
     * the same harness, where a page of primary colours would read as test scaffolding.
     *
     * Five slots means two fixtures can land on the same colour, which matters only where they
     * end up side by side — a grid of covers that is meant to show they differ. When adding a
     * fixture cover, check its slot rather than assume:
     *
     * ```
     * Math.floorMod(("http://<host>/cover-cache/" + fileName).hashCode(), 5)
     * ```
     */
    private val BASE_COLORS = intArrayOf(
        0xFF3B5A46.toInt(), // deep green — the colour every cover used to be
        0xFF4A5570.toInt(), // slate blue
        0xFF6B4A3C.toInt(), // warm brown
        0xFF4F4A63.toInt(), // muted violet
        0xFF3F5860.toInt(), // teal
    )

    fun drawable(resources: Resources, url: String): Drawable =
        BitmapDrawable(resources, bitmap(url))

    private fun bitmap(url: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val base = BASE_COLORS[(url.hashCode().mod(BASE_COLORS.size))]
        val paint = Paint().apply { isAntiAlias = true }

        canvas.drawColor(base)

        // A wedge out of the bottom-left corner: orientation, and something for a wrong crop
        // anchor to move.
        paint.color = shift(base, 1.35f)
        canvas.drawPath(
            Path().apply {
                moveTo(0f, HEIGHT.toFloat())
                lineTo(WIDTH * 0.55f, HEIGHT.toFloat())
                lineTo(0f, HEIGHT * 0.25f)
                close()
            },
            paint,
        )

        // Inside the middle third, so a centre crop keeps it whole and its roundness is the
        // thing a stretched image loses.
        paint.color = shift(base, 1.85f)
        canvas.drawCircle(WIDTH * 0.52f, HEIGHT * 0.42f, HEIGHT * 0.24f, paint)

        // Full-width stripe along the bottom: survives the crop, so a vertical offset shows as
        // a stripe that is too thick, too thin or gone.
        paint.color = shift(base, 0.6f)
        canvas.drawRect(0f, HEIGHT * 0.88f, WIDTH.toFloat(), HEIGHT.toFloat(), paint)

        return bitmap
    }

    /** Lightens ([factor] above 1) or darkens the channels of [color], staying in range. */
    private fun shift(color: Int, factor: Float): Int {
        fun channel(shift: Int): Int =
            (((color shr shift) and 0xFF) * factor).toInt().coerceIn(0, 0xFF)

        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
