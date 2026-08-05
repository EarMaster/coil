package app.coilforphoniebox.domain.repository

import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.SleepTimerStatus
import app.coilforphoniebox.domain.model.VolumeStatus
import kotlinx.coroutines.flow.Flow

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

    suspend fun play(): Result<Unit>
    suspend fun pause(): Result<Unit>
    suspend fun toggle(): Result<Unit>
    suspend fun next(): Result<Unit>
    suspend fun previous(): Result<Unit>
    suspend fun seekTo(positionSeconds: Double): Result<Unit>
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
