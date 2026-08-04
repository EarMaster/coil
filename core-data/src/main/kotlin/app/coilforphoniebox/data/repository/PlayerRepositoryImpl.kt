package app.coilforphoniebox.data.repository

import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.PlayTarget
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.RepeatMode
import app.coilforphoniebox.domain.model.VolumeStatus
import app.coilforphoniebox.domain.repository.PlayerRepository
import app.coilforphoniebox.transport.Commands
import app.coilforphoniebox.transport.ConnectionManager
import app.coilforphoniebox.transport.LibraryParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlayerRepositoryImpl @Inject constructor(
    private val transport: ConnectionManager,
) : PlayerRepository {

    override val status: Flow<PlayerStatus> = transport.status
    override val volume: Flow<VolumeStatus> = transport.volume
    override val connectionState: Flow<ConnectionState> = transport.connection
    override val boxVersion: Flow<String?> = transport.boxVersion

    /** Keyed on box and song, because the same file name means different art per box. */
    private val coverFileCache = ConcurrentHashMap<String, String>()

    /**
     * Cover art is not published: the box hands back a file name for the current song
     * and the image itself comes over HTTP from its web server (§5). One RPC per song
     * change, not per status message — the flow only reacts to [PlayerStatus.file].
     */
    override val coverUrl: Flow<String?> = combine(
        transport.status.map { it.file }.distinctUntilChanged(),
        transport.activeBox,
    ) { file, box -> file to box }
        .mapLatest { (file, box) ->
            if (file == null || box == null) return@mapLatest null
            val key = "${box.id}|$file"
            val coverFile = coverFileCache[key] ?: transport
                .call(Commands.singleCoverArt(file))
                .getOrNull()
                ?.let { LibraryParser.coverFile(it) }
                ?.also { coverFileCache[key] = it }
            coverFile?.let { box.coverUrl(it) }
        }
        .distinctUntilChanged()

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

    override suspend fun play(target: PlayTarget): Result<Unit> =
        transport.call(Commands.play(target)).unit()

    override suspend fun playOn(boxId: String, target: PlayTarget): Result<Unit> =
        transport.callOn(boxId, Commands.play(target)).unit()

    private fun Result<JsonElement>.unit(): Result<Unit> = map { }
}
