package app.coilforphoniebox.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerStatusTest {

    @Test
    fun `the title tag wins when the box sends one`() {
        val status = PlayerStatus(
            title = "Kapitel 3",
            file = "Petronella/03 - Kapitel 3.mp3",
        )
        assertEquals("Kapitel 3", status.displayTitle)
    }

    /**
     * The case that put "Coil is running" on the lock screen: most of a Phoniebox library is
     * untagged rips, and a null title reaches media3 as a notification with no content title,
     * which the platform fills in with its own text.
     */
    @Test
    fun `an untagged file falls back to its name`() {
        val status = PlayerStatus(file = "Petronella/03 - Kapitel 3.mp3")
        assertEquals("03 - Kapitel 3.mp3", status.displayTitle)
    }

    /** A blank tag is the same as no tag; the parsers map both to null, but not every source does. */
    @Test
    fun `a blank title falls back too`() {
        val status = PlayerStatus(title = "   ", file = "Petronella/03 - Kapitel 3.mp3")
        assertEquals("03 - Kapitel 3.mp3", status.displayTitle)
    }

    /** A file at the library root has no folder to strip off. */
    @Test
    fun `a file with no folder is its own name`() {
        assertEquals("kapitel.mp3", PlayerStatus(file = "kapitel.mp3").displayTitle)
    }

    /** A stream has no file name, so there is nothing to fall back to but the tag. */
    @Test
    fun `a trailing slash leaves nothing to fall back to`() {
        assertNull(PlayerStatus(file = "http://stream.example/").displayTitle)
    }

    /** An idle box names nothing, which is what every caller's own placeholder is for. */
    @Test
    fun `an idle box has no title`() {
        assertNull(PlayerStatus.Idle.displayTitle)
    }
}
