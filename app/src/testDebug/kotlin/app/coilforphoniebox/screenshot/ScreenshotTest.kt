package app.coilforphoniebox.screenshot

import android.graphics.drawable.ColorDrawable
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.coilforphoniebox.HiltTestActivity
import app.coilforphoniebox.ui.theme.CoilTheme
import coil.Coil
import coil.ImageLoader
import coil.test.FakeImageLoaderEngine
import com.github.takahirom.roborazzi.captureRoboImage
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
     * than on the layout — so every URL resolves to the same flat colour, instantly.
     */
    @Before
    fun installFakeImageLoader() {
        val engine = FakeImageLoaderEngine.Builder()
            .default(ColorDrawable(FAKE_COVER_COLOR))
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
        compose.onRoot().captureRoboImage("$GOLDEN_DIR/$name.png")
    }

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

    private companion object {
        /** Committed next to the tests so a review sees the picture beside the change. */
        const val GOLDEN_DIR = "src/testDebug/screenshots"

        /** A muted green, close enough to real artwork to show how the layout carries it. */
        const val FAKE_COVER_COLOR = 0xFF3B5A46.toInt()
    }
}
