package app.coilforphoniebox.ui.components

import androidx.annotation.DrawableRes
import app.coilforphoniebox.R
import java.text.Normalizer
import java.util.Locale

/**
 * Stand-in artwork for content the box has no cover for.
 *
 * A Phoniebox library is largely ripped CDs and home-made folders, so a great deal of it has
 * no embedded artwork at all — and a screen of identical grey placeholder icons is both
 * unattractive and useless to the child this app is pointed at, who navigates by picture
 * rather than by reading. Thirty-two abstract covers ship with the app and one is assigned to
 * every uncovered item, so a folder looks the same every time it is opened, on every screen it
 * appears on, and after a reinstall.
 *
 * **The key is the name, not the path.** A folder contributes its own name (the last segment),
 * an album its title, a track its title — whatever the user reads beside the picture is what
 * chooses the picture. Two folders of the same name in different corners of the library
 * therefore share a cover, which is the intended reading of "the same name always gets the same
 * art" rather than a collision to design around.
 *
 * **[coverIndexFor] is frozen.** Change the hash, the folding or the number of images and every
 * library in the field reshuffles — covers users have come to recognise become other pictures.
 * Adding art means appending to [FALLBACK_COVERS] is *not* enough either: the modulus moves.
 * If the set ever grows, it has to grow by a scheme that keeps the first 32 buckets where they
 * are, or be accepted as a one-off reshuffle in a release note.
 */
object FallbackCoverArt {

    /**
     * The shipped set, in `res/drawable-nodpi` so the platform does not density-scale a
     * 512×512 source up to a launcher-icon's idea of xxhdpi.
     */
    private val FALLBACK_COVERS = intArrayOf(
        R.drawable.cover00, R.drawable.cover01, R.drawable.cover02, R.drawable.cover03,
        R.drawable.cover04, R.drawable.cover05, R.drawable.cover06, R.drawable.cover07,
        R.drawable.cover08, R.drawable.cover09, R.drawable.cover10, R.drawable.cover11,
        R.drawable.cover12, R.drawable.cover13, R.drawable.cover14, R.drawable.cover15,
        R.drawable.cover16, R.drawable.cover17, R.drawable.cover18, R.drawable.cover19,
        R.drawable.cover20, R.drawable.cover21, R.drawable.cover22, R.drawable.cover23,
        R.drawable.cover24, R.drawable.cover25, R.drawable.cover26, R.drawable.cover27,
        R.drawable.cover28, R.drawable.cover29, R.drawable.cover30, R.drawable.cover31,
    )

    val size: Int get() = FALLBACK_COVERS.size

    /**
     * The [index]th cover, wrapping. Exists for the screenshot tests, which stand these same
     * images in for the box's HTTP cover cache rather than keeping a second set of artwork.
     */
    @DrawableRes
    fun coverAt(index: Int): Int = FALLBACK_COVERS[Math.floorMod(index, FALLBACK_COVERS.size)]

    /**
     * The cover for [name], or null when there is no name to key on — an empty string is not a
     * thing that deserves its own picture, it is a missing label, and one arbitrary cover
     * standing for every one of them would look like a bug.
     */
    @DrawableRes
    fun coverFor(name: String?): Int? {
        val index = coverIndexFor(name) ?: return null
        return FALLBACK_COVERS[index]
    }

    /** Split out from [coverFor] so a test can assert the distribution without a resource table. */
    fun coverIndexFor(name: String?): Int? {
        val key = fold(name ?: return null).ifBlank { return null }
        return Math.floorMod(hash(key), FALLBACK_COVERS.size)
    }

    /**
     * Case- and accent-insensitive, so `Bär`, `bar` and `BÄR` are one folder as far as the
     * artwork is concerned. Same reasoning as `SearchText` in `:core-data`, and deliberately a
     * separate copy: that one may gain rules to suit searching, and this one must never change
     * once artwork has been assigned by it.
     */
    private fun fold(name: String): String =
        Normalizer.normalize(name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")

    /**
     * FNV-1a, and not `String.hashCode`.
     *
     * `hashCode` is stable across hosts and versions — which is the property that matters here —
     * but it is not *mixed*: taking it modulo 32 keeps only its low five bits, and for the
     * strings a library is actually full of (`folge 01`, `folge 02`, `folge 03`) those bits move
     * in lockstep with the last character. A shelf of sibling folders would come out as a run of
     * two or three repeated pictures. FNV-1a costs the same and spreads.
     */
    private fun hash(key: String): Int {
        var hash = FNV_OFFSET_BASIS
        for (byte in key.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toInt() and 0xFF)) * FNV_PRIME
        }
        return hash
    }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    private const val FNV_OFFSET_BASIS = -2128831035 // 0x811C9DC5
    private const val FNV_PRIME = 16777619
}
