package app.coilforphoniebox.domain.model

/**
 * `Disconnected → Connecting → Connected → Degraded → Disconnected` (§4.3).
 *
 * [DEGRADED] means the sockets are up but the box has gone quiet past the watchdog
 * interval — ZMQ reconnects TCP silently and never reports it, so quiet is the only
 * signal available.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DEGRADED,
    ;

    val isUsable: Boolean get() = this == CONNECTED || this == DEGRADED
}

/** Outcome of a one-shot reachability probe, used by the add-box flow (§4.4). */
sealed interface ConnectionTestResult {
    /** [version] is `core.version` when the box reported one. */
    data class Reachable(val version: String?) : ConnectionTestResult

    data object Unreachable : ConnectionTestResult
}
