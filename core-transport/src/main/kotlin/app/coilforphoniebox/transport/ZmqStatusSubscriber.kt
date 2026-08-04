package app.coilforphoniebox.transport

import android.os.SystemClock
import android.util.Log
import org.zeromq.SocketType
import org.zeromq.ZContext

/**
 * `SUB` socket on TCP 5558.
 *
 * No polling anywhere in the app: the box polls MPD itself every 250 ms and publishes
 * the result, so the client gets four updates a second without asking. It also keeps
 * a last-value cache, which means subscribing alone yields the complete current state
 * — there is no initial `playerstatus` request on connect (§4.2.1, §4.2.2).
 *
 * That publish happens unconditionally, including while the box is stopped: roughly
 * 345,000 messages a day for a long-running service. The volume is irrelevant, the
 * JSON parsing is not, so identical payloads are dropped here by raw string compare
 * before anything is parsed (§4.2.3).
 */
class ZmqStatusSubscriber(
    private val host: String,
    private val port: Int,
    private val topics: List<String>,
    private val onMessage: (topic: String, payload: String) -> Unit,
) : AutoCloseable {

    private val lastRawPerTopic = HashMap<String, String>()

    /**
     * When the last message arrived, on the elapsed-realtime clock. The watchdog
     * reads this: ZMQ reconnects TCP silently and never reports it upwards, so
     * silence is the only signal that something is wrong (§4.3).
     */
    @Volatile
    var lastMessageAtElapsed: Long = 0L
        private set

    @Volatile
    private var running = true

    init {
        Thread({ socketLoop() }, "coil-sub-$host").apply {
            isDaemon = true
            start()
        }
    }

    private fun socketLoop() {
        try {
            ZContext().use { context ->
                val socket = context.createSocket(SocketType.SUB).apply {
                    linger = 0
                    tcpKeepAlive = 1
                    receiveTimeOut = POLL_MILLIS
                    topics.forEach { subscribe(it.toByteArray()) }
                    connect("tcp://$host:$port")
                }

                while (running) {
                    val topic = socket.recvStr() ?: continue
                    if (!socket.hasReceiveMore()) continue
                    val payload = socket.recvStr() ?: continue

                    // Updated even for a duplicate payload: an unchanged status still
                    // proves the link is alive, which is exactly what matters here.
                    lastMessageAtElapsed = SystemClock.elapsedRealtime()

                    if (lastRawPerTopic[topic] == payload) continue
                    lastRawPerTopic[topic] = payload

                    onMessage(topic, payload)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Subscriber for $host ended", e)
        } finally {
            running = false
        }
    }

    override fun close() {
        running = false
    }

    companion object {
        private const val TAG = "CoilSub"

        /** Long enough to stay cheap at rest, short enough to close promptly. */
        private const val POLL_MILLIS = 250

        const val TOPIC_PLAYER_STATUS = "playerstatus"

        /** Prefix subscription: catches `volume.level` and any sibling topic. */
        const val TOPIC_VOLUME = "volume"

        /**
         * Prefix subscription for the box's own identity topics.
         *
         * Deliberately a prefix rather than `core.version`: the daemon publishes
         * `core.started_at`, `core.git_state` and `core.plugins.*`, and *not* a version —
         * so subscribing to the family is the only way to pick up whatever it does send.
         */
        const val TOPIC_CORE_PREFIX = "core."
        const val TOPIC_VERSION = "core.version"
        const val TOPIC_GIT_STATE = "core.git_state"

        val DEFAULT_TOPICS = listOf(
            TOPIC_PLAYER_STATUS,
            TOPIC_VOLUME,
            TOPIC_CORE_PREFIX,
        )
    }
}
