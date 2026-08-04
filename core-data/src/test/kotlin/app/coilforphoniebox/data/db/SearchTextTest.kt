package app.coilforphoniebox.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {

    /** The whole point: German, French and Spanish names must not need exact case or accents. */
    @Test
    fun `folding ignores case and accents`() {
        assertEquals("bar", SearchText.fold("Bär"))
        assertEquals("bar", SearchText.fold("BÄR"))
        assertEquals("elephant", SearchText.fold("Éléphant"))
        assertEquals("nino", SearchText.fold("Niño"))
    }

    @Test
    fun `a haystack joins the parts it was given and drops the rest`() {
        val haystack = SearchText.haystack("Kapitel 1", null, "  ", "Käpt'n Blaubär")

        assertEquals("kapitel 1 kapt'n blaubar", haystack)
    }

    @Test
    fun `a query matches its row through the folded haystack`() {
        val haystack = SearchText.haystack("Käpt'n Blaubär")
        val pattern = SearchText.pattern("blaub")

        // What SQLite will do with the pattern, minus the wildcards.
        assertTrue(haystack.contains(pattern.trim('%')))
    }

    /**
     * A user typing `100%` or `a_b` is searching for that text. Unescaped, the first would
     * match every row in the library.
     */
    @Test
    fun `wildcards in a query are escaped`() {
        assertEquals("%100\\%%", SearchText.pattern("100%"))
        assertEquals("%a\\_b%", SearchText.pattern("a_b"))
        assertEquals("%c\\\\d%", SearchText.pattern("c\\d"))
    }
}
