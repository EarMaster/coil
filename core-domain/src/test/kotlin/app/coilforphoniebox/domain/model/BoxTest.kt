package app.coilforphoniebox.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class BoxTest {

    private val box = Box(
        id = "box-1",
        displayName = "Living room",
        host = "phoniebox.local",
        addedAt = 0L,
    )

    @Test
    fun `defaults are the TCP ports, not the WebSocket ones`() {
        // JeroMQ has no ws:// transport, so 5556/5557 are never used.
        assertEquals(5555, box.rpcPort)
        assertEquals(5558, box.pubPort)
    }

    /** Cover art comes over HTTP from the box's web server, not over ZMQ. */
    @Test
    fun `cover urls point at the box's cover cache`() {
        assertEquals("http://phoniebox.local/cover-cache/a1.jpg", box.coverUrl("a1.jpg"))
        assertEquals("http://phoniebox.local/cover-cache/a1.jpg", box.coverUrl("/a1.jpg"))
    }
}
