package app.coilforphoniebox.screenshot

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.coilforphoniebox.R
import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.LibrarySearchResults
import app.coilforphoniebox.domain.model.LibrarySource
import app.coilforphoniebox.ui.library.LibraryScreen
import app.coilforphoniebox.ui.library.LibraryViewModel
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The library screen: folder list, album grid, search results, and the two empty states.
 *
 * Search and the album grid are reached by driving the screen rather than by handing the
 * ViewModel a different starting state — that is how a user gets there, and it means the
 * golden also proves the tab and the query field still work.
 */
@HiltAndroidTest
class LibraryScreenshotTest : ScreenshotTest() {

    private fun viewModel(
        folders: Map<String, FolderContent> = mapOf(FolderContent.ROOT to Fixtures.libraryRoot),
        albums: List<LibraryAlbum> = Fixtures.albums,
        albumsCachedAt: Long? = Fixtures.cachedThreeDaysAgo,
        searchResults: LibrarySearchResults = Fixtures.searchResults,
        sources: List<LibrarySource> = emptyList(),
    ) = LibraryViewModel(
        library = FakeLibraryRepository(
            folders = folders,
            albums = albums,
            cachedAt = albumsCachedAt,
            results = searchResults,
        ).also { it.sources.value = sources },
        boxes = FakeBoxRepository(),
        player = FakePlayerRepository(),
        favorites = FakeFavoriteRepository(Fixtures.favorites),
        settings = FakeSettingsRepository(),
    )

    private fun string(id: Int): String = RuntimeEnvironment.getApplication().getString(id)

    @Test
    fun folders_light() {
        val vm = viewModel()
        capture("library/folders_light") { LibraryScreen(vm) }
    }

    @Test
    fun folders_dark() {
        val vm = viewModel()
        capture("library/folders_dark", dark = true) { LibraryScreen(vm) }
    }

    /**
     * German: the tabs, the breadcrumb and the freshness line all in one picture, which is
     * where a longer language runs out of room first.
     *
     * It is also the only look anyone gets at the translations without a phone — and it shows
     * the drift honestly, because 43 of the 163 strings have no German yet and fall back to
     * English on screen.
     */
    @Test
    @Config(qualifiers = "+de")
    fun folders_german() {
        val vm = viewModel()
        capture("library/folders_de") { LibraryScreen(vm) }
    }

    /** A level down: tracks with durations, and a breadcrumb wide enough to scroll. */
    @Test
    fun tracks_in_folder() {
        val path = Fixtures.detectiveStories.path
        val vm = viewModel(
            folders = mapOf(
                FolderContent.ROOT to Fixtures.libraryRoot,
                path to Fixtures.detectiveStories,
            ),
        )
        vm.openFolder(path)
        capture("library/tracks_light") { LibraryScreen(vm) }
    }

    @Test
    fun albums_grid() {
        val vm = viewModel()
        show { LibraryScreen(vm) }
        compose.onNodeWithText(string(R.string.library_tab_albums)).performClick()
        captureRoot("library/albums_light")
    }

    /**
     * A box with a streaming service as well as its own music: every tile gains a kind badge
     * and its source, and the same record from both sources stays two tiles.
     *
     * The counterpart is [albums_grid], which must keep showing none of that — on a box with
     * one source the marks would be identical on every tile and say nothing.
     */
    @Test
    fun albums_grid_with_two_sources() {
        val vm = viewModel(albums = Fixtures.mixedSourceAlbums, sources = Fixtures.mixedSources)
        show { LibraryScreen(vm) }
        compose.onNodeWithText(string(R.string.library_tab_albums)).performClick()
        captureRoot("library/albums_two_sources_light")
    }

    /**
     * Results are debounced, so the clock has to move before there is anything to see — the
     * one place these tests have to acknowledge time at all.
     */
    @Test
    fun search_results() {
        val vm = viewModel()
        vm.onSearchQueryChange("the")
        show { LibraryScreen(vm) }
        advanceTime(500)
        captureRoot("library/search_light")
    }

    /**
     * Search found nothing. Worth a golden of its own because the message has a job to do:
     * only what has been fetched is searchable, and an empty result must not read as an
     * empty library.
     */
    @Test
    fun search_without_results() {
        val vm = viewModel(searchResults = LibrarySearchResults())
        vm.onSearchQueryChange("zzz")
        show { LibraryScreen(vm) }
        advanceTime(500)
        captureRoot("library/search_empty_light")
    }

    /** Nothing cached yet — the state a first run shows before the box has answered. */
    @Test
    fun empty_library() {
        val vm = viewModel(folders = emptyMap(), albums = emptyList(), albumsCachedAt = null)
        capture("library/empty_light") { LibraryScreen(vm) }
    }
}
