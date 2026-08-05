package app.coilforphoniebox.transport

import android.util.Log
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.SleepTimerStatus
import app.coilforphoniebox.domain.model.VolumeStatus
import app.coilforphoniebox.domain.repository.BoxRepository
import app.coilforphoniebox.transport.di.TransportScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one live [PhonieboxSession] and rebuilds it when the active box changes.
 *
 * The connection is reference counted rather than tied to the process: the UI holds a
 * reference while it is on screen, the media service holds one while it runs. At zero
 * the sockets close, which is what makes "only while the app is open" mean what it
 * says (§8.3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ConnectionManager @Inject constructor(
    private val boxes: BoxRepository,
    @TransportScope private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val _session = MutableStateFlow<PhonieboxSession?>(null)

    private var owners = 0
    private var latestBox: Box? = null

    /** Identity of a *connection*, so a `lastSeenAt` write does not rebuild sockets. */
    private val Box.connectionKey: String get() = "$id|$host|$rpcPort|$pubPort"
    private var currentKey: String? = null

    val activeBox: StateFlow<Box?> =
        boxes.activeBox.stateIn(scope, SharingStarted.Eagerly, null)

    val connection: StateFlow<ConnectionState> = _session
        .flatMapLatest { it?.connection ?: flowOf(ConnectionState.DISCONNECTED) }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.DISCONNECTED)

    val status: StateFlow<PlayerStatus> = _session
        .flatMapLatest { it?.status ?: flowOf(PlayerStatus.Idle) }
        .stateIn(scope, SharingStarted.Eagerly, PlayerStatus.Idle)

    val volume: StateFlow<VolumeStatus> = _session
        .flatMapLatest { it?.volume ?: flowOf(VolumeStatus.Unknown) }
        .stateIn(scope, SharingStarted.Eagerly, VolumeStatus.Unknown)

    val boxVersion: StateFlow<String?> = _session
        .flatMapLatest { it?.version ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** A timer belongs to a box, so switching box drops back to "no timer". */
    val sleepTimer: StateFlow<SleepTimerStatus> = _session
        .flatMapLatest { it?.sleepTimer ?: flowOf(SleepTimerStatus.Off) }
        .stateIn(scope, SharingStarted.Eagerly, SleepTimerStatus.Off)

    init {
        scope.launch {
            boxes.activeBox.collect { box ->
                lock.withLock {
                    latestBox = box
                    reconcile()
                }
            }
        }

        // A box that answers is a box that was seen; used by the switcher's reachability
        // dots and by nothing that would loop back into a rebuild.
        scope.launch {
            connection.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    latestBox?.let { boxes.markSeen(it.id) }
                }
            }
        }
    }

    /** Called by a screen or the media service that needs the box connected. */
    fun acquire() {
        scope.launch {
            lock.withLock {
                owners++
                reconcile()
            }
        }
    }

    fun release() {
        scope.launch {
            lock.withLock {
                owners = (owners - 1).coerceAtLeast(0)
                reconcile()
            }
        }
    }

    private fun reconcile() {
        val wanted = if (owners > 0) latestBox else null
        val wantedKey = wanted?.connectionKey
        if (wantedKey == currentKey) return

        _session.value?.let {
            Log.i(TAG, "Closing session for ${it.box.displayName}")
            it.close()
        }
        _session.value = wanted?.let {
            Log.i(TAG, "Opening session for ${it.displayName} (${it.host})")
            PhonieboxSession(it)
        }
        currentKey = wantedKey
    }

    /** Sends [command] to the active box. */
    suspend fun call(command: PhonieboxCommand): Result<JsonElement> =
        _session.value?.call(command) ?: Result.failure(NotConnectedException())

    /**
     * Sends [command] to a named box, whether or not it is the active one.
     *
     * A home screen shortcut has to reach the box it was created for (§7.3), so when
     * that is not the active box this opens a socket, sends one command and closes it
     * again — no session, no subscription, no lasting state.
     */
    suspend fun callOn(boxId: String, command: PhonieboxCommand): Result<JsonElement> {
        _session.value?.takeIf { it.box.id == boxId }?.let { return it.call(command) }

        val box = boxes.box(boxId) ?: return Result.failure(NotConnectedException())
        return ZmqRpcClient(box.host, box.rpcPort).use { client -> client.call(command) }
    }

    /** Current status of the active box without collecting the flow. */
    fun currentStatus(): PlayerStatus = status.value

    fun currentVolume(): VolumeStatus = volume.value

    fun currentSleepTimer(): SleepTimerStatus = sleepTimer.value

    /** Asks the active box for its timer state; no session means nothing to ask. */
    suspend fun refreshSleepTimer(): Result<Unit> =
        _session.value?.refreshSleepTimer() ?: Result.failure(NotConnectedException())

    private companion object {
        const val TAG = "CoilConnection"
    }
}
