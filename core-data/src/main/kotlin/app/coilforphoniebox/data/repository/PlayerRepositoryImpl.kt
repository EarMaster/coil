package app.coilforphoniebox.data.repository

import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.JumpOutcome
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.QueueEntry
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.SleepTimerStatus
import app.coilforphoniebox.domain.model.VolumeStatus
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.transport.Commands
import app.coilforphoniebox.transport.ConnectionManager
import app.coilforphoniebox.transport.LibraryParser
import app.coilforphoniebox.transport.NotConnectedException
import app.coilforphoniebox.transport.QueueParser
import app.coilforphoniebox.transport.RpcErrorException
import app.coilforphoniebox.transport.UnexpectedPayloadException
import app.coilforphoniebox.transport.di.TransportScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

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
     * The same cover as [_coverUrl], as the bare file name.
     *
     * Kept beside the URL rather than derived from it: a favourite stores the file name, and
     * picking it back out of a URL would make [Box.coverUrl] a format two places have to
     * agree on.
     */
    private val _coverFile = MutableStateFlow<String?>(null)

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

    /**
     * Set for as long as a lookup is outstanding, so a null [coverUrl] can be read as "no
     * artwork" rather than "not yet". Cleared whether the lookup found something or not —
     * what the UI is waiting for is the *answer*, not a cover.
     */
    private val _coverPending = MutableStateFlow(false)

    override val coverPending: StateFlow<Boolean> = _coverPending.asStateFlow()

    override fun currentCoverFile(): String? = _coverFile.value

    private val _queue = MutableStateFlow<List<QueueEntry>>(emptyList())

    override val queue: StateFlow<List<QueueEntry>> = _queue.asStateFlow()

    private val _queueLoading = MutableStateFlow(false)

    override val queueLoading: StateFlow<Boolean> = _queueLoading.asStateFlow()

    /**
     * One queue fetch at a time. Two overlapping `playlistinfo` calls would put two library-sized
     * requests on the socket the box shares with its card reader, for one answer.
     */
    private val queueFetch = Mutex()

    /**
     * Boxes that have been asked to `play(pos=…)`, and whether they took it.
     *
     * Kept in memory and keyed by box, never persisted: a box gets its software updated, and a
     * stored "no" would outlive the reason for it. Absent means "not asked yet".
     */
    private val acceptsJump = ConcurrentHashMap<String, Boolean>()

    init {
        scope.launch { resolveCoversForCurrentSong() }
        scope.launch { resolveQueueWhenItChanges() }
    }

    /** What makes one queue a different queue from another. */
    private data class QueueTrigger(
        val boxId: String?,
        val connected: Boolean,
        val length: Int,
        val file: String?,
    )

    /**
     * Keeps [queue] in step with the box, asking `playlistinfo` **once per queue change**.
     *
     * The queue is not published, so the only way to know it changed is to notice that
     * `playerstatus` no longer fits the cached one — a different length, or a playing file that
     * is not in it. Both mean a new `play_folder`/`play_album`/card tap; a plain track change
     * fits the cached queue and costs nothing. An album of twenty tracks is therefore one RPC
     * rather than twenty, which is what makes this safe on the socket the box shares with its
     * card reader (§6).
     */
    private suspend fun resolveQueueWhenItChanges() {
        var loadedFor: String? = null

        combine(
            transport.activeBox.map { it?.id },
            transport.connection.map { it == ConnectionState.CONNECTED },
            transport.status.map { it.playlistLength to it.file },
        ) { boxId, connected, queueShape ->
            QueueTrigger(boxId, connected, queueShape.first, queueShape.second)
        }
            .distinctUntilChanged()
            // A change while a fetch is in flight abandons it: the answer would describe a
            // queue that is no longer the current one.
            .collectLatest { trigger ->
                // A queue belongs to a box and to a live connection. Showing the one from
                // before a switch would put another box's tracks on screen.
                if (trigger.boxId == null || !trigger.connected || trigger.length == 0) {
                    _queue.value = emptyList()
                    _queueLoading.value = false
                    loadedFor = null
                    return@collectLatest
                }
                if (trigger.boxId != loadedFor) {
                    _queue.value = emptyList()
                    loadedFor = null
                }
                if (queueStillFits(trigger)) return@collectLatest

                // Said before the wait, not after: from here on an empty queue means "the
                // answer is coming", which is what stops the sheet reading it as a failure.
                _queueLoading.value = true
                try {
                    // A queue change is usually a card tap, and the box is still finishing the
                    // card handling on this same sequential socket. Racing it is precisely what
                    // §6 rules out, so let the status settle first.
                    delay(QUEUE_SETTLE_MILLIS)
                    // A failure leaves the last known queue in place and does not retry here:
                    // the next track change re-runs this, a bounded retry rather than a loop.
                    if (fetchQueue().isSuccess) loadedFor = trigger.boxId
                } finally {
                    // Also on the way out of a `collectLatest` cancellation, or the flag would
                    // be stuck true for a queue nobody is fetching any more.
                    _queueLoading.value = false
                }
            }
    }

    /** Reads the queue and publishes it. */
    private suspend fun fetchQueue(): Result<Unit> = queueFetch.withLock {
        transport.call(Commands.playlistInfo).map { payload ->
            _queue.value = QueueParser.queue(payload)
        }
    }

    override suspend fun refreshQueue(): Result<Unit> {
        _queueLoading.value = true
        return try {
            fetchQueue()
        } finally {
            _queueLoading.value = false
        }
    }

    /** Whether the cached queue can still be the one the box is playing from. */
    private fun queueStillFits(trigger: QueueTrigger): Boolean {
        val cached = _queue.value
        if (cached.size != trigger.length) return false
        // A stopped box reports no file; the queue it holds is still the one it had.
        val file = trigger.file ?: return true
        return cached.any { it.url == file }
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
                    _coverFile.value = null
                    _coverUrl.value = null
                    lastKey = key
                }
                // An idle player is not a lookup in progress. Saying otherwise would leave
                // the screen on a placeholder waiting for an answer that is not coming.
                _coverPending.value = file != null && box != null
                if (file != null && box != null) {
                    val coverFile = resolveCoverFile(file, box)
                    _coverFile.value = coverFile
                    _coverUrl.value = coverFile?.let { box.coverUrl(it) }
                    // Answered, with or without a cover — either way the wait is over. A
                    // lookup abandoned by `collectLatest` never gets here, which is correct:
                    // the song that replaced it sets this again on its own way through.
                    _coverPending.value = false
                }
            }
    }

    /**
     * The box answers the first request for a song with `CACHE_PENDING` and extracts the
     * artwork on a worker thread, so a single request never returns a usable name. This
     * asks again a few times before giving up.
     */
    private suspend fun resolveCoverFile(file: String, box: Box): String? {
        val key = "${box.id}|$file"
        if (key in songsWithoutArt) return null
        resolvedCovers[key]?.let { return it }

        repeat(COVER_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(COVER_RETRY_MILLIS)

            val payload = transport.call(Commands.singleCoverArt(file)).getOrNull() ?: return null
            when (val art = LibraryParser.coverArt(payload)) {
                is LibraryParser.CoverArt.Available -> {
                    resolvedCovers[key] = art.fileName
                    return art.fileName
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

    /**
     * Two routes to the same place, tried in order of preference.
     *
     * The box has no command for this (see [Commands.playAt]), so the first attempt is a probe:
     * `play(pos=…)` either works or is rejected by the plugin's own signature *before its body
     * runs*, which makes a rejected attempt free of consequences. What the answer must not be
     * confused with is a timeout — a box that is switched off says nothing about whether its
     * software has the command, so only an `error` reply is taken as "no".
     */
    override suspend fun playAt(position: Int): Result<JumpOutcome> {
        val boxId = transport.activeBox.value?.id
            ?: return Result.failure(NotConnectedException())
        val target = position.coerceAtLeast(0)

        if (acceptsJump[boxId] != false) {
            val attempt = transport.call(Commands.playAt(target))
            attempt.onSuccess {
                acceptsJump[boxId] = true
                return Result.success(JumpOutcome.Arrived)
            }
            val failure = attempt.exceptionOrNull()
            if (failure !is RpcErrorException) {
                // Unreachable, torn down or too slow. The capability is still unknown.
                return Result.failure(failure ?: NotConnectedException())
            }
            acceptsJump[boxId] = false
        }

        return stepTo(target)
    }

    /**
     * Walks the queue to [target] with `next`/`prev`, for a box that cannot jump.
     *
     * The queue survives this, which is the whole point of not reaching for `play_single`: it
     * would replace the queue with the one track and leave the box silent when it ended. The
     * cost is that it takes a moment and that the caller has to be told when it fell short.
     */
    private suspend fun stepTo(target: Int): Result<JumpOutcome> {
        val start = transport.currentStatus()
        val from = start.playlistPosition
            ?: return Result.failure(UnexpectedPayloadException(Commands.next.name))

        if (from == target) return Result.success(JumpOutcome.Arrived)
        // Stepping past the end runs the box's own `end_of_playlist_next_action`, which is not
        // ours to trigger.
        if (target > start.playlistLength - 1) return Result.success(JumpOutcome.Incomplete(from))
        // MPD's `next` with `random` enabled goes to a *random* song rather than the following
        // one, so no number of steps arrives anywhere in particular. Nothing is sent.
        if (start.shuffle) return Result.success(JumpOutcome.BlockedByShuffle)

        val wasPlaying = start.isPlaying
        // From `pause`, the box's `next` takes `mpd_client.next()` rather than its stopped-state
        // branch, and MPD may hold the pause across it — which makes the whole walk silent. If
        // it does not hold, this has cost one command and changed nothing else.
        if (wasPlaying) transport.call(Commands.pause)

        try {
            // Each call waits for its reply, so by the last one the box has already moved; the
            // loop below is mostly confirmation. A step that times out is *not* resent — that
            // is what `retryable = false` on `next` means — and shows up instead as a position
            // that did not move.
            val step = stepToward(target, from)
            val gap = abs(target - from)
            var sent = 0
            while (sent < gap && transport.call(step).isSuccess) sent++

            var landed = awaitSettled(target)
            var corrections = 0
            while (landed != target && corrections < MAX_STEP_CORRECTIONS) {
                if (transport.call(stepToward(target, landed)).isFailure) break
                landed = awaitSettled(target)
                corrections++
            }

            return Result.success(
                if (landed == target) JumpOutcome.Arrived else JumpOutcome.Incomplete(landed),
            )
        } finally {
            // Including when the walk was cancelled half way: leaving the box paused because
            // the user closed the sheet would be a worse outcome than not arriving.
            if (wasPlaying) withContext(NonCancellable) { transport.call(Commands.play) }
        }
    }

    private fun stepToward(target: Int, current: Int) =
        if (target > current) Commands.next else Commands.previous

    /**
     * Waits until the box reports [target] or stops moving, and returns where it ended up.
     *
     * Both halves are needed. Waiting only for the target hangs forever on a step that went
     * missing, and reading the position after a fixed delay would "correct" a walk that was
     * still arriving — overshooting by however many steps were still in flight. The box
     * publishes about four times a second, so a few quiet ticks mean it has finished moving
     * rather than merely not having published yet. Nothing here touches the box: this reads the
     * status already arriving over PubSub.
     */
    private suspend fun awaitSettled(target: Int): Int {
        var position = transport.currentStatus().playlistPosition ?: -1
        var quietTicks = 0

        repeat(STEP_TICK_LIMIT) {
            delay(STEP_TICK_MILLIS)
            val reported = transport.currentStatus().playlistPosition ?: position
            when {
                reported == target -> return target
                reported != position -> {
                    position = reported
                    quietTicks = 0
                }

                else -> if (++quietTicks >= STEP_QUIET_TICKS) return position
            }
        }
        return position
    }

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

        /**
         * Long enough for a card read to finish on the box's sequential RPC socket before the
         * queue is asked for, short enough that the sheet does not feel stuck opening.
         */
        const val QUEUE_SETTLE_MILLIS = 1_000L

        /** Roughly the box's own publish interval, so a step is seen on the next tick. */
        const val STEP_TICK_MILLIS = 250L

        /** Four quiet ticks — a second of no movement — mean the walk has finished arriving. */
        const val STEP_QUIET_TICKS = 4

        /** Ceiling on one wait, so a box that stopped reporting cannot hang the walk. */
        const val STEP_TICK_LIMIT = 60

        /** A correction covers a lost or doubled step; a run of them means give up and say so. */
        const val MAX_STEP_CORRECTIONS = 3
    }
}
