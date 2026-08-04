package app.coilforphoniebox.transport

/** Base type for every failure the transport layer reports upwards. */
sealed class TransportException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * No reply within the command's timeout. Expected in normal operation: the box may
 * be switched off, which is a state rather than an error.
 */
class RpcTimeoutException(val commandName: String) :
    TransportException("No reply to $commandName")

/** The box answered with an `error` object instead of a `result`. */
class RpcErrorException(val commandName: String, val detail: String) :
    TransportException("$commandName failed: $detail")

/** No box configured, or the socket pair has been torn down. */
class NotConnectedException : TransportException("Not connected to a box")

/** The reply arrived but did not look like what the command promised. */
class UnexpectedPayloadException(val commandName: String) :
    TransportException("Unexpected payload for $commandName")
