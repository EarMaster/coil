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

    suspend fun probe(host: String, rpcPort: Int): ConnectionTestResult =
        ZmqRpcClient(host, rpcPort).use { client ->
            client.call(Commands.version).fold(
                onSuccess = { ConnectionTestResult.Reachable(StatusParser.version(it)) },
                onFailure = { ConnectionTestResult.Unreachable },
            )
        }
}
