package app.coilforphoniebox.domain.repository

import app.coilforphoniebox.domain.model.Box
import app.coilforphoniebox.domain.model.ConnectionTestResult
import kotlinx.coroutines.flow.Flow

interface BoxRepository {
    val boxes: Flow<List<Box>>

    /** The one box the app currently controls; null before the first box is added. */
    val activeBox: Flow<Box?>

    suspend fun box(boxId: String): Box?

    /** Returns the stored box, which carries the generated [Box.id]. */
    suspend fun add(
        displayName: String,
        host: String,
        rpcPort: Int = Box.DEFAULT_RPC_PORT,
        pubPort: Int = Box.DEFAULT_PUB_PORT,
    ): Box

    suspend fun update(box: Box)

    /** Cascades to the box's library cache and favourites. */
    suspend fun delete(boxId: String)

    suspend fun setActive(boxId: String)

    suspend fun markSeen(boxId: String)

    /** One-shot `core.version` probe, without touching the active connection. */
    suspend fun testConnection(host: String, rpcPort: Int): ConnectionTestResult
}
