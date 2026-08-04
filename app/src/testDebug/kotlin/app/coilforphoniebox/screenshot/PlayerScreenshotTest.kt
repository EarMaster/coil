package app.coilforphoniebox.screenshot

import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.SleepTimerStatus
import app.coilforphoniebox.domain.model.VolumeStatus
import app.coilforphoniebox.ui.player.PlayerScreen
import app.coilforphoniebox.ui.player.PlayerViewModel
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The player screen, in the states it is actually seen in.
 *
 * "Playing" is not one picture: web radio has no duration to draw a progress bar from, an
 * idle box has no title, and a running sleep timer adds a line under the transport row. Each
 * of those has broken separately at some point, which is why each gets its own golden.
 */
@HiltAndroidTest
class PlayerScreenshotTest : ScreenshotTest() {

    private fun viewModel(
        status: PlayerStatus = Fixtures.playing,
        volume: VolumeStatus = VolumeStatus(level = 42, maxLevel = 100),
        connection: ConnectionState = ConnectionState.CONNECTED,
        coverUrl: String? = "http://phoniebox.local/cover-cache/missing-key.jpg",
        sleepTimer: SleepTimerStatus = SleepTimerStatus.Off,
        favorites: List<Favorite> = Fixtures.favorites,
    ) = PlayerViewModel(
        player = FakePlayerRepository(
            status = status,
            volume = volume,
            connection = connection,
            coverUrl = coverUrl,
            sleepTimer = sleepTimer,
        ),
        boxes = FakeBoxRepository(),
        favorites = FakeFavoriteRepository(favorites),
    )

    @Test
    fun playing_light() {
        val vm = viewModel()
        capture("player/playing_light") { PlayerScreen(vm) }
    }

    @Test
    fun playing_dark() {
        val vm = viewModel()
        capture("player/playing_dark", dark = true) { PlayerScreen(vm) }
    }

    @Test
    fun paused() {
        val vm = viewModel(status = Fixtures.paused)
        capture("player/paused_light") { PlayerScreen(vm) }
    }

    /** No box has ever answered: no title, no cover, nothing to seek in. */
    @Test
    fun nothing_playing() {
        val vm = viewModel(
            status = PlayerStatus.Idle,
            connection = ConnectionState.DISCONNECTED,
            coverUrl = null,
            volume = VolumeStatus.Unknown,
            favorites = emptyList(),
        )
        capture("player/idle_light") { PlayerScreen(vm) }
    }

    /** A stream reports no duration and no album — the layout has to survive both. */
    @Test
    fun web_radio() {
        val vm = viewModel(status = Fixtures.webRadio, coverUrl = null)
        capture("player/web_radio_light") { PlayerScreen(vm) }
    }

    /**
     * Shuffle, repeat and a running timer at once: the options button is tinted and the
     * countdown gets its own line, which is the one thing on this screen that appears out of
     * nowhere rather than changing in place.
     */
    @Test
    fun sleep_timer_and_options() {
        val vm = viewModel(status = Fixtures.shuffledAndRepeating, sleepTimer = Fixtures.timerRunning)
        capture("player/sleep_timer_light") { PlayerScreen(vm) }
    }

    /**
     * German. Almost everything on a playing screen is the box's own metadata, so what this
     * golden actually shows is the idle state, where the app is doing the talking.
     *
     * The locale axis lives mostly on the library screen for that reason — see
     * `LibraryScreenshotTest.folders_german`.
     */
    @Test
    @Config(qualifiers = "+de")
    fun nothing_playing_german() {
        val vm = viewModel(
            status = PlayerStatus.Idle,
            connection = ConnectionState.DISCONNECTED,
            coverUrl = null,
            volume = VolumeStatus.Unknown,
            favorites = emptyList(),
        )
        capture("player/idle_de") { PlayerScreen(vm) }
    }
}
