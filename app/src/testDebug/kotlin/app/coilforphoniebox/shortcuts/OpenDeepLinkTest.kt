package app.coilforphoniebox.shortcuts

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `coil://open` parsing and building.
 *
 * Lives in `:app`'s `testDebug` source set rather than in `:feature-shortcuts` because
 * `android.net.Uri` needs Robolectric, and this is the module where Robolectric is already
 * configured — down to the SDK level it runs on. Moving it would mean a second copy of that
 * setup for one test class.
 */
@RunWith(AndroidJUnit4::class)
class OpenDeepLinkTest {

    @Test
    fun `parses a link with no box as a request to leave the active box alone`() {
        val request = OpenDeepLink.parse(Uri.parse("coil://open"))

        assertEquals(OpenDeepLink.Request(boxId = null), request)
    }

    @Test
    fun `parses the box id`() {
        val request = OpenDeepLink.parse(Uri.parse("coil://open?box=box-living-room"))

        assertEquals("box-living-room", request?.boxId)
    }

    /** A hand-written link with an empty parameter still means "just open the app". */
    @Test
    fun `treats a blank box as absent`() {
        val request = OpenDeepLink.parse(Uri.parse("coil://open?box="))

        assertEquals(OpenDeepLink.Request(boxId = null), request)
    }

    @Test
    fun `ignores the play host, which is a different link`() {
        assertNull(OpenDeepLink.parse(Uri.parse("coil://play?box=box-1&type=folder&path=Stories")))
    }

    @Test
    fun `ignores another scheme on the same host`() {
        assertNull(OpenDeepLink.parse(Uri.parse("https://open?box=box-1")))
    }

    @Test
    fun `ignores a null uri, so every incoming intent can be handed to it`() {
        assertNull(OpenDeepLink.parse(null))
    }

    @Test
    fun `builds a link without a box`() {
        assertEquals("coil://open", OpenDeepLink.uriFor().toString())
    }

    @Test
    fun `builds and parses a box link round trip`() {
        val uri = OpenDeepLink.uriFor("box-bedroom")

        assertEquals("coil://open?box=box-bedroom", uri.toString())
        assertEquals("box-bedroom", OpenDeepLink.parse(uri)?.boxId)
    }

    /** Ids are generated UUIDs today, but the format must not depend on that. */
    @Test
    fun `escapes a box id that needs it`() {
        val uri = OpenDeepLink.uriFor("box one&two")

        assertEquals("box one&two", OpenDeepLink.parse(uri)?.boxId)
    }

    @Test
    fun `restricts the intent to the app's own package`() {
        val intent = OpenDeepLink.intentFor(packageName = "app.coilforphoniebox", boxId = "box-1")

        assertEquals("app.coilforphoniebox", intent.`package`)
        assertEquals("coil://open?box=box-1", intent.data.toString())
    }
}
