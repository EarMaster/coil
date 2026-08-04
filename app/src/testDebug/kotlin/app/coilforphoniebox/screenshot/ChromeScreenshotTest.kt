package app.coilforphoniebox.screenshot

import androidx.compose.ui.res.stringResource
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.ui.components.BoxIndicator
import app.coilforphoniebox.ui.components.MiniPlayer
import app.coilforphoniebox.ui.components.OfflineBanner
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The app's chrome: what `CoilApp` draws around whichever screen is showing.
 *
 * None of this appears in the screen goldens, because the screens do not draw it — the top
 * bar's box indicator, the mini player and the offline banner all live in the scaffold. They
 * are captured here as components rather than in place, since assembling the real scaffold
 * means navigation and Hilt-injected view models.
 *
 * The box indicator earns the most attention of the three: it is the only place the app says
 * anything about the connection, and "degraded" and "connected" differing by one small dot is
 * exactly the kind of thing a golden should be watching.
 */
@HiltAndroidTest
@Config(qualifiers = "+h200dp")
class ChromeScreenshotTest : ScreenshotTest() {

    @Test
    fun box_indicator_connected() {
        captureComponent("chrome/box_indicator_connected") {
            BoxIndicator(
                activeBox = Fixtures.livingRoom,
                connection = ConnectionState.CONNECTED,
                switchable = false,
                onClick = {},
            )
        }
    }

    /** Two boxes: the indicator becomes a switcher and grows an affordance for it. */
    @Test
    fun box_indicator_switchable() {
        captureComponent("chrome/box_indicator_switchable") {
            BoxIndicator(
                activeBox = Fixtures.livingRoom,
                connection = ConnectionState.CONNECTED,
                switchable = true,
                onClick = {},
            )
        }
    }

    /** Sockets up, box gone quiet past the watchdog — the state that must not read as fine. */
    @Test
    fun box_indicator_degraded() {
        captureComponent("chrome/box_indicator_degraded") {
            BoxIndicator(
                activeBox = Fixtures.livingRoom,
                connection = ConnectionState.DEGRADED,
                switchable = true,
                onClick = {},
            )
        }
    }

    @Test
    fun box_indicator_disconnected() {
        captureComponent("chrome/box_indicator_disconnected") {
            BoxIndicator(
                activeBox = Fixtures.livingRoom,
                connection = ConnectionState.DISCONNECTED,
                switchable = false,
                onClick = {},
            )
        }
    }

    @Test
    fun mini_player_playing() {
        captureComponent("chrome/mini_player_playing") {
            MiniPlayer(
                status = Fixtures.playing,
                coverUrl = "http://phoniebox.local/cover-cache/missing-key.jpg",
                onClick = {},
                onToggle = {},
            )
        }
    }

    @Test
    fun mini_player_paused_dark() {
        captureComponent("chrome/mini_player_paused_dark", dark = true) {
            MiniPlayer(
                status = Fixtures.paused,
                coverUrl = null,
                onClick = {},
                onToggle = {},
            )
        }
    }

    /** The banner takes its text from the caller, so the golden uses the app's own string. */
    @Test
    fun offline_banner() {
        captureComponent("chrome/offline_banner") {
            OfflineBanner(text = stringResource(R.string.offline_banner))
        }
    }
}
