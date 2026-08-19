package app.coilforphoniebox.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(
            "http://phoniebox.local/cover-cache/a1.jpg",
            box.coverUrl("a1.jpg", allowExternal = false),
        )
        assertEquals(
            "http://phoniebox.local/cover-cache/a1.jpg",
            box.coverUrl("/a1.jpg", allowExternal = false),
        )
    }

    /**
     * A cache name is the box's own artwork, so the setting has nothing to say about it —
     * turning external covers on must not change where a local cover is loaded from.
     */
    @Test
    fun `a cache name is unaffected by the external setting`() {
        assertEquals(
            "http://phoniebox.local/cover-cache/a1.jpg",
            box.coverUrl("a1.jpg", allowExternal = true),
        )
    }

    /**
     * An absolute URL belongs to whichever backend served the content — Spotify answers with
     * one. Appending it to the box's address would 404, so it is passed through whole.
     */
    @Test
    fun `an absolute url is passed through when external covers are allowed`() {
        assertEquals(
            "https://i.scdn.co/image/ab67616d",
            box.coverUrl("https://i.scdn.co/image/ab67616d", allowExternal = true),
        )
        assertEquals(
            "http://cdn.example/art.jpg",
            box.coverUrl("http://cdn.example/art.jpg", allowExternal = true),
        )
    }

    /**
     * The LAN-only promise (§16): with the setting off, an external cover is not loaded at
     * all rather than being loaded from the wrong place.
     */
    @Test
    fun `an absolute url is refused when external covers are not allowed`() {
        assertNull(box.coverUrl("https://i.scdn.co/image/ab67616d", allowExternal = false))
    }

    /** Scheme matching is case-insensitive, so `HTTPS://` cannot smuggle a URL through. */
    @Test
    fun `external detection ignores scheme case`() {
        assertNull(box.coverUrl("HTTPS://i.scdn.co/image/ab67616d", allowExternal = false))
    }
}
