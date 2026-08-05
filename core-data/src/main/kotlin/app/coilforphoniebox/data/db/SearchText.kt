package app.coilforphoniebox.data.db

import java.text.Normalizer
import java.util.Locale

/**
 * How library text is matched when searching.
 *
 * Everything is folded to lower case with accents removed, on the way *into* the database
 * and again for the query, so `bar`, `Bär` and `BÄR` all find each other. This cannot be
 * done in SQL: SQLite's `LIKE` and `lower()` are case-insensitive for ASCII only, which
 * would leave every umlaut in the German, French, Spanish and Dutch libraries unmatched
 * unless the user typed the exact case — and umlauts are everywhere in the audiobook names
 * this app exists to start.
 *
 * `Locale.ROOT` on purpose: the fold has to agree between the row that was written on one
 * day and the query typed on another, so it must not depend on the phone's current locale.
 *
 * Known limit: `ß` is not decomposed by NFD, so "Strasse" does not find "Straße". Adding
 * that means a list of language-specific pairs, which is a bigger decision than it looks.
 */
internal object SearchText {

    private val combiningMarks = "\\p{Mn}+".toRegex()

    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(combiningMarks, "")

    /** The folded haystack stored on a row; blank and missing parts drop out. */
    fun haystack(vararg parts: String?): String = parts
        .asSequence()
        .filterNotNull()
        .filter { it.isNotBlank() }
        .joinToString(separator = " ") { fold(it) }

    /**
     * A `LIKE` pattern for [query]. `%`, `_` and the escape character itself are neutralised,
     * so a user typing `100%` searches for that text instead of matching everything.
     */
    fun pattern(query: String): String {
        val escaped = fold(query)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }
}
