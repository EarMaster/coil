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
     * Where to load a cover from, or null for "do not load this one".
     *
     * Cover art comes over plain HTTP from the box's web server, not over ZMQ, and
     * `get_single_coverart` normally answers with a bare filename in the box's own cache
     * that is appended here.
     *
     * A provider-neutral box can answer with an **absolute URL** instead, belonging to
     * whichever backend the content came from — Spotify hands back `https://i.scdn.co/…`.
     * Appending that to the box's address would produce a URL that 404s, so it is passed
     * through unchanged — but only when [allowExternal] says so. Loading it means the phone
     * talks to a third party, which is the one thing the LAN-only design promises it does
     * not do (§16), so [AppSettings.loadExternalCoverArt] is off until the user opts in and
     * a refused cover falls back to the same placeholder as a missing one.
     */
    fun coverUrl(coverRef: String, allowExternal: Boolean): String? = when {
        !coverRef.isExternalCoverRef() -> "http://$host/cover-cache/${coverRef.trimStart('/')}"
        allowExternal -> coverRef
        else -> null
    }

    companion object {
        /** TCP, not the WebSocket ports — JeroMQ has no `ws://` transport. */
        const val DEFAULT_RPC_PORT = 5555
        const val DEFAULT_PUB_PORT = 5558
    }
}

/**
 * Whether a cover reference points somewhere other than the active box's cover cache.
 *
 * Deliberately a prefix test and not a URL parse: the only thing that distinguishes the two
 * kinds of answer is that one is a bare cache filename and the other is an absolute
 * `http(s)` URL, and a filename can contain anything else a parser might trip over.
 *
 * Lives here rather than next to its two callers because both the parser that reads the
 * box's answer and the model that turns it into a URL have to agree on the same test —
 * disagreeing would either mangle an external URL or hand a filename to a third party.
 */
fun String.isExternalCoverRef(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)
