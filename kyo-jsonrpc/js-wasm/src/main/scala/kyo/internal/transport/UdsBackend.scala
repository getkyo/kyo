package kyo.internal.transport

import kyo.*

/** Scala.js stub for the Unix-domain-socket backend.
  *
  * Unix domain sockets require OS-level NIO support that is not available on the
  * Scala.js platform. Any call to [[connect]] immediately fails with an
  * [[UnsupportedOperationException]]. Use [[kyo.JsonRpcTransport.inMemory]] or
  * [[kyo.JsonRpcTransport.stdio]] for browser/Node.js transports instead.
  */
private[kyo] object UdsBackend:

    def connect(
        sockPath: Path,
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[Throwable]) =
        Abort.fail(new UnsupportedOperationException(
            s"Unix domain sockets are not supported on the Scala.js platform (path: $sockPath)"
        ))

end UdsBackend
