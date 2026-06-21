package kyo.internal.transport

import kyo.*

/** Scala Native stub for the Unix-domain-socket backend.
  *
  * The NIO `UnixDomainSocketAddress` / `ServerSocketChannel` APIs used by the JVM
  * implementation are not yet available on Scala Native. Any call to [[connect]]
  * immediately fails with an [[UnsupportedOperationException]]. Use
  * [[kyo.JsonRpcTransport.inMemory]] or [[kyo.JsonRpcTransport.stdio]] for native
  * process transports instead.
  */
private[kyo] object UdsBackend:

    def connect(
        sockPath: Path,
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[Throwable]) =
        Abort.fail(new UnsupportedOperationException(
            s"Unix domain sockets are not supported on the Scala Native platform (path: $sockPath)"
        ))

end UdsBackend
