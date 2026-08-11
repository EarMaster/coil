package app.coilforphoniebox.media

import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The queue and `playerstatus` arrive independently, so every disagreement between them has to
 * end up on the safe side of `SimpleBasePlayer`, which throws when the current index falls
 * outside the playlist.
 */
class TimelineIndexTest {

    private fun queue(vararg urls: String) = urls.mapIndexed { index, url ->
        QueueEntry(position = index, url = url, title = url.substringAfterLast('/'))
    }

    private fun status(file: String?, position: Int?) =
        PlayerStatus(file = file, playlistPosition = position, playlistLength = 3)

    @Test
    fun `a queue that matches the status gives its position`() {
        val index = timelineIndexFor(
            queue("A/01.mp3", "A/02.mp3", "A/03.mp3"),
            status("A/02.mp3", 1),
        )

        assertEquals(1, index)
    }

    /** The crash case: the box moved to a longer album before the refetch landed. */
    @Test
    fun `a position past the end of the cached queue is refused`() {
        assertNull(timelineIndexFor(queue("A/01.mp3"), status("B/07.mp3", 6)))
    }

    /**
     * The quiet case, and the more insidious one: the index exists but describes a track from
     * the album that was playing a moment ago.
     */
    @Test
    fun `a queue of the right length but the wrong album is refused`() {
        assertNull(
            timelineIndexFor(
                queue("A/01.mp3", "A/02.mp3", "A/03.mp3"),
                status("B/02.mp3", 1),
            ),
        )
    }

    @Test
    fun `an empty queue is refused whatever the status says`() {
        assertNull(timelineIndexFor(emptyList(), status("A/01.mp3", 0)))
        assertNull(timelineIndexFor(emptyList(), status(null, null)))
    }

    /** A stopped box, or one whose status has not arrived, reports no position. */
    @Test
    fun `no reported position is refused`() {
        assertNull(timelineIndexFor(queue("A/01.mp3", "A/02.mp3"), status("A/01.mp3", null)))
    }

    @Test
    fun `a negative position is refused rather than clamped`() {
        assertNull(timelineIndexFor(queue("A/01.mp3", "A/02.mp3"), status("A/01.mp3", -1)))
    }
}
