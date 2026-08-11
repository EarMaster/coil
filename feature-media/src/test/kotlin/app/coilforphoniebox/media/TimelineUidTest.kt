package app.coilforphoniebox.media

import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * media3 identifies timeline items by UID: it diffs them position by position to tell a track
 * change from a new playlist, and throws `"Duplicate MediaItemData UID in playlist"` out of
 * `getState()` if one repeats. Both of those used to be reachable, because the playing item took
 * its identity from `playerstatus` while its neighbours took theirs from the queue.
 */
class TimelineUidTest {

    private fun entry(position: Int, url: String, songId: String? = null) =
        QueueEntry(position = position, url = url, title = url, songId = songId)

    @Test
    fun `a queue id identifies the row`() {
        assertEquals("42", uidFor(entry(0, "A/01.mp3", songId = "42")))
    }

    /** MPD did not send ids, so position has to separate two copies of one file. */
    @Test
    fun `without a queue id the position separates identical files`() {
        val first = uidFor(entry(0, "A/01.mp3"))
        val second = uidFor(entry(1, "A/01.mp3"))

        assertNotEquals(first, second)
    }

    /**
     * The crash. The same file twice, and a status whose `songid` belongs to the *other* copy:
     * building the playing item's uid from the status would repeat the id already used one row
     * up. Taking it from the row it actually occupies cannot collide, whatever the status says.
     */
    @Test
    fun `the playing row keeps its own identity whatever the status reports`() {
        val queue = listOf(
            entry(0, "A/01.mp3", songId = "7"),
            entry(1, "A/01.mp3", songId = "8"),
        )
        val status = PlayerStatus(file = "A/01.mp3", playlistPosition = 1, songId = "7")

        val index = timelineIndexFor(queue, status)
        assertEquals(1, index)

        val uids = queue.map { uidFor(it) }
        assertEquals(uids.size, uids.toSet().size)
        // The row's id, not the status's — which is the id of the copy above it.
        assertEquals("8", uidFor(queue[index!!]))
    }

    @Test
    fun `the single item timeline falls back through what the status has`() {
        assertEquals("9", fallbackUid(PlayerStatus(file = "A/01.mp3", songId = "9")))
        assertEquals("A/01.mp3", fallbackUid(PlayerStatus(file = "A/01.mp3")))
        assertEquals(CURRENT_ITEM_UID, fallbackUid(PlayerStatus()))
    }
}
