package app.coilforphoniebox.transport

import app.coilforphoniebox.domain.model.ConnectionTestResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot reachability check for the add-box and settings flows.
 *
 * Deliberately independent of [ConnectionManager]: testing an address must not disturb
 * the live connection to the box that is currently playing.
 */
@Singleton
class BoxProbe @Inject constructor() {

    /**
     * A reply of any shape means the box is there and its jukebox app is answering, which
     * is all this needs to establish. The version is not asked for: no RPC returns it — it
     * arrives over PubSub if the box publishes it at all.
     */
    suspend fun probe(host: String, rpcPort: Int): ConnectionTestResult =
        ZmqRpcClient(host, rpcPort).use { client ->
            client.call(Commands.ping).fold(
                onSuccess = { ConnectionTestResult.Reachable(version = null) },
                onFailure = { ConnectionTestResult.Unreachable },
            )
        }
}
