package app.coilforphoniebox.screenshot

import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FavoriteType
import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.LibraryContentType
import app.coilforphoniebox.domain.model.LibraryFolder
import app.coilforphoniebox.domain.model.LibraryProvider
import app.coilforphoniebox.domain.model.LibrarySearchResults
import app.coilforphoniebox.domain.model.LibrarySource
import app.coilforphoniebox.domain.model.LibraryTrack
import app.coilforphoniebox.domain.model.PlaybackState
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.QueueEntry
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
        // Third of twelve, zero-based — so in [queue] the highlighted row is "Chapter 3" and
        // the numbering either side of it reads the way a reader would expect.
        playlistPosition = 2,
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

    /**
     * The queue [playing] is playing from — twelve tracks, matching its `playlistLength`, and
     * carrying its title and URL at its `playlistPosition` so the sheet's highlighted row is the
     * same track the player above it names.
     *
     * Deliberately mixed, because a row here has three things to get wrong: one track has no
     * duration the way a stream does (row 10), one has no artist or album so the subtitle line
     * has to disappear rather than render blank (row 6), and one title is long enough that
     * ellipsising is in the picture (row 8).
     */
    val queue = List(12) { index ->
        val number = index + 1
        QueueEntry(
            position = index,
            url = if (index == playing.playlistPosition) {
                playing.file!!
            } else {
                "Detective Stories/The Missing Key/${"%02d".format(number)} Chapter.mp3"
            },
            title = when (index) {
                playing.playlistPosition -> playing.title!!
                7 -> "Chapter 8 — A Long Title That Has To Be Cut Off Somewhere Around Here"
                else -> "Chapter $number"
            },
            artist = "Detective Stories".takeUnless { index == 5 },
            album = "The Missing Key".takeUnless { index == 5 },
            durationSeconds = if (index == 9) null else 240.0 + index * 17,
            songId = (14 + index).toString(),
        )
    }

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

    private fun album(
        artist: String,
        name: String,
        cover: String? = null,
        provider: String = LibraryProvider.MPD,
        contentUri: String? = null,
        contentType: String = LibraryContentType.ALBUM,
    ) = LibraryAlbum(
        boxId = livingRoom.id,
        albumArtist = artist,
        album = name,
        coverFile = cover,
        cachedAt = NOW,
        provider = provider,
        contentUri = contentUri,
        contentType = contentType,
    )

    /**
     * Four with artwork and two without, so the album grid is a picture of both cases — a
     * cover resolved from the box next to an album it has no art for, which now draws one of
     * the app's own stand-ins rather than an icon. The file names are a stand-in too, but not
     * an inert one: [FakeCoverArt] derives a different picture from each.
     */
    val albums = listOf(
        album("Bärenstark", "Ein Bär räumt auf", "cover-baer.jpg"),
        album("Detective Stories", "The Missing Key", "cover-missing-key.jpg"),
        album("Detective Stories", "The Silent Lighthouse", "cover-lighthouse.jpg"),
        album("Nursery Rhymes", "Ten in the Bed"),
        album("Nursery Rhymes", "Wheels on the Bus", "cover-wheels.jpg"),
        album("Sing-Along", "Songs for the Long Drive Home"),
    )

    /**
     * A box with a streaming service alongside its own music — the only state in which the
     * kind badge and the source name appear at all.
     *
     * Deliberately includes the awkward case the whole identity change exists for: "Ein Bär
     * räumt auf" appears twice, once from each source. It has to stay two tiles, and the two
     * have to be tellable apart — owning a record on disc *and* having it saved in an account
     * is ordinary, and the two are started by different calls.
     */
    val mixedSourceAlbums = listOf(
        album("Bärenstark", "Ein Bär räumt auf", "cover-baer.jpg"),
        album("Detective Stories", "The Missing Key", "cover-missing-key.jpg"),
        album("Nursery Rhymes", "Wheels on the Bus", "cover-wheels.jpg"),
        album(
            artist = "Bärenstark",
            name = "Ein Bär räumt auf",
            provider = "spotify",
            contentUri = "spotify:album:1",
        ),
        album(
            artist = "Nico",
            name = "Long Drive Home",
            provider = "spotify",
            contentUri = "spotify:playlist:2",
            contentType = LibraryContentType.PLAYLIST,
        ),
        album(
            artist = "Spotify",
            name = "Liked Songs",
            provider = "spotify",
            contentUri = "spotify:collection:tracks",
            contentType = LibraryContentType.COLLECTION,
        ),
    )

    /** What such a box reports for itself; the labels are the box's own English. */
    val mixedSources = listOf(
        LibrarySource(id = "mpd", label = "Local"),
        LibrarySource(id = "spotify", label = "Spotify"),
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
     * Two with artwork and one without, which is the mix worth a golden: a cover from the box
     * has to render *and* a favourite it has no artwork for has to get its stand-in. The file
     * names are a stand-in too, but not an inert one — [FakeCoverArt] derives a different
     * picture from each, so a cover shown against the wrong title is something the golden can
     * fail on.
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
