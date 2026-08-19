package app.coilforphoniebox.data.repository

import app.coilforphoniebox.transport.RpcErrorException

/**
 * What one attempt at an optional RPC says about whether the box supports it.
 *
 * `true` supported, `false` not, **null still unknown** — and the null is the whole reason
 * this is a function rather than two lines at the call site.
 *
 * A box answers an unknown method by raising before the plugin body runs, and
 * `jukebox/rpc/server.py` turns that into an error reply. So an error reply is a real answer,
 * and a cheap one: nothing happened on the box. Every *other* failure is not an answer at
 * all. A timeout, a torn-down socket or an unreachable host says the box is off or busy,
 * which says nothing whatever about what its software can do — and recording "unsupported"
 * from one of those would strand a perfectly capable box on the fallback path until the app
 * was restarted.
 *
 * The same distinction as `PlayerRepositoryImpl.playAt`, which probes for `play(pos=…)`.
 */
internal fun probeVerdict(attempt: Result<*>): Boolean? = when {
    attempt.isSuccess -> true
    attempt.exceptionOrNull() is RpcErrorException -> false
    else -> null
}
