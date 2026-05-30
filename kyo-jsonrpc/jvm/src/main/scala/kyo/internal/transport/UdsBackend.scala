package kyo.internal.transport

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kyo.*

/** JVM implementation of the Unix-domain-socket backend.
  *
  * Binds a `ServerSocketChannel` on `sockPath`, registers a `Scope.ensure` cleanup that
  * closes the channel and deletes the socket file, and wraps the connection as a
  * [[kyo.JsonRpcTransport]] via [[UdsWireTransport]] + [[kyo.JsonRpcTransport.fromWire]].
  */
private[kyo] object UdsBackend:

    def connect(
        sockPath: Path,
        framer: JsonRpcTransport.Framer = JsonRpcTransport.Framer.lineDelimited,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope & Abort[Throwable]) =
        Sync.defer {
            val addr    = UnixDomainSocketAddress.of(sockPath)
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.bind(addr)
            Scope.ensure(Sync.defer {
                channel.close()
                Files.deleteIfExists(sockPath)
                ()
            }).andThen {
                Sync.defer(new UdsWireTransport(channel)).map { wire =>
                    JsonRpcTransport.fromWire(wire, framer, codec)
                }
            }
        }

end UdsBackend
