package app.coilforphoniebox.screenshot

import app.coilforphoniebox.domain.model.AppSettings
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.ConnectionTestResult
import app.coilforphoniebox.domain.model.Favorite
import app.coilforphoniebox.domain.model.FavoritesLayout
import app.coilforphoniebox.domain.model.FolderContent
import app.coilforphoniebox.domain.model.LibraryAlbum
import app.coilforphoniebox.domain.model.LibraryIndexResult
import app.coilforphoniebox.domain.model.LibraryIndexState
import app.coilforphoniebox.domain.model.JumpOutcome
import app.coilforphoniebox.domain.model.LibrarySearchResults
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.QueueEntry
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.SessionMode
import app.coilforphoniebox.domain.model.SleepTimerStatus
import app.coilforphoniebox.domain.model.ThemeMode
import app.coilforphoniebox.domain.model.VolumeStatus
import app.coilforphoniebox.domain.repository.BackupRepository
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.domain.repository.FavoriteRepository
import app.coilforphoniebox.domain.repository.LibraryRepository
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Repositories that answer from constructor arguments instead of from a box.
 *
 * A screenshot has to be the same picture every time it is taken, which rules out the real
 * implementations twice over: they need a Phoniebox on the network, and what they return
 * depends on what that box happens to be doing. These take the screen's state as data, so a
 * test names the state it wants and the golden shows exactly that.
 *
 * Commands succeed silently. A screenshot captures one moment and never presses a button, so
 * there is nothing here to record — a fake that verified calls would be a mock, and the
 * behaviour those calls have belongs in the ViewModel's own unit tests.
 */
