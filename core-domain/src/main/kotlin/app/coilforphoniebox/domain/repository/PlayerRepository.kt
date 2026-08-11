package app.coilforphoniebox.domain.repository

import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.JumpOutcome
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.QueueEntry
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.SleepTimerStatus
import app.coilforphoniebox.domain.model.VolumeStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Live state of the active box. Nothing here is persisted: `playerstatus` and
 * `volume.level` arrive over PubSub roughly four times a second and are always
 * taken fresh from the box (§6.2).
 *
 * Commands return [Result] so the UI can surface a failure; they do not wait for the
 * state to change, because the next PubSub message will correct any optimistic
 * assumption anyway.
 */
interface PlayerRepository {
    val status: Flow<PlayerStatus>
    val volume: Flow<VolumeStatus>
    val connectionState: Flow<ConnectionState>

    /** `core.version` of the active box, for diagnostics and bug reports. */
    val boxVersion: Flow<String?>

    /** Absolute HTTP URL of the current song's cover, or null when there is none. */
    val coverUrl: Flow<String?>

    /**
     * Whether the cover for the playing song is still being resolved.
     *
     * A null [coverUrl] is two different things — "this song has no artwork" and "the answer
     * is not in yet" — and the box makes the second one slow enough to matter: the first
     * request for any song answers `CACHE_PENDING` while it extracts the image on a worker
     * thread, so a cover takes a second or two on the track it is asked about first. The UI
     * needs to tell them apart before it can put stand-in artwork on screen, or every track
     * change would show a stand-in and then replace it.
     */
    val coverPending: Flow<Boolean>

    /**
     * Cover file name of the playing song, if one has already been resolved.
     *
     * The same name [coverUrl] is built from, handed over unwrapped so that saving a
     * favourite can store it. Read once at the moment of the tap rather than exposed as a
     * flow: nothing on screen shows this, and a favourite records the cover it was saved
     * with, not whatever is playing later.
     */
    fun currentCoverFile(): String?

    /**
     * The box's stop-player timer. Its shutdown timers are deliberately not represented
     * here at all — Coil never sends a command that switches the box off (§16).
     */
    val sleepTimer: Flow<SleepTimerStatus>

    /**
     * The box's current queue, in play order, or an empty list when it is not known.
     *
     * A flow rather than a one-shot because the media session consumes it too — but a flow
     * that is **resolved per queue change, never polled**: `playlistinfo` is asked when
     * `playlistLength` moves or the playing file is not in the cached list, which happens on a
     * `play_folder`/`play_album`/card tap and *not* on a track change. An album of twenty
     * tracks therefore costs one RPC, not twenty (§6).
     *
     * It starts empty and stays empty until the first answer arrives, so it can never hold up
     * a screen that combines it with the rest of the player state — the trap [coverUrl] exists
     * to avoid. Every consumer has to cope with an empty or momentarily stale list.
     */
    val queue: StateFlow<List<QueueEntry>>

    /**
     * Whether an answer is on its way, so an empty [queue] can be read as "could not be read"
     * rather than "not yet" — the same distinction [coverPending] draws for artwork, and for the
     * same reason: without it a failed fetch is a spinner that never stops.
     */
    val queueLoading: StateFlow<Boolean>

    /**
     * Asks for the queue again now, for a user who is looking at a list that failed to load.
     *
     * The background resolver handles every ordinary case; this exists so a retry does not have
     * to wait for the next track change.
     */
    suspend fun refreshQueue(): Result<Unit>

    suspend fun play(): Result<Unit>
    suspend fun pause(): Result<Unit>
    suspend fun toggle(): Result<Unit>
    suspend fun next(): Result<Unit>
    suspend fun previous(): Result<Unit>
    suspend fun seekTo(positionSeconds: Double): Result<Unit>

    /**
     * Start playing queue position [position], leaving the rest of the queue in place.
     *
     * **The box does not have a command for this**, so the implementation is a ladder: it
     * tries `play(pos=…)` — which upstream `future3` rejects harmlessly — and otherwise walks
     * the queue with `next`/`prev`. A walk takes a moment and is visible, so callers should
     * show that it is happening; see [JumpOutcome] for what can come back.
     *
     * Suspends until the box has arrived (or the attempt gave up), unlike the fire-and-forget
     * commands above, because "did it get there" is the only useful answer.
     */
    suspend fun playAt(position: Int): Result<JumpOutcome>
    suspend fun setShuffle(enabled: Boolean): Result<Unit>
    suspend fun setRepeat(mode: RepeatMode): Result<Unit>

    suspend fun setVolume(level: Int): Result<Unit>
    suspend fun changeVolume(step: Int): Result<Unit>
    suspend fun toggleMute(): Result<Unit>

    /**
     * Sets the timer that stops playback after [minutes], replacing a running one.
     *
     * The box ignores `start` while its timer is already alive, so changing the duration
     * means cancelling first — which the implementation does, rather than leaving the user
     * wondering why the new duration did not take.
     */
    suspend fun startSleepTimer(minutes: Int): Result<Unit>

    suspend fun cancelSleepTimer(): Result<Unit>

    /**
     * Pulls the timer state once, for when the timer UI opens. Later changes arrive on the
     * published topic, so this is never called on a schedule.
     */
    suspend fun refreshSleepTimer(): Result<Unit>

    suspend fun play(target: PlayTarget): Result<Unit>

    /** Start [target] on a specific box, connecting ad hoc if it is not the active one. */
    suspend fun playOn(boxId: String, target: PlayTarget): Result<Unit>
}
