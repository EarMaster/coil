package app.coilforphoniebox.data.repository

import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.SleepTimerStatus
import app.coilforphoniebox.domain.model.VolumeStatus
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.transport.Commands
import app.coilforphoniebox.transport.ConnectionManager
import app.coilforphoniebox.transport.LibraryParser
import app.coilforphoniebox.transport.di.TransportScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live player state and playback commands, both straight from the active box.
 *
 * Nothing here touches the database: this is the one class of data that is always live
 * and never persisted (§6.2).
 */
@Singleton
class PlayerRepositoryImpl @Inject constructor(
    private val transport: ConnectionManager,
    @TransportScope private val scope: CoroutineScope,
) : PlayerRepository {

    override val status: Flow<PlayerStatus> = transport.status
    override val volume: Flow<VolumeStatus> = transport.volume
    override val connectionState: Flow<ConnectionState> = transport.connection
    override val boxVersion: Flow<String?> = transport.boxVersion
    override val sleepTimer: Flow<SleepTimerStatus> = transport.sleepTimer

    /**
     * Resolved covers, keyed on box and song because the same file name means different
     * art on a different box.
     */
    private val resolvedCovers = ConcurrentHashMap<String, String>()

    /** Songs the box has told us have no artwork, so they are not asked about again. */
    private val songsWithoutArt: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val _coverUrl = MutableStateFlow<String?>(null)

    /**
     * Deliberately a [StateFlow] with a value from the start, resolved in the background,
     * rather than a flow that maps the current song to an RPC.
     *
     * Every screen combines this with the rest of the player state, and `combine` emits
     * nothing until *all* of its inputs have emitted once. A cover flow whose first value
     * needs a network round trip therefore freezes the whole player — title, progress and
     * controls included — for as long as the lookup takes, or forever if it keeps being
     * restarted. Cover art is the least important thing on the screen; it must never be
     * able to hold up the rest.
     */
    override val coverUrl: StateFlow<String?> = _coverUrl.asStateFlow()

    init {
        scope.launch { resolveCoversForCurrentSong() }
    }

    private suspend fun resolveCoversForCurrentSong() {
        var lastKey: String? = null

        combine(
            transport.status.map { it.file }.distinctUntilChanged(),
            transport.activeBox,
        ) { file, box -> file to box }
            // Only the newest song matters; a lookup for the previous one is abandoned.
            .collectLatest { (file, box) ->
                val key = cacheKey(file, box)
                if (key != lastKey) {
                    // Showing the previous song's artwork would be worse than showing none.
                    _coverUrl.value = null
                    lastKey = key
                }
                if (file != null && box != null) {
                    _coverUrl.value = resolveCover(file, box)
                }
            }
    }

    /**
     * The box answers the first request for a song with `CACHE_PENDING` and extracts the
     * artwork on a worker thread, so a single request never returns a usable name. This
     * asks again a few times before giving up.
     */
    private suspend fun resolveCover(file: String, box: Box): String? {
        val key = "${box.id}|$file"
        if (key in songsWithoutArt) return null
        resolvedCovers[key]?.let { return box.coverUrl(it) }

        repeat(COVER_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(COVER_RETRY_MILLIS)

            val payload = transport.call(Commands.singleCoverArt(file)).getOrNull() ?: return null
            when (val art = LibraryParser.coverArt(payload)) {
                is LibraryParser.CoverArt.Available -> {
                    resolvedCovers[key] = art.fileName
                    return box.coverUrl(art.fileName)
                }

                LibraryParser.CoverArt.Missing -> {
                    songsWithoutArt += key
                    return null
                }

                // Still extracting; fall through to the next attempt.
                LibraryParser.CoverArt.Pending -> Unit
            }
        }
        // Not cached as missing: the next song change or reconnect may find it ready.
        return null
    }

    private fun cacheKey(file: String?, box: Box?): String? =
        if (file == null || box == null) null else "${box.id}|$file"

    override suspend fun play(): Result<Unit> = transport.call(Commands.play).unit()

    override suspend fun pause(): Result<Unit> = transport.call(Commands.pause).unit()

    override suspend fun toggle(): Result<Unit> = transport.call(Commands.toggle).unit()

    override suspend fun next(): Result<Unit> = transport.call(Commands.next).unit()

    override suspend fun previous(): Result<Unit> = transport.call(Commands.previous).unit()

    override suspend fun seekTo(positionSeconds: Double): Result<Unit> =
        transport.call(Commands.seek(positionSeconds.coerceAtLeast(0.0))).unit()

    override suspend fun setShuffle(enabled: Boolean): Result<Unit> =
        transport.call(Commands.shuffle(enabled)).unit()

    override suspend fun setRepeat(mode: RepeatMode): Result<Unit> =
        transport.call(Commands.repeat(mode)).unit()

    override suspend fun setVolume(level: Int): Result<Unit> {
        // The box clamps to its soft maximum anyway; clamping here keeps the slider and
        // the volume keys from ever asking for something it will silently refuse.
        val max = transport.currentVolume().maxLevel
        return transport.call(Commands.setVolume(level.coerceIn(0, max))).unit()
    }

    override suspend fun changeVolume(step: Int): Result<Unit> =
        transport.call(Commands.changeVolume(step)).unit()

    override suspend fun toggleMute(): Result<Unit> =
        transport.call(Commands.mute(!transport.currentVolume().muted)).unit()

    override suspend fun startSleepTimer(minutes: Int): Result<Unit> {
        // `GenericTimerClass.start` logs "Ignoring start command" and returns when its timer
        // is already alive, so setting 30 minutes over a running 60 would silently keep the
        // 60. Cancel first, and only then start.
        if (transport.currentSleepTimer().running) {
            transport.call(Commands.cancelSleepTimer)
                .onFailure { return Result.failure(it) }
        }
        val seconds = minutes.coerceAtLeast(1) * SECONDS_PER_MINUTE
        return transport.call(Commands.startSleepTimer(seconds)).unit()
    }

    override suspend fun cancelSleepTimer(): Result<Unit> =
        transport.call(Commands.cancelSleepTimer).unit()

    override suspend fun refreshSleepTimer(): Result<Unit> = transport.refreshSleepTimer()

    override suspend fun play(target: PlayTarget): Result<Unit> =
        transport.call(Commands.play(target)).unit()

    override suspend fun playOn(boxId: String, target: PlayTarget): Result<Unit> =
        transport.callOn(boxId, Commands.play(target)).unit()

    private fun Result<JsonElement>.unit(): Result<Unit> = map { }

    private companion object {
        /** Four tries over roughly two seconds, which covers an ordinary extraction. */
        const val COVER_ATTEMPTS = 4
        const val COVER_RETRY_MILLIS = 800L

        /** The timer plugin takes `wait_seconds`; the UI offers whole minutes. */
        const val SECONDS_PER_MINUTE = 60
    }
}