class FakePlayerRepository(
    status: PlayerStatus = PlayerStatus.Idle,
    volume: VolumeStatus = VolumeStatus(level = 42, maxLevel = 100),
    connection: ConnectionState = ConnectionState.CONNECTED,
    coverUrl: String? = null,
    /**
     * A golden is a settled moment, so nothing is waiting on the box by default. Set this to
     * capture the placeholder a screen shows *while* a cover is being looked up, which is a
     * different picture from the stand-in artwork it settles on when there is none.
     */
    coverPending: Boolean = false,
    sleepTimer: SleepTimerStatus = SleepTimerStatus.Off,
    boxVersion: String? = "future3/main",
    private val coverFile: String? = null,
    /**
     * What the box has queued. Empty by default, which is also what the player screen shows for
     * it: with no queue there is no playlist button, so a golden that does not name one captures
     * the same picture it always did.
     */
    queue: List<QueueEntry> = emptyList(),
) : PlayerRepository {
    override val status = MutableStateFlow(status)
    override val volume = MutableStateFlow(volume)
    override val connectionState = MutableStateFlow(connection)
    override val boxVersion = MutableStateFlow(boxVersion)
    override val coverUrl = MutableStateFlow(coverUrl)
    override val coverPending = MutableStateFlow(coverPending)
    override val sleepTimer = MutableStateFlow(sleepTimer)
    override val queue = MutableStateFlow(queue)
    override val queueLoading = MutableStateFlow(false)

    override fun currentCoverFile(): String? = coverFile

    override suspend fun play(): Result<Unit> = Result.success(Unit)
    override suspend fun pause(): Result<Unit> = Result.success(Unit)
    override suspend fun toggle(): Result<Unit> = Result.success(Unit)
    override suspend fun next(): Result<Unit> = Result.success(Unit)
    override suspend fun previous(): Result<Unit> = Result.success(Unit)
    override suspend fun seekTo(positionSeconds: Double): Result<Unit> = Result.success(Unit)
    override suspend fun setShuffle(enabled: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun setRepeat(mode: RepeatMode): Result<Unit> = Result.success(Unit)
    override suspend fun setVolume(level: Int): Result<Unit> = Result.success(Unit)
    override suspend fun changeVolume(step: Int): Result<Unit> = Result.success(Unit)
    override suspend fun toggleMute(): Result<Unit> = Result.success(Unit)

    override suspend fun setMuted(muted: Boolean): Result<Unit> = Result.success(Unit)
    override suspend fun startSleepTimer(minutes: Int): Result<Unit> = Result.success(Unit)
    override suspend fun cancelSleepTimer(): Result<Unit> = Result.success(Unit)
    override suspend fun refreshSleepTimer(): Result<Unit> = Result.success(Unit)
    override suspend fun refreshQueue(): Result<Unit> = Result.success(Unit)

    /**
     * Arrives immediately, without moving [status].
     *
     * A golden is one settled moment, so there is nothing here to simulate — and the walk this
     * stands in for is the repository's own business, tested where the stepping lives rather
     * than through a screenshot.
     */
    override suspend fun playAt(position: Int): Result<JumpOutcome> =
        Result.success(JumpOutcome.Arrived)

    override suspend fun play(target: PlayTarget): Result<Unit> = Result.success(Unit)
    override suspend fun playOn(boxId: String, target: PlayTarget): Result<Unit> = Result.success(Unit)
}

class FakeBoxRepository(
    boxes: List<Box> = listOf(Fixtures.livingRoom),
    active: Box? = boxes.firstOrNull(),
) : BoxRepository {
    override val boxes = MutableStateFlow(boxes)
    override val activeBox = MutableStateFlow(active)

    override suspend fun box(boxId: String): Box? = boxes.value.firstOrNull { it.id == boxId }

    override suspend fun add(displayName: String, host: String, rpcPort: Int, pubPort: Int): Box {
        val box = Box(
            id = "box-${boxes.value.size + 1}",
            displayName = displayName,
            host = host,
            rpcPort = rpcPort,
            pubPort = pubPort,
            addedAt = Fixtures.NOW,
        )
        boxes.value = boxes.value + box
        return box
    }

    override suspend fun update(box: Box) {
        boxes.value = boxes.value.map { if (it.id == box.id) box else it }
    }

    override suspend fun delete(boxId: String) {
        boxes.value = boxes.value.filterNot { it.id == boxId }
    }

    override suspend fun setActive(boxId: String) {
        activeBox.value = box(boxId)
    }

    override suspend fun markSeen(boxId: String) = Unit

    override suspend fun testConnection(host: String, rpcPort: Int): ConnectionTestResult =
        ConnectionTestResult.Reachable(version = "future3/main")
}

class FakeFavoriteRepository(favorites: List<Favorite> = emptyList()) : FavoriteRepository {
    /** Public so an injected instance can be given different favourites before composing. */
    val all = MutableStateFlow(favorites)

    override fun favorites(boxId: String): Flow<List<Favorite>> =
        all.map { list -> list.filter { it.boxId == boxId }.sortedBy { it.sortIndex } }

    override fun mostLaunched(boxId: String, limit: Int): Flow<List<Favorite>> =
        all.map { list -> list.filter { it.boxId == boxId }.sortedByDescending { it.launchCount }.take(limit) }

    override suspend fun favorite(id: Long): Favorite? = all.value.firstOrNull { it.id == id }

    override suspend fun matching(boxId: String, target: PlayTarget): Favorite? =
        all.value.firstOrNull { it.boxId == boxId && it.toPlayTarget() == target }

    override suspend fun add(favorite: Favorite): Long {
        val id = (all.value.maxOfOrNull { it.id } ?: 0L) + 1
        all.value = all.value + favorite.copy(id = id)
        return id
    }

    override suspend fun remove(id: Long) {
        all.value = all.value.filterNot { it.id == id }
    }

    override suspend fun rename(id: Long, label: String) {
        all.value = all.value.map { if (it.id == id) it.copy(label = label) else it }
    }

    override suspend fun reorder(ids: List<Long>) = Unit
    override suspend fun recordLaunch(id: Long) = Unit
    override suspend fun setPinned(id: Long, pinned: Boolean) = Unit

    override suspend fun setCover(id: Long, coverFile: String) {
        all.value = all.value.map { if (it.id == id) it.copy(coverFile = coverFile) else it }
    }
}

/**
 * [folders] is keyed by path, so a test can hand in as much of a tree as the screen it is
 * capturing actually walks. A path that is not in the map is an empty, never-fetched level —
 * which is what the app shows before a box has been reached, and a state worth a golden.
 */
class FakeLibraryRepository(
    folders: Map<String, FolderContent> = emptyMap(),
    albums: List<LibraryAlbum> = emptyList(),
    cachedAt: Long? = Fixtures.NOW,
    results: LibrarySearchResults = LibrarySearchResults(),
) : LibraryRepository {
    // Held as flows rather than constructor values so a test that gets this injected can swap
    // the library out before composing — which is how the store assets get their own content
    // without a second Hilt module.
    val allFolders = MutableStateFlow(folders)
    val allAlbums = MutableStateFlow(albums)
    val cachedAt = MutableStateFlow(cachedAt)
    val results = MutableStateFlow(results)

    override val indexState = MutableStateFlow(LibraryIndexState())

    override fun folderContent(boxId: String, path: String): Flow<FolderContent> =
        allFolders.map { it[path] ?: FolderContent(path) }

    override suspend fun refreshFolder(boxId: String, path: String): Result<Unit> = Result.success(Unit)

    override fun albums(boxId: String): Flow<List<LibraryAlbum>> = allAlbums

    override fun albumsCachedAt(boxId: String): Flow<Long?> = cachedAt

    override suspend fun refreshAlbums(boxId: String): Result<Unit> = Result.success(Unit)

    override suspend fun ensureAlbumCover(boxId: String, albumArtist: String, album: String) = Unit

    /**
     * Null, deliberately: a golden has to be the same picture every run, and a cover that
     * appeared as the result of a lookup would depend on when the lookup finished. A
     * favourite that is meant to show artwork carries its `coverFile` from the start.
     */
    override suspend fun coverFileFor(boxId: String, target: PlayTarget): String? = null

    override suspend fun rescanBoxLibrary(boxId: String): Result<Unit> = Result.success(Unit)

    override suspend fun refreshIfStaleAndIdle(boxId: String) = Unit

    override fun search(boxId: String, query: String): Flow<LibrarySearchResults> =
        results.map { if (query.isBlank()) LibrarySearchResults() else it.copy(query = query) }

    override suspend fun indexLibrary(boxId: String): LibraryIndexResult =
        LibraryIndexResult.Finished(foldersScanned = 0, stoppedAtCap = false)
}

/** Export and import never run in a screenshot; settings only needs the repository to exist. */
class FakeBackupRepository : BackupRepository {
    override suspend fun export(): String = "{}"
    override suspend fun importFrom(content: String): Result<Unit> = Result.success(Unit)
}

class FakeSettingsRepository(settings: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(settings)
    override val settings: StateFlow<AppSettings> = state

    override suspend fun current(): AppSettings = state.value

    override suspend fun setThemeMode(mode: ThemeMode) {
        state.value = state.value.copy(themeMode = mode)
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        state.value = state.value.copy(dynamicColor = enabled)
    }

    override suspend fun setSessionMode(mode: SessionMode) {
        state.value = state.value.copy(sessionMode = mode)
    }

    override suspend fun setFavoritesLayout(layout: FavoritesLayout) {
        state.value = state.value.copy(favoritesLayout = layout)
    }

    override suspend fun setOnboardingComplete(complete: Boolean) {
        state.value = state.value.copy(onboardingComplete = complete)
    }
}
