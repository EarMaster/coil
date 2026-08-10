package app.coilforphoniebox.transport

import android.os.SystemClock
import android.util.Log
import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionState
import app.coilforphoniebox.domain.model.PlayerStatus
import app.coilforphoniebox.domain.model.SleepTimerStatus
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

    private val _sleepTimer = MutableStateFlow(SleepTimerStatus.Off)

    /**
     * The stop-player timer. Starts at "off" rather than "unknown": the box publishes this
     * only when it changes, so a box that has not been asked to set one has nothing in the
     * last-value cache — and "no timer" is what that means.
     */
    val sleepTimer: StateFlow<SleepTimerStatus> = _sleepTimer.asStateFlow()

    /**
     * When this session's SUB socket was opened, on the elapsed-realtime clock.
     *
     * The last-value cache is what makes a new session cheap for every other topic, and a trap
     * for this one. What it replays on the timer topic is the message that *set* the timer,
     * carrying the seconds that were left back then and nothing to date it by — so taken at
     * face value it restarts the countdown from the top every time the app comes back to the
     * foreground and the connection is rebuilt. A timer publish arriving this soon after
     * subscribing is therefore read as "there is a timer, go ask what is left of it".
     *
     * Declared above [subscriber] on purpose: [openSubscriber] writes it.
     */
    @Volatile
    private var subscribedAtElapsed: Long = 0L

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

    /**
     * Asks for the timer's state: once per connection, when the timer UI opens, and when the
     * topic says something whose age cannot be established (see [subscribedAtElapsed]). Never
     * on a schedule — the published topic covers every later change.
     */
    suspend fun refreshSleepTimer(): Result<Unit> =
        call(Commands.sleepTimerState).map { result ->
            StatusParser.sleepTimer(result, SystemClock.elapsedRealtime())
                ?.let { _sleepTimer.value = it }
            Unit
        }

    /** Pulls the status over RPC. Only for after a rebuilt connection — never polled. */
    suspend fun refreshStatus(): Result<Unit> =
        call(Commands.playerStatus).map { result ->
            (result as? JsonObject)
                ?.let { StatusParser.playerStatus(it, SystemClock.elapsedRealtime()) }
                ?.let { _status.value = it }
            Unit
        }

    private fun openSubscriber(): ZmqStatusSubscriber {
        subscribedAtElapsed = SystemClock.elapsedRealtime()
        return ZmqStatusSubscriber(
            host = box.host,
            port = box.pubPort,
            topics = ZmqStatusSubscriber.DEFAULT_TOPICS,
            onMessage = ::onPublished,
        )
    }

    /** Called on the subscriber's own thread; every write here is to a StateFlow. */
    private fun onPublished(topic: String, payload: String) {
        when {
            topic == ZmqStatusSubscriber.TOPIC_PLAYER_STATUS -> {
                val parsed = StatusParser.playerStatus(payload, SystemClock.elapsedRealtime())
                if (parsed != null) {
                    _status.value = parsed
                } else {
                    // A silent skip here looks exactly like a frozen player, so say so.
                    Log.w(TAG, "Unparseable playerstatus payload: ${payload.take(200)}")
                }
            }

            topic == ZmqStatusSubscriber.TOPIC_SLEEP_TIMER -> {
                val sinceSubscribed = SystemClock.elapsedRealtime() - subscribedAtElapsed
                if (sinceSubscribed < TIMER_REPLAY_WINDOW_MILLIS) {
                    // A replayed cache entry and a change made this second look identical
                    // (see [subscribedAtElapsed]), so neither is believed: `get_state` answers
                    // both cases with a remaining count that is true now.
                    scope.launch { refreshSleepTimer() }
                } else {
                    StatusParser.sleepTimer(payload, SystemClock.elapsedRealtime())
                        ?.let { _sleepTimer.value = it }
                }
            }

            topic.startsWith(ZmqStatusSubscriber.TOPIC_VOLUME) ->
                StatusParser.volume(payload, _volume.value)?.let { _volume.value = it }

            topic == ZmqStatusSubscriber.TOPIC_VERSION ->
                StatusParser.versionFromTopic(payload)?.let { _version.value = it }

            // What the daemon actually publishes about itself. Used only when no explicit
            // version arrived, and only when it is a plain string worth showing.
            topic == ZmqStatusSubscriber.TOPIC_GIT_STATE && _version.value == null ->
                StatusParser.plainString(payload)?.let { _version.value = it }
        }

        if (_connection.value != ConnectionState.CONNECTED) {
            _connection.value = ConnectionState.CONNECTED
        }
    }

    /**
     * One ping to establish reachability without waiting for the publisher, and one
     * request for the soft volume maximum, which is never published and which the media
     * session needs as `deviceVolumeMax` (§8.1).
     *
     * The ping is `playerstatus`, so its reply doubles as the initial state — the
     * last-value cache would deliver that anyway, this just makes it immediate.
     *
     * The timer is the one piece of state the cache cannot supply, because what it holds is
     * dated (see [subscribedAtElapsed]), so it is asked for outright on every connection. A
     * running countdown then survives the app being sent to the background and brought back,
     * which closes the session and opens a new one.
     */
    private suspend fun handshake() {
        call(Commands.ping)
            .onSuccess { payload ->
                (payload as? JsonObject)
                    ?.let { StatusParser.playerStatus(it, SystemClock.elapsedRealtime()) }
                    ?.let { _status.value = it }
            }
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

        refreshSleepTimer()
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

            if (rpc.call(Commands.ping).isSuccess) {
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

        /**
         * How long a timer publish is treated as possibly a replayed cache entry. The cache is
         * handed over as the subscription is accepted, so this only has to outlast the socket
         * setup — and asking is harmless when it turns out to have been a real change.
         */
        const val TIMER_REPLAY_WINDOW_MILLIS = 2_000L
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
    }
}
