package app.coilforphoniebox.screenshot

import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FavoriteType
import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.LibraryFolder
import app.coilforphoniebox.domain.model.LibrarySearchResults
import app.coilforphoniebox.domain.model.LibraryTrack
import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.SleepTimerStatus
import java.util.concurrent.TimeUnit

/**
 * The library a screenshot shows.
 *
 * Names are deliberately a mixed bag: an umlaut and a long title are in here because those
 * are what break a layout, and a golden that only ever renders short ASCII would not notice.
 * German in particular runs longer than English at the same meaning, which is the reason the
 * locale axis exists at all.
 */
object Fixtures {

    /** A fixed instant for anything whose exact value never reaches the screen. */
    const val NOW = 1_770_000_000_000L

    /**
     * Freshness labels are computed against the wall clock, so a cache timestamp has to be an
     * offset from it rather than a constant — a fixed epoch would read "Updated 2,847 days
     * ago" and change every night. The extra hours keep the value off the bucket boundary, so
     * "3 days ago" cannot round to "4" depending on when the suite runs.
     */
    val cachedThreeDaysAgo: Long
        get() = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3) - TimeUnit.HOURS.toMillis(5)

    val livingRoom = Box(
        id = "box-living-room",
        displayName = "Living room",
        host = "phoniebox.local",
        addedAt = NOW,
        lastSeenAt = NOW,
    )

    val bedroom = Box(
        id = "box-bedroom",
        displayName = "Bedroom",
        host = "phoniebox-bedroom.local",
        addedAt = NOW,
        sortIndex = 1,
    )

    // ------------------------------------------------------------------ player

    val playing = PlayerStatus(
        state = PlaybackState.PLAY,
        title = "Chapter 3 — The Cave Behind the Waterfall",
        artist = "Detective Stories",
        album = "The Missing Key",
        file = "Detective Stories/The Missing Key/03 The Cave.mp3",
        elapsedSeconds = 95.0,
        durationSeconds = 372.0,
        songId = "17",
        playlistPosition = 3,
        playlistLength = 12,
    )

    val paused = playing.copy(state = PlaybackState.PAUSE, elapsedSeconds = 214.0)

    val shuffledAndRepeating = playing.copy(shuffle = true, repeat = RepeatMode.ALL)

    /** Web radio: no album, no duration — the case that used to break the progress row. */
    val webRadio = PlayerStatus(
        state = PlaybackState.PLAY,
        title = "Kinderradio — Am Nachmittag",
        artist = "Kinderradio",
        file = "http://stream.example.org/kinderradio.mp3",
        elapsedSeconds = 3_612.0,
        playlistLength = 1,
    )

    val timerRunning = SleepTimerStatus(
        running = true,
        remainingSeconds = 1_800,
        requestedSeconds = 1_800,
        receivedAtElapsedMillis = 0L,
    )

    // ----------------------------------------------------------------- library

    private fun folder(path: String, name: String, parent: String? = null, hasChildren: Boolean = true) =
        LibraryFolder(
            boxId = livingRoom.id,
            path = path,
            parentPath = parent,
            displayName = name,
            hasChildren = hasChildren,
            cachedAt = NOW,
        )

    val libraryRoot = FolderContent(
        path = FolderContent.ROOT,
        folders = listOf(
            folder("Bedtime Stories", "Bedtime Stories"),
            folder("Bärenstark", "Bärenstark"),
            folder("Detective Stories", "Detective Stories"),
            folder("Nursery Rhymes", "Nursery Rhymes"),
            folder("Sing-Along Songs for the Long Drive Home", "Sing-Along Songs for the Long Drive Home"),
        ),
        cachedAt = cachedThreeDaysAgo,
    )

    val detectiveStories = FolderContent(
        path = "Detective Stories/The Missing Key",
        folders = emptyList(),
        tracks = listOf(
            track(1, "01 The Letter.mp3", "Chapter 1 — The Letter", 301.0),
            track(2, "02 The Old House.mp3", "Chapter 2 — The Old House", 288.0),
            track(3, "03 The Cave.mp3", "Chapter 3 — The Cave Behind the Waterfall", 372.0),
            track(4, "04 Footprints.mp3", "Chapter 4 — Footprints in the Sand", 265.0),
            track(5, "05 The Key.mp3", "Chapter 5 — The Key", 341.0),
        ),
        cachedAt = cachedThreeDaysAgo,
    )

    private fun track(number: Int, file: String, title: String, seconds: Double) = LibraryTrack(
        boxId = livingRoom.id,
        url = "Detective Stories/The Missing Key/$file",
        parentPath = "Detective Stories/The Missing Key",
        title = title,
        artist = "Detective Stories",
        album = "The Missing Key",
        trackNo = number,
        durationSeconds = seconds,
    )

    private fun album(artist: String, name: String) = LibraryAlbum(
        boxId = livingRoom.id,
        albumArtist = artist,
        album = name,
        cachedAt = NOW,
    )

    val albums = listOf(
        album("Bärenstark", "Ein Bär räumt auf"),
        album("Detective Stories", "The Missing Key"),
        album("Detective Stories", "The Silent Lighthouse"),
        album("Nursery Rhymes", "Ten in the Bed"),
        album("Nursery Rhymes", "Wheels on the Bus"),
        album("Sing-Along", "Songs for the Long Drive Home"),
    )

    /** What "the" finds: one hit of each kind, which is the layout worth a golden. */
    val searchResults = LibrarySearchResults(
        query = "the",
        folders = listOf(folder("Bedtime Stories", "Bedtime Stories")),
        albums = listOf(album("Detective Stories", "The Missing Key")),
        tracks = listOf(
            track(1, "01 The Letter.mp3", "Chapter 1 — The Letter", 301.0),
            track(5, "05 The Key.mp3", "Chapter 5 — The Key", 341.0),
        ),
    )

    // --------------------------------------------------------------- favourites

    /**
     * Two with artwork and one without, which is the mix worth a golden: a cover has to
     * render *and* a favourite the box has no artwork for has to stay recognisable by its
     * placeholder. The file names are a stand-in — the fake image loader answers every URL
     * with the same flat colour, so what they say is only that a cover was resolved.
     */
    val favorites = listOf(
        Favorite(
            id = 1,
            boxId = livingRoom.id,
            label = "Bedtime Stories",
            type = FavoriteType.FOLDER,
            folder = "Bedtime Stories",
            coverFile = "cover-bedtime.jpg",
            sortIndex = 0,
            launchCount = 31,
        ),
        Favorite(
            id = 2,
            boxId = livingRoom.id,
            label = "The Missing Key",
            type = FavoriteType.ALBUM,
            albumArtist = "Detective Stories",
            album = "The Missing Key",
            coverFile = "cover-missing-key.jpg",
            sortIndex = 1,
            launchCount = 12,
        ),
        Favorite(
            id = 3,
            boxId = livingRoom.id,
            label = "Chapter 3 — The Cave Behind the Waterfall",
            type = FavoriteType.TRACK,
            trackUrl = "Detective Stories/The Missing Key/03 The Cave.mp3",
            sortIndex = 2,
            launchCount = 4,
        ),
    )
}
