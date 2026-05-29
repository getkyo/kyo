// PUBLIC JVM-only UDS transport extension on the shared JsonRpcTransport companion
package kyo

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path

object JsonRpcTransportJvm:

    /** JVM-only UDS server transport. Binds a `ServerSocketChannel` on `sockPath`,
      * registers Scope.ensure cleanup that closes the channel and deletes the socket
      * file, and exposes the resulting bytes through `Framer.lineDelimited` by default.
      */
    def unixDomain(
        sockPath: Path,
        framer: Framer = Framer.lineDelimited,
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0
    )(using Frame): JsonRpcTransport < (Async & Scope) =
        Sync.defer {
            val addr    = UnixDomainSocketAddress.of(sockPath)
            val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            channel.bind(addr)
            Scope.ensure(Sync.defer {
                channel.close()
                Files.deleteIfExists(sockPath)
                ()
            }).andThen {
                Sync.defer(new internal.UdsWireTransport(channel)).map { wire =>
                    JsonRpcTransport.fromWire(wire, framer, codec)
                }
            }
        }

    extension (self: JsonRpcTransport.type)
        def unixDomain(sockPath: Path)(using Frame): JsonRpcTransport < (Async & Scope) =
            JsonRpcTransportJvm.unixDomain(sockPath)
        def unixDomain(sockPath: Path, framer: Framer)(using Frame): JsonRpcTransport < (Async & Scope) =
            JsonRpcTransportJvm.unixDomain(sockPath, framer)
        def unixDomain(sockPath: Path, framer: Framer, codec: JsonRpcCodec)(using Frame): JsonRpcTransport < (Async & Scope) =
            JsonRpcTransportJvm.unixDomain(sockPath, framer, codec)
        def unixDomain(sockPath: Path, codec: JsonRpcCodec)(using Frame): JsonRpcTransport < (Async & Scope) =
            JsonRpcTransportJvm.unixDomain(sockPath, codec = codec)
    end extension

end JsonRpcTransportJvm
