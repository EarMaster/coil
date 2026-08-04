package app.coilforphoniebox.transport

import android.os.SystemClock
import android.util.Log
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.VolumeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * One live connection to one box: the RPC socket pair and the SUB socket, plus the
 * watchdog that keeps them honest.
 *
 * Exactly one session exists at a time. Switching boxes tears this down and builds a
 * new one, which is cheap because the publisher's last-value cache means the new
 * session's state arrives without a request (§7.1).
 */
class PhonieboxSession(val box: Box) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketLock = Mutex()

    private val _connection = MutableStateFlow(ConnectionState.CONNECTING)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _status = MutableStateFlow(PlayerStatus.Idle)
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    private val _volume = MutableStateFlow(VolumeStatus.Unknown)
    val volume: StateFlow<VolumeStatus> = _volume.asStateFlow()

    private val _version = MutableStateFlow<String?>(null)

    /** `core.version`: never branched on, but included in bug reports (§1). */
    val version: StateFlow<String?> = _version.asStateFlow()

    @Volatile
    private var rpc: ZmqRpcClient = ZmqRpcClient(box.host, box.rpcPort)

    @Volatile
    private var subscriber: ZmqStatusSubscriber = openSubscriber()

    init {
        scope.launch { handshake() }
        scope.launch { watchdog() }
    }

    suspend fun call(command: PhonieboxCommand): Result<JsonElement> =
        rpc.call(command).onSuccess {
            // A successful reply is proof of life, whatever the watchdog thought.
            if (_connection.value != ConnectionState.CONNECTED) {
                _connection.value = ConnectionState.CONNECTED
            }
        }

    /** Pulls the status over RPC. Only for after a rebuilt connection — never polled. */
    suspend fun refreshStatus(): Result<Unit> =
        call(Commands.playerStatus).map { result ->
            (result as? JsonObject)
                ?.let { StatusParser.playerStatus(it, SystemClock.elapsedRealtime()) }
                ?.let { _status.value = it }
            Unit
        }

    private fun openSubscriber() = ZmqStatusSubscriber(
        host = box.host,
        port = box.pubPort,
        topics = ZmqStatusSubscriber.DEFAULT_TOPICS,
        onMessage = ::onPublished,
    )

    /** Called on the subscriber's own thread; every write here is to a StateFlow. */
    private fun onPublished(topic: String, payload: String) {
        when {
            topic == ZmqStatusSubscriber.TOPIC_PLAYER_STATUS ->
                StatusParser.playerStatus(payload, SystemClock.elapsedRealtime())
                    ?.let { _status.value = it }

            topic.startsWith(ZmqStatusSubscriber.TOPIC_VOLUME) ->
                StatusParser.volume(payload, _volume.value)?.let { _volume.value = it }

            topic == ZmqStatusSubscriber.TOPIC_VERSION ->
                StatusParser.versionFromTopic(payload)?.let { _version.value = it }
        }

        if (_connection.value != ConnectionState.CONNECTED) {
            _connection.value = ConnectionState.CONNECTED
        }
    }

    /**
     * No `playerstatus` request here: subscribing is enough. What does need asking for
     * is the soft volume maximum, which is never published and which the media session
     * needs as `deviceVolumeMax` (§8.1).
     */
    private suspend fun handshake() {
        val versionResult = call(Commands.version)
        versionResult
            .onSuccess { payload -> _version.value = StatusParser.version(payload) }
            .onFailure {
                // Nothing published yet either means the box really is not there.
                if (subscriber.lastMessageAtElapsed == 0L) {
                    _connection.value = ConnectionState.DISCONNECTED
                }
            }

        call(Commands.softMaxVolume).onSuccess { payload ->
            StatusParser.softMaxVolume(payload)?.let { max ->
                _volume.value = _volume.value.copy(maxLevel = max)
            }
        }
    }

    /**
     * ZMQ reconnects a dropped TCP connection by itself but never says so, and the box
     * publishes unconditionally at 4 Hz — so silence, and only silence, means
     * something is wrong. This is mandatory rather than optional (§4.3).
     */
    private suspend fun watchdog() {
        var backoffMillis = INITIAL_BACKOFF_MILLIS

        while (scope.isActive) {
            delay(CHECK_INTERVAL_MILLIS)

            val lastMessage = subscriber.lastMessageAtElapsed
            val silentFor = SystemClock.elapsedRealtime() - lastMessage

            if (lastMessage != 0L && silentFor < SILENCE_LIMIT_MILLIS) {
                if (_connection.value != ConnectionState.CONNECTED) {
                    _connection.value = ConnectionState.CONNECTED
                }
                backoffMillis = INITIAL_BACKOFF_MILLIS
                continue
            }

            if (_connection.value == ConnectionState.CONNECTED) {
                _connection.value = ConnectionState.DEGRADED
            }

            if (rpc.call(Commands.version).isSuccess) {
                // Sockets are fine, the publisher just went quiet. Ask once.
                _connection.value = ConnectionState.CONNECTED
                backoffMillis = INITIAL_BACKOFF_MILLIS
                refreshStatus()
                continue
            }

            _connection.value = ConnectionState.DISCONNECTED
            delay(backoffMillis)
            backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
            rebuild()
        }
    }

    private suspend fun rebuild() {
        socketLock.withLock {
            if (!scope.isActive) return
            Log.i(TAG, "Rebuilding sockets for ${box.host}")
            rpc.close()
            subscriber.close()
            _connection.value = ConnectionState.CONNECTING
            rpc = ZmqRpcClient(box.host, box.rpcPort)
            subscriber = openSubscriber()
        }
        handshake()
    }

    override fun close() {
        scope.cancel()
        rpc.close()
        subscriber.close()
        _connection.value = ConnectionState.DISCONNECTED
    }

    private companion object {
        const val TAG = "CoilSession"
        const val CHECK_INTERVAL_MILLIS = 5_000L

        /** 20 s of nothing at all, against an expected four messages a second. */
        const val SILENCE_LIMIT_MILLIS = 20_000L
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
    }
}
