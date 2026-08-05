package app.coilforphoniebox.screenshot

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.hilt.navigation.compose.hiltViewModel
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.ui.AppViewModel
import app.coilforphoniebox.ui.CoilApp
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The app as it is actually seen: `CoilApp`'s scaffold with a screen inside it.
 *
 * The screen-level goldens deliberately capture screens on their own, which leaves out
 * everything the scaffold draws — the top bar with the box indicator, the bottom navigation,
 * the mini player and the offline banner. These capture the assembled thing instead, so a
 * golden of the player starts at the title bar the way the app does.
 *
 * Destinations are reached by clicking the navigation bar rather than by composing a screen
 * directly, which means the navigation itself is under test too: a destination that stopped
 * being reachable would fail here rather than quietly keep its old picture.
 *
 * Subclasses supply the device. Everything else is shared, so adding a phone size is a class
 * with two lines in it.
 */
abstract class AppScreenshotTest : ScreenshotTest() {

    /** Suffix for every golden this class writes. Diff output is flat, so it cannot repeat. */
    protected abstract val device: String

    @Inject lateinit var boxes: FakeBoxRepository

    @Inject lateinit var player: FakePlayerRepository

    @Before
    fun injectFakes() {
        hilt.inject()
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun showApp(dark: Boolean = false) {
        show(dark) { CoilApp(appViewModel = hiltViewModel<AppViewModel>()) }
    }

    /** Bottom navigation is how a user changes screen, so it is how these tests do it too. */
    private fun navigateTo(label: Int) {
        compose.onNodeWithText(string(label)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun player() {
        showApp()
        captureRoot("app/player_$device")
    }

    @Test
    fun player_dark() {
        showApp(dark = true)
        captureRoot("app/player_dark_$device")
    }

    /**
     * Away from the player, the mini player appears above the navigation bar — the one piece
     * of chrome that is conditional on both what is playing and where you are.
     */
    @Test
    fun library() {
        showApp()
        navigateTo(R.string.nav_library)
        captureRoot("app/library_$device")
    }

    @Test
    fun favourites() {
        showApp()
        navigateTo(R.string.nav_favourites)
        captureRoot("app/favourites_$device")
    }

    @Test
    fun settings() {
        showApp()
        navigateTo(R.string.nav_settings)
        captureRoot("app/settings_$device")
    }

    /**
     * The settings rows that start below the fold on a phone — the row into box management, and
     * the library actions that run against whichever box is active.
     *
     * Scrolled to by name rather than by gesture, so the golden lands on the same rows every
     * time. Without this the settings golden would only ever show the appearance section, and a
     * change further down would pass unseen.
     */
    @Test
    fun settings_lower_section() {
        showApp()
        navigateTo(R.string.nav_settings)
        compose.onNodeWithText(string(R.string.settings_index)).performScrollTo()
        compose.waitForIdle()
        captureRoot("app/settings_lower_$device")
    }

    /**
     * Box management, reached the way a user reaches it: settings, then the row that leads out
     * of settings. Two boxes, because one box is the case where this screen has least to say.
     */
    @Test
    fun boxes() {
        boxes.boxes.value = listOf(Fixtures.livingRoom, Fixtures.bedroom)
        showApp()
        navigateTo(R.string.nav_settings)
        openBoxManagement()
        captureRoot("app/boxes_$device")
    }

    /**
     * One box's own page — the fields that used to sit in the settings list, on the box that is
     * *not* active, which is the case the settings screen could not offer at all.
     */
    @Test
    fun box_detail() {
        boxes.boxes.value = listOf(Fixtures.livingRoom, Fixtures.bedroom)
        showApp()
        navigateTo(R.string.nav_settings)
        openBoxManagement()
        compose.onNodeWithText(Fixtures.bedroom.displayName).performClick()
        compose.waitForIdle()
        captureRoot("app/box_detail_$device")
    }

    private fun openBoxManagement() {
        compose.onNodeWithText(string(R.string.settings_manage_boxes)).performScrollTo()
        compose.onNodeWithText(string(R.string.settings_manage_boxes)).performClick()
        compose.waitForIdle()
    }

    /**
     * Box configured, box not answering: the offline banner under the top bar, and the
     * indicator's dot gone grey. Both live in the scaffold and appear in no screen golden.
     */
    @Test
    fun offline() {
        player.connectionState.value = ConnectionState.DISCONNECTED
        player.status.value = Fixtures.playing.copy(state = PlaybackState.PAUSE)
        showApp()
        navigateTo(R.string.nav_library)
        captureRoot("app/offline_$device")
    }

    /**
     * A first launch has no box, and the app is the add-box screen and nothing else — no
     * navigation bar, no top bar, no way past it (§11.2).
     */
    @Test
    fun onboarding() {
        boxes.boxes.value = emptyList()
        boxes.activeBox.value = null
        showApp()
        captureRoot("app/onboarding_$device")
    }
}

/** The reference device: the size `robolectric.properties` sets for everything else. */
@HiltAndroidTest
class MediumPhoneAppScreenshotTest : AppScreenshotTest() {
    override val device = "phone"
}

/**
 * A small, dense phone. Half the point of a device axis: 360×640 is where a bottom navigation
 * bar, a mini player and a screen stop fitting together.
 */
@HiltAndroidTest
@Config(qualifiers = "w360dp-h640dp-normal-long-notround-any-xhdpi-keyshidden-nonav")
class SmallPhoneAppScreenshotTest : AppScreenshotTest() {
    override val device = "small"
}

/**
 * A tablet in landscape. The UI is single-column everywhere and this is what that looks like
 * on 1280 dp — these goldens document the gap rather than claiming it is handled.
 */
@HiltAndroidTest
@Config(qualifiers = "w1280dp-h800dp-xlarge-notlong-notround-any-xhdpi-keyshidden-nonav")
class TabletAppScreenshotTest : AppScreenshotTest() {
    override val device = "tablet"
}
