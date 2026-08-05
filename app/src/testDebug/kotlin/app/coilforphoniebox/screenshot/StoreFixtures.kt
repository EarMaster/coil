package app.coilforphoniebox.screenshot

import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FavoriteType
import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryFolder
import app.coilforphoniebox.domain.model.LibrarySearchResults
import app.coilforphoniebox.domain.model.LibraryTrack
import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.SleepTimerStatus

/**
 * Content for the store listing and the website — a different job from [Fixtures].
 *
 * A golden's content is chosen to *stress* the layout: long titles that ellipsise, umlauts,
 * a folder name that runs off the edge. A store screenshot has the opposite job, so names here
 * are short enough to be read at thumbnail size and nothing truncates.
 *
 * It is still invented content rather than anyone's real library, which is both what Play asks
 * for (a screenshot must show the app, and this is exactly what the app shows) and the only
 * way to keep the pictures reproducible.
 */
object StoreFixtures {

    val nowPlaying = PlayerStatus(
        state = PlaybackState.PLAY,
        title = "The Cave Behind the Waterfall",
        artist = "Detective Stories",
        album = "The Missing Key",
        file = "Detective Stories/The Missing Key/03 The Cave.mp3",
        elapsedSeconds = 143.0,
        durationSeconds = 372.0,
        playlistPosition = 3,
        playlistLength = 12,
    )

    val sleepTimer = SleepTimerStatus(
        running = true,
        remainingSeconds = 1_500,
        requestedSeconds = 1_800,
        receivedAtElapsedMillis = 0L,
    )

    private fun folder(name: String) = LibraryFolder(
        boxId = Fixtures.livingRoom.id,
        path = name,
        parentPath = null,
        displayName = name,
        hasChildren = true,
        cachedAt = Fixtures.NOW,
    )

    val libraryRoot = FolderContent(
        path = FolderContent.ROOT,
        folders = listOf(
            folder("Bedtime Stories"),
            folder("Detective Stories"),
            folder("Fairy Tales"),
            folder("Nursery Rhymes"),
            folder("Sing-Along Songs"),
            folder("Bärenstark"),
        ),
        cachedAt = Fixtures.cachedThreeDaysAgo,
    )

    private fun track(number: Int, title: String, seconds: Double) = LibraryTrack(
        boxId = Fixtures.livingRoom.id,
        url = "Detective Stories/The Missing Key/0$number.mp3",
        parentPath = "Detective Stories/The Missing Key",
        title = title,
        artist = "Detective Stories",
        album = "The Missing Key",
        trackNo = number,
        durationSeconds = seconds,
    )

    /** What "story" finds — one hit of each kind, so the screenshot shows what search is for. */
    val searchResults = LibrarySearchResults(
        folders = listOf(folder("Bedtime Stories")),
        albums = listOf(Fixtures.albums.first { it.album == "The Missing Key" }),
        tracks = listOf(
            track(1, "The Letter", 301.0),
            track(3, "The Cave Behind the Waterfall", 372.0),
        ),
    )

    val favorites = listOf(
        Favorite(
            id = 1,
            boxId = Fixtures.livingRoom.id,
            label = "Bedtime Stories",
            type = FavoriteType.FOLDER,
            folder = "Bedtime Stories",
            sortIndex = 0,
            launchCount = 31,
        ),
        Favorite(
            id = 2,
            boxId = Fixtures.livingRoom.id,
            label = "The Missing Key",
            type = FavoriteType.ALBUM,
            albumArtist = "Detective Stories",
            album = "The Missing Key",
            sortIndex = 1,
            launchCount = 12,
        ),
        Favorite(
            id = 3,
            boxId = Fixtures.livingRoom.id,
            label = "Fairy Tales",
            type = FavoriteType.FOLDER,
            folder = "Fairy Tales",
            sortIndex = 2,
            launchCount = 9,
        ),
        Favorite(
            id = 4,
            boxId = Fixtures.livingRoom.id,
            label = "Nursery Rhymes",
            type = FavoriteType.FOLDER,
            folder = "Nursery Rhymes",
            sortIndex = 3,
            launchCount = 5,
        ),
    )
}
