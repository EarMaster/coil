package app.coilforphoniebox.transport

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * RPC against the box's `zmq.REP` socket on TCP 5555.
 *
 * Uses `DEALER` rather than `REQ`: a REQ socket allows exactly one outstanding
 * request and the web UI carries a code comment saying concurrent requests break it.
 * A DEALER can have several in flight, and correlation happens through the `id`
 * field in the payload anyway.
 *
 * **The detail that decides whether any of this works:** with REQ/REP, ZMQ inserts an
 * empty delimiter frame that a DEALER has to prepend itself. Without the `sendMore("")`
 * below, the REP socket cannot read the envelope and never replies at all.
 *
 * JeroMQ sockets are not thread-safe, so exactly one thread ever touches the socket.
 * Callers hand work over through [outgoing] and wait on a [CompletableDeferred].
 */
class ZmqRpcClient(
    private val host: String,
    private val port: Int,
) : AutoCloseable {

    private class Request(val id: String, val payload: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val outgoing = Channel<Request>(Channel.UNLIMITED)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    @Volatile
    private var running = true

    private val thread = Thread({ socketLoop() }, "coil-rpc-$host").apply {
        isDaemon = true
        start()
    }

    /**
     * Sends [command] and waits for the matching reply.
     *
     * Retries once for commands marked [PhonieboxCommand.retryable]. Relative ones —
     * `next`, `change_volume`, `toggle` — are never retried: if the first request did
     * arrive and only the reply was lost, a retry would apply the change twice.
     */
    suspend fun call(command: PhonieboxCommand): Result<JsonElement> {
        val attempts = if (command.retryable) 2 else 1
        var failure: Result<JsonElement> = Result.failure(NotConnectedException())
        repeat(attempts) { attempt ->
            if (attempt > 0) Log.d(TAG, "Retrying ${command.name}")
            val result = attempt(command)
            if (result.isSuccess) return result
            failure = result
        }
        return failure
    }

    private suspend fun attempt(command: PhonieboxCommand): Result<JsonElement> {
        if (!running) return Result.failure(NotConnectedException())

        val id = UUID.randomUUID().toString()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply

        return try {
            outgoing.trySend(Request(id, command.toJson(id).toString()))
            val response = withTimeoutOrNull(command.timeoutMillis) { reply.await() }
                ?: return Result.failure(RpcTimeoutException(command.name))

            response["error"]?.let { error ->
                return Result.failure(RpcErrorException(command.name, error.toString()))
            }
            Result.success(response["result"] ?: JsonNull)
        } catch (e: TransportException) {
            // The socket thread gave up while this call was waiting.
            Result.failure(e)
        } finally {
            pending.remove(id)
        }
    }

    private fun socketLoop() {
        try {
            ZContext().use { context ->
                val socket = context.createSocket(SocketType.DEALER).apply {
                    linger = 0
                    // Surfaces dead connections faster under Doze, where a silently
                    // broken TCP connection would otherwise look healthy for minutes.
                    tcpKeepAlive = 1
                    tcpKeepAliveIdle = KEEPALIVE_IDLE_SECONDS
                    tcpKeepAliveInterval = KEEPALIVE_INTERVAL_SECONDS
                    receiveTimeOut = POLL_MILLIS
                    connect("tcp://$host:$port")
                }

                while (running) {
                    while (true) {
                        val request = outgoing.tryReceive().getOrNull() ?: break
                        socket.sendMore("") // the delimiter frame REP expects
                        socket.send(request.payload)
                    }
                    receiveOne(socket)
                }
            }
        } catch (e: Throwable) {
            // The context is gone; every caller has to fail rather than hang.
            Log.w(TAG, "RPC loop for $host ended", e)
        } finally {
            running = false
            failAllPending()
        }
    }

    private fun receiveOne(socket: ZMQ.Socket) {
        // Null on the receive timeout, which is the idle case.
        val first = socket.recvStr() ?: return
        val body = if (first.isEmpty() && socket.hasReceiveMore()) socket.recvStr() else first
        if (body.isNullOrBlank()) return

        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (obj == null) {
            Log.w(TAG, "Dropping unparseable reply from $host")
            return
        }

        // Replies without an id still have to be drained — the box always answers,
        // and an unread reply would sit in the receive queue forever.
        val id = obj["id"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return
        pending.remove(id)?.complete(obj)
    }

    /**
     * Completes waiting callers with a failure rather than cancelling them:
     * cancelling the deferred would cancel the *caller's* coroutine, which is a
     * surprising way for "the box went away" to arrive at a button press.
     */
    private fun failAllPending() {
        pending.keys.toList().forEach { id ->
            pending.remove(id)?.completeExceptionally(NotConnectedException())
        }
    }

    override fun close() {
        // The loop notices within one poll interval and tears the context down
        // itself; interrupting it mid-receive would be a worse way to stop.
        running = false
        outgoing.close()
    }

    private companion object {
        const val TAG = "CoilRpc"

        /**
         * How long the socket thread blocks waiting for a reply before checking the
         * outgoing queue again. It bounds the delay on a command, so it stays short;
         * 20 wakeups a second is nothing next to the 4 Hz status stream.
         */
        const val POLL_MILLIS = 50

        const val KEEPALIVE_IDLE_SECONDS = 30L
        const val KEEPALIVE_INTERVAL_SECONDS = 10L
    }
}
