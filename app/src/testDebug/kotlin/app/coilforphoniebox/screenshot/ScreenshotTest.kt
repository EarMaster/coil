package app.coilforphoniebox.screenshot

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.coilforphoniebox.HiltTestActivity
import app.coilforphoniebox.ui.theme.CoilTheme
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.SuccessResult
import coil.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dagger.hilt.android.testing.HiltAndroidRule
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * Base for the screenshot tests: renders the UI on the JVM and writes it to a golden file.
 *
 * Robolectric with native graphics, rather than an emulator or Layoutlib previews, for one
 * reason above the others — the clock. This UI interpolates elapsed time, counts a sleep
 * timer down and debounces a search field, and Robolectric's paused looper holds all of that
 * still. On a device the same screen is a slightly different picture every time it is taken.
 *
 * The content is composed into [HiltTestActivity], so `hiltViewModel()` resolves and a whole
 * `CoilApp` can be captured rather than only a screen; `robolectric.properties` names
 * `HiltTestApplication` as the application class for the same reason. Every concrete test
 * class must carry `@HiltAndroidTest` — that annotation is what generates the component this
 * rule installs.
 *
 * Nothing here writes a file during `./gradlew test`. [captureRoboImage] is inert unless
 * Roborazzi's own tasks turn it on, so the ordinary test run only checks that everything still
 * composes — which is worth having on its own, given none of this used to be covered.
 */
@RunWith(AndroidJUnit4::class)
abstract class ScreenshotTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<HiltTestActivity>()

    /**
     * Cover art must never be a real request. The box's HTTP cover cache is not reachable from
     * CI, and an image that arrives a frame late would make the golden depend on timing rather
     * than on the layout — so every URL resolves instantly, from [FakeCoverArt].
     *
     * It answers with synthetic artwork rather than a flat colour on purpose: one rectangle of
     * one colour looks the same whether it was cropped, stretched, letterboxed, left unclipped
     * or swapped with a different cover, which left these goldens unable to fail on any of it.
     * See [FakeCoverArt] for what each part of the picture is there to catch.
     *
     * The interceptor is added rather than set as the default because only that path receives
     * the engine's transformed request, which is the one with the crossfade removed — an image
     * that fades in would put timing back into the golden.
     */
    @Before
    fun installFakeImageLoader() {
        val resources = RuntimeEnvironment.getApplication().resources
        val engine = FakeImageLoaderEngine.Builder()
            .addInterceptor { chain ->
                SuccessResult(
                    drawable = FakeCoverArt.drawable(resources, chain.request.data.toString()),
                    request = chain.request,
                    dataSource = DataSource.MEMORY,
                )
            }
            .build()
        Coil.setImageLoader(
            ImageLoader.Builder(RuntimeEnvironment.getApplication())
                .components { add(engine) }
                .build(),
        )
    }

    /**
     * Renders [content] full screen and writes `src/testDebug/screenshots/[name].png`.
     *
     * A locale is not a parameter here: it has to be in place before the activity hosting the
     * content exists, so it comes from `@Config(qualifiers = "+de")` on the test method or
     * class. Setting it from inside the test body silently does nothing — the activity has
     * already resolved its resources by then, and the golden comes out in English.
     */
    protected fun capture(
        name: String,
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        show(dark, content)
        captureRoot(name)
    }

    /**
     * Composes without capturing, for UI that has to be *put* into the state worth a picture —
     * a tab selected, a destination navigated to, a debounce elapsed. Pair it with
     * [captureRoot].
     */
    protected fun show(
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            // The theme is passed its mode explicitly rather than reading the system setting,
            // so a golden's appearance is decided by the test and not by a qualifier. This is
            // the same wrapping MainActivity applies around CoilApp.
            CoilTheme(darkTheme = dark, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    content()
                }
            }
        }
    }

    protected fun captureRoot(name: String) {
        captureTo("$GOLDEN_DIR/$name.png")
    }

    /**
     * Captures every window at once, for UI that opens one of its own.
     *
     * A `ModalBottomSheet` composes into a separate window rather than into the activity's, so
     * [captureRoot] fails outright there — `onRoot()` matches two roots and cannot choose. This
     * composites the lot, which is also the only way to get the sheet *and* the screen behind
     * it into one picture, scrim included.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    protected fun captureScreen(name: String) {
        captureScreenRoboImage("$GOLDEN_DIR/$name.png")
    }

    /**
     * Writes the window to an arbitrary path, relative to the `:app` module directory.
     *
     * Used by the store assets, which are products rather than baselines and therefore land
     * outside the golden directory — under `fastlane/` and `docs/pages/`.
     */
    protected fun captureTo(path: String) {
        compose.onRoot().captureRoboImage(path)
    }

    /**
     * Whether Roborazzi is actually writing files this run.
     *
     * `captureRoboImage` is a no-op during a plain `./gradlew test`, so anything that
     * post-processes a captured file has to know not to touch the copy already on disk.
     */
    protected fun isRecording(): Boolean =
        System.getProperty("roborazzi.test.record")?.toBoolean() == true

    /**
     * Captures a single component with a little padding around it.
     *
     * The picture is still the whole window: Roborazzi captures the window rather than
     * cropping to the node, whichever node it is handed. A component test therefore shortens
     * the window instead, with `@Config(qualifiers = "+h200dp")` on the class, so the golden is
     * the component and not a stripe of it above a screenful of background.
     */
    protected fun captureComponent(
        name: String,
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        show(dark) {
            Box(Modifier.padding(16.dp)) { content() }
        }
        captureRoot(name)
    }

    /**
     * Runs the main looper forward by [millis] of virtual time.
     *
     * Needed wherever the UI waits on a coroutine `delay` — the search field's debounce is
     * the case here. Robolectric's clock does not move on its own, which is the whole reason
     * these goldens are stable, so anything time-based has to be advanced deliberately.
     */
    protected fun advanceTime(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        compose.waitForIdle()
    }

    /**
     * Advances the clock until [text] is on screen, and fails the test if it never is.
     *
     * Compose's own `waitUntil` is no use here: it advances the *compose* clock, and what a
     * debounced flow is waiting on is the looper's. So this steps [advanceTime] instead, which
     * runs the delayed work the coroutine actually posted.
     */
    protected fun awaitText(text: String, timeoutMillis: Long = 5_000) {
        var waited = 0L
        while (waited <= timeoutMillis) {
            if (compose.onAllNodes(hasText(text, substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                return
            }
            advanceTime(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        error("Timed out after ${timeoutMillis}ms waiting for \"$text\" to appear")
    }

    private companion object {
        /** Committed next to the tests so a review sees the picture beside the change. */
        const val GOLDEN_DIR = "src/testDebug/screenshots"

        /** How far the clock steps between checks in [awaitText]. */
        const val POLL_MILLIS = 250L
    }
}
