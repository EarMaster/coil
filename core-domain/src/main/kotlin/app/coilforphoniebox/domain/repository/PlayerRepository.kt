package app.coilforphoniebox.domain.repository

import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.RepeatMode
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

    suspend fun play(target: PlayTarget): Result<Unit>

    /** Start [target] on a specific box, connecting ad hoc if it is not the active one. */
    suspend fun playOn(boxId: String, target: PlayTarget): Result<Unit>
}
