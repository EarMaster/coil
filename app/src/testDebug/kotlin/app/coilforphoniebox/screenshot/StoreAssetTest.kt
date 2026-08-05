package app.coilforphoniebox.screenshot

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.hilt.navigation.compose.hiltViewModel
import app.coilforphoniebox.R
import app.coilforphoniebox.ui.AppViewModel
import app.coilforphoniebox.ui.CoilApp
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The Play Store listing images and the website's screenshots, from the same harness as the
 * goldens but with a different purpose — and therefore different rules.
 *
 * These are **products, not baselines**: they are regenerated deliberately and are not
 * compared against anything, so they are excluded from the verify job by a `--tests` filter.
 * A UI change should fail one job, not two. See AGENTS.md, "Store assets".
 *
 *     ./gradlew :app:recordRoborazziDebug --tests '*StoreAssetTest'
 *
 * Play's rules are what dictate the shape of what follows:
 *
 * - **24-bit PNG, no alpha.** Roborazzi writes RGBA, so every file is flattened onto an opaque
 *   background on the way out. A screenshot with an alpha channel is rejected at upload.
 * - **The longest side may be at most twice the shortest**, between 320 and 3840 px. The
 *   goldens' 1078×2399 phone frame is 2.23:1 and would be refused, which is the main reason
 *   these are generated separately rather than copied across.
 * - Play recommends 1080×1920 for phones, which `w360dp-h640dp` at xxhdpi hits exactly.
 *
 * `tools/check_store_metadata.sh` enforces all of that on the files themselves, so a
 * hand-added image is checked as well as a generated one.
 *
 * Only English is generated for now. Every other launch locale would be one more subclass with
 * a locale qualifier — but three of the five translations are unreviewed drafts missing 43
 * strings, and a store screenshot with English fallbacks showing in it is worse than no
 * localised screenshot at all.
 */
abstract class StoreAssetTest : ScreenshotTest() {

    /** `phoneScreenshots`, `sevenInchScreenshots` or `tenInchScreenshots`, per Play's layout. */
    protected abstract val imagesDir: String

    @Inject lateinit var boxes: FakeBoxRepository

    @Inject lateinit var player: FakePlayerRepository

    @Inject lateinit var library: FakeLibraryRepository

    @Inject lateinit var favorites: FakeFavoriteRepository

    @Before
    fun useStoreContent() {
        hilt.inject()
        player.status.value = StoreFixtures.nowPlaying
        library.allFolders.value = mapOf("" to StoreFixtures.libraryRoot)
        library.results.value = StoreFixtures.searchResults
        favorites.all.value = StoreFixtures.favorites
    }

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    private fun showApp() {
        show { CoilApp(appViewModel = hiltViewModel<AppViewModel>()) }
    }

    private fun navigateTo(label: Int) {
        compose.onNodeWithText(string(label)).performClick()
        compose.waitForIdle()
    }

    /**
     * Brings the transport and volume controls into view.
     *
     * The player is taller than a store-legal frame: Play caps the long side at twice the
     * short one, and the cover alone is a full screen width square. Without this the listing's
     * first screenshot would show a player with no play button in it. Scrolling to a node is
     * exact — no fling, no gesture distance to get wrong — so the result is the same picture
     * every run.
     */
    private fun scrollToControls() {
        compose.onNodeWithContentDescription(string(R.string.action_volume)).performScrollTo()
        compose.waitForIdle()
    }

    /**
     * Captures straight into the fastlane layout.
     *
     * The numeric prefix is the order Play and fastlane list them in, which is filename order —
     * so the numbers are the running order of the listing, not decoration.
     *
     * Roborazzi writes RGBA, which Play rejects. Flattening happens afterwards in the
     * `flattenStoreAssets` Gradle task rather than here, because `javax.imageio` and `java.awt`
     * are not on an Android unit test's classpath at all. That task also puts the phone set on
     * the website, and `recordRoborazziDebug` is finalised by it, so one command still does the
     * whole job.
     */
    private fun store(name: String) {
        captureTo("$STORE_DIR/$imagesDir/$name.png")
    }

    @Test
    fun player() {
        showApp()
        scrollToControls()
        store("01_player")
    }

    @Test
    fun library() {
        showApp()
        navigateTo(R.string.nav_library)
        store("02_library")
    }

    @Test
    fun favourites() {
        showApp()
        navigateTo(R.string.nav_favourites)
        store("03_favourites")
    }

    /** Typed rather than injected, so the picture shows a real query in a real field. */
    @Test
    fun search() {
        showApp()
        navigateTo(R.string.nav_library)
        compose.onNode(hasSetTextAction()).performTextInput(SEARCH_QUERY)

        // Results are debounced, and a store screenshot of an empty result list would be worse
        // than no screenshot at all — so this waits for a hit to actually be on screen and
        // fails the run if one never arrives, rather than photographing whatever is there.
        awaitText(SEARCH_HIT)
        store("04_search")
    }

    /** The one feature Coil has that the box's own web UI does not put a countdown on. */
    @Test
    fun sleep_timer() {
        player.sleepTimer.value = StoreFixtures.sleepTimer
        showApp()
        scrollToControls()
        store("05_sleep_timer")
    }

    private companion object {
        /** Relative to the `:app` module directory, which is where a test's paths resolve. */
        const val STORE_DIR = "../fastlane/metadata/android/en-US/images"

        const val SEARCH_QUERY = "story"

        /** A result the query must produce, so the wait has something concrete to wait for. */
        const val SEARCH_HIT = "Bedtime Stories"
    }
}

/**
 * A phone: 1233×2460, from 411×820 dp at xxhdpi.
 *
 * Sized to be as tall as Play allows and no taller — 1.995:1, just inside the rule that the
 * long side may not exceed twice the short one. Today's phones are taller than that (the
 * goldens' frame is 2.23:1), and the extra dp matter here: every 20 dp given back is 20 dp of
 * the player that fits without scrolling. It comfortably clears Play's 1080 px recommendation
 * for the screenshots that get surfaced in recommendation slots.
 *
 * This is also the set the website shows — `flattenStoreAssets` copies `phoneScreenshots` into
 * the Pages tree, since nobody browses a landing page for tablet pictures.
 */
@HiltAndroidTest
@Config(qualifiers = "w411dp-h820dp-normal-long-notround-any-xxhdpi-keyshidden-nonav")
class PhoneStoreAssetTest : StoreAssetTest() {
    override val imagesDir = "phoneScreenshots"
}

/** A 7-inch tablet in portrait: 1200×1920. */
@HiltAndroidTest
@Config(qualifiers = "w600dp-h960dp-large-notlong-notround-any-xhdpi-keyshidden-nonav")
class SevenInchStoreAssetTest : StoreAssetTest() {
    override val imagesDir = "sevenInchScreenshots"
}

/**
 * A 10-inch tablet in landscape: 2560×1600.
 *
 * Worth looking at before uploading. The UI is single-column everywhere, so these show a phone
 * layout stretched across a tablet — accurate, and not flattering. Until the tablet layout is
 * addressed, publishing the phone set alone may serve the listing better.
 */
@HiltAndroidTest
@Config(qualifiers = "w1280dp-h800dp-xlarge-notlong-notround-any-xhdpi-keyshidden-nonav")
class TenInchStoreAssetTest : StoreAssetTest() {
    override val imagesDir = "tenInchScreenshots"
}
