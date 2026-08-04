package app.coilforphoniebox.domain.model

/**
 * A configured Phoniebox.
 *
 * Exactly one box is *active* at a time (see implementation plan §7.1); everything
 * else in the app hangs off [id], including the library cache and favourites, so a
 * box can be removed in one step.
 */
data class Box(
    val id: String,
    val displayName: String,
    val host: String,
    val rpcPort: Int = DEFAULT_RPC_PORT,
    val pubPort: Int = DEFAULT_PUB_PORT,
    val addedAt: Long,
    /** Whether the background session may monitor this box (§7.4). */
    val autoSessionEnabled: Boolean = false,
    /** Wi-Fi network the automatic mode is limited to; null means any Wi-Fi. */
    val networkSsid: String? = null,
    val lastSeenAt: Long? = null,
    val sortIndex: Int = 0,
) {
    /**
     * Cover art comes over plain HTTP from the box's web server, not over ZMQ.
     * `get_single_coverart` returns a bare filename that is appended here.
     */
    fun coverUrl(coverFile: String): String =
        "http://$host/cover-cache/${coverFile.trimStart('/')}"

    companion object {
        /** TCP, not the WebSocket ports — JeroMQ has no `ws://` transport. */
        const val DEFAULT_RPC_PORT = 5555
        const val DEFAULT_PUB_PORT = 5558
    }
}
