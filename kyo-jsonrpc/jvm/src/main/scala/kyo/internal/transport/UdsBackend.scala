package kyo.internal.transport

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import kyo.*

/** JVM implementation of the Unix-domain-socket backend.
  *
  * Binds a `ServerSocketChannel` on `sockPath`, registers a `Scope.ensure` cleanup that closes the
  * channel and removes the socket file, and wraps the connection as a [[kyo.JsonRpcTransport]] via
  * [[UdsWireTransport]] + [[kyo.JsonRpcTransport.fromWire]].
  */
private[kyo] object UdsBackend:

    def connect(
        sockPath: Path,
        framer: JsonRpcFramer = JsonRpcFramer.lineDelimited,
        codec: Schema[JsonRpcEnvelope] = summon[Schema[JsonRpcEnvelope]]
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[Throwable]) =
        Sync.defer {
            val addr    = UnixDomainSocketAddress.of(sockPath.toString)
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.bind(addr)
            Scope.ensure(
                Sync.defer(channel.close()).andThen(Abort.run[FileFsException](sockPath.remove).unit)
            ).andThen {
                // Unsafe: build the UDS transport, initializing its accept/read state under AllowUnsafe
                Sync.Unsafe.defer(UdsWireTransport.init(channel)).map { wire =>
                    JsonRpcTransport.fromWire(wire, framer, codec)
                }
            }
        }

end UdsBackend
