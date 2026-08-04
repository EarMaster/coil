/*
 * Phoniebox v3 - JeroMQ spike
 *
 * Validates exactly the stack the Android app will run on:
 * JeroMQ (pure Java) against the box's zmq.REP server.
 *
 * Run:  ./gradlew run --args="phoniebox.local"
 */

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val RPC_PORT = 5555
private const val PUB_PORT = 5558

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

private fun ok(msg: String) = println("  [ OK ] $msg")
private fun fail(msg: String) = println("  [FAIL] $msg")

// ---------------------------------------------------------------- RPC client

/**
 * DEALER based client. Unlike REQ it allows several outstanding requests;
 * correlation happens through the "id" field carried in the payload.
 *
 * The decisive detail is the empty delimiter frame before the payload.
 * Without it the REP socket on the other side cannot read the envelope.
 */
class RpcClient(private val ctx: ZContext, host: String) : AutoCloseable {

    private val socket = ctx.createSocket(SocketType.DEALER).apply {
        linger = 0
        receiveTimeOut = 4000
        tcpKeepAlive = 1
        connect("tcp://$host:$RPC_PORT")
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement?>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // A dedicated thread owns the socket. JeroMQ sockets are NOT
        // thread-safe, so sending from another thread would be a bug.
        scope.launch { receiveLoop() }
    }

    private val sendQueue = ArrayDeque<Pair<String, String>>()
    private val lock = Any()

    private suspend fun receiveLoop() {
        while (scope.isActive) {
            // Drain any pending sends
            synchronized(lock) {
                while (sendQueue.isNotEmpty()) {
                    val (_, payload) = sendQueue.removeFirst()
                    socket.sendMore("")          // <- the delimiter
                    socket.send(payload)
                }
            }

            val first = socket.recvStr(ZMQ.DONTWAIT)
            if (first == null) { delay(20); continue }

            // Expected: empty frame, then JSON
            val body = if (first.isEmpty()) socket.recvStr() else first
            val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: continue

            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
            pending.remove(id)?.complete(obj["result"])
        }
    }

    suspend fun call(
        pkg: String,
        plugin: String,
        method: String? = null,
        kwargs: Map<String, JsonElement> = emptyMap(),
        asThread: Boolean = false,
    ): JsonElement? {
        val id = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("id", id)
            put("package", pkg)
            put("plugin", plugin)
            method?.let { put("method", it) }
            if (kwargs.isNotEmpty()) put("kwargs", JsonObject(kwargs))
            if (asThread) put("as_thread", true)
        }.toString()

        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        synchronized(lock) { sendQueue.addLast(id to payload) }

        return withTimeoutOrNull(5000) { deferred.await() }
            .also { if (it == null) pending.remove(id) }
    }

    override fun close() {
        scope.cancel()
        ctx.destroySocket(socket)
    }
}

// ------------------------------------------------------------- PubSub listener

suspend fun listenPubSub(ctx: ZContext, host: String, seconds: Int) {
    val socket = ctx.createSocket(SocketType.SUB).apply {
        linger = 0
        receiveTimeOut = 500
        // "core." is a prefix subscription: the daemon publishes core.git_state and
        // core.started_at, but not core.version, whatever the docs suggest.
        listOf("playerstatus", "volume.level", "core.", "rfid.card_id")
            .forEach { subscribe(it.toByteArray()) }
        connect("tcp://$host:$PUB_PORT")
    }

    val topics = mutableMapOf<String, Int>()
    val fields = mutableMapOf<String, String>()
    val deadline = System.currentTimeMillis() + seconds * 1000L

    while (System.currentTimeMillis() < deadline) {
        val topic = socket.recvStr() ?: continue
        val payload = socket.recvStr() ?: continue
        topics[topic] = (topics[topic] ?: 0) + 1

        if (topic == "playerstatus") {
            runCatching { json.parseToJsonElement(payload).jsonObject }
                .getOrNull()?.forEach { (k, v) ->
                    fields.putIfAbsent(k, if (v is JsonPrimitive && v.isString) "String" else "Number/Other")
                }
        }
    }

    ctx.destroySocket(socket)

    if (topics.isEmpty()) {
        fail("No PubSub messages received.")
        return
    }
    ok("Topics: " + topics.entries.joinToString { "${it.key}(${it.value})" })
    if (fields.isNotEmpty()) {
        println("\n  playerstatus fields:")
        fields.toSortedMap().forEach { (k, t) -> println("    ${k.padEnd(22)} $t") }
    }
}

// ---------------------------------------------------------------------- Main

fun main(args: Array<String>) = runBlocking {
    val host = args.firstOrNull() ?: run {
        println("Usage: ./gradlew run --args=\"phoniebox.local\"")
        return@runBlocking
    }

    println("JeroMQ spike against $host")
    println("=".repeat(55))

    ZContext().use { ctx ->
        RpcClient(ctx, host).use { rpc ->

            println("\n[1] Single DEALER request")
            // player.ctrl.playerstatus, not core.version: there is no `core` RPC package,
            // so asking for one answers with an error and a healthy box looks dead.
            val status = rpc.call("player", "ctrl", "playerstatus")
            if (status != null) ok("playerstatus answered")
            else {
                fail("No reply. Check the delimiter framing (see probe_phoniebox.py).")
                return@runBlocking
            }

            println("\n[2] 5 concurrent requests")
            val results = (1..5).map { async { rpc.call("volume", "ctrl", "get_volume") } }.awaitAll()
            val good = results.count { it != null }
            if (good == 5) ok("All 5 replies correlated correctly.")
            else fail("Only $good/5 replies - serialise requests.")

            println("\n[3] Slow call, deliberately without as_thread")
            // as_thread hands the call to a daemon thread and returns the Thread object
            // instead of the result, so a library call carrying it comes back unusable.
            // It is fire-and-forget only — never use it for a call you read.
            val albums = rpc.call("player", "ctrl", "list_albums")
            val albumCount = albums?.let { runCatching { it.jsonArray.size }.getOrNull() }
            if (albumCount != null) ok("list_albums returned $albumCount entries.")
            else fail("list_albums gave no usable array: $albums")

            println("\n[4] Playback test: toggle, wait 3s, toggle back")
            rpc.call("player", "ctrl", "toggle")
            delay(3000)
            rpc.call("player", "ctrl", "toggle")
            ok("Toggle sent - did the box react?")
        }

        println("\n[5] PubSub - listening for 20s")
        println("    -> Please play something on the box now\n")
        listenPubSub(ctx, host, 20)
    }

    println("\n" + "=".repeat(55))
    println("If [1] and [2] are green, the planned architecture holds.")
}
