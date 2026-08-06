package app.coilforphoniebox.screenshot

import app.coilforphoniebox.ui.components.FallbackCoverArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one non-screenshot test in this module, and it earns the exception.
 *
 * `FallbackCoverArt` assigns artwork by hashing a name, and that assignment is *frozen*: a
 * library in the field has covers its owner has come to recognise, and any change to the hash,
 * the folding or the size of the set silently reshuffles every one of them. Nothing else here
 * would notice — a golden only shows the fixtures' own names, and they would simply re-record
 * to whatever the new mapping produced. So the mapping is pinned to values instead.
 *
 * These numbers are not derived from anything. If one of them fails, the fix is to restore the
 * behaviour, not to update the number — unless a reshuffle is being made deliberately, in which
 * case it belongs in a release note.
 */
class FallbackCoverArtTest {

    /** Names from the screenshot fixtures, plus the two that pin the fold. */
    @Test
    fun `assignment is frozen`() {
        val expected = mapOf(
            "Bedtime Stories" to 16,
            "Fairy Tales" to 15,
            "Nursery Rhymes" to 29,
            "Ten in the Bed" to 15,
            "Songs for the Long Drive Home" to 0,
            "The Missing Key" to 11,
            "Ein Bär räumt auf" to 31,
        )

        expected.forEach { (name, index) ->
            assertEquals("cover for \"$name\"", index, FallbackCoverArt.coverIndexFor(name))
        }
    }

    /**
     * Case and accents are folded away, so a folder renamed `BÄRENSTARK` keeps its picture —
     * and so do the two spellings of a name that a box and a favourite may disagree on.
     */
    @Test
    fun `folding ignores case, accents and surrounding space`() {
        val reference = FallbackCoverArt.coverIndexFor("Ein Bär räumt auf")

        listOf("ein bär räumt auf", "EIN BÄR RÄUMT AUF", "  Ein Bär räumt auf  ", "Ein Bar raumt auf")
            .forEach { variant ->
                assertEquals("\"$variant\"", reference, FallbackCoverArt.coverIndexFor(variant))
            }
    }

    /** A missing label is not a thing to give a picture to — every one of them would share it. */
    @Test
    fun `nothing to key on gets no cover`() {
        assertNull(FallbackCoverArt.coverIndexFor(null))
        assertNull(FallbackCoverArt.coverIndexFor(""))
        assertNull(FallbackCoverArt.coverIndexFor("   "))
    }

    /**
     * The reason the hash is FNV-1a and not `String.hashCode`.
     *
     * Taking `hashCode` modulo 32 keeps its low five bits, which for a run of sibling folders
     * move in lockstep with the last character: `Folge 01`…`Folge 40` come out as 14 distinct
     * covers in a visibly repeating pattern, which is exactly the shelf a Phoniebox library is
     * full of. This asserts the mixed hash does substantially better, without pinning a number
     * that says more about these forty strings than about the property being checked.
     */
    @Test
    fun `sibling names spread across the set`() {
        val slots = (1..40).map { FallbackCoverArt.coverIndexFor("Folge %02d".format(it)) }
        val distinct = slots.toSet().size

        assertTrue("only $distinct distinct covers for 40 sibling folders", distinct >= 24)
    }

    @Test
    fun `every index maps to a drawable`() {
        val ids = (0 until FallbackCoverArt.size).map { FallbackCoverArt.coverAt(it) }

        assertEquals("the set has duplicates", FallbackCoverArt.size, ids.toSet().size)
        assertTrue("indices do not wrap", ids.none { it == 0 })
    }
}
