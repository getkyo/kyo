package kyo.internal.transport

import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*

// Single client-at-a-time: the first accepted connection wires send/incoming; subsequent accepts are
// dropped. Multi-client support would require a per-connection map. The accept/read state is held in an
// AtomicRef.Unsafe shared between send and incoming; it is built by the companion init under a
// propagated AllowUnsafe and accessed inside the deferred boundaries below.
final private[kyo] class UdsWireTransport(
    server: ServerSocketChannel,
    activeChannelRef: AtomicRef.Unsafe[Maybe[SocketChannel]]
) extends JsonRpcWireTransport:

    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        // Unsafe: AtomicRef.Unsafe handoff + blocking SocketChannel write inside the deferred boundary
        Sync.Unsafe.defer {
            activeChannelRef.get() match
                case Present(socket) =>
                    val buffer = ByteBuffer.wrap(bytes.toArray)
                    while buffer.hasRemaining do discard(socket.write(buffer))
                case Absent =>
                    ()
        }

    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        Stream.unfold[Unit, Chunk[Byte], Async & Abort[Closed]](()) { _ =>
            // Unsafe: AtomicRef.Unsafe accept-then-read state inside the deferred boundary
            Sync.Unsafe.defer {
                val socket = activeChannelRef.get() match
                    case Present(s) => s
                    case Absent =>
                        val s = server.accept()
                        discard(activeChannelRef.compareAndSet(Absent, Present(s)))
                        s
                val buffer = ByteBuffer.allocate(4096)
                val n      = socket.read(buffer)
                if n < 0 then Maybe.Absent
                else
                    buffer.flip()
                    val arr = new Array[Byte](n)
                    buffer.get(arr)
                    Maybe.Present((Chunk.from(arr), ()))
                end if
            }
        }

    def close(using Frame): Unit < Async =
        // Unsafe: AtomicRef.Unsafe read + blocking close inside the deferred boundary
        Sync.Unsafe.defer {
            activeChannelRef.get().foreach(_.close())
            server.close()
        }
end UdsWireTransport

private[kyo] object UdsWireTransport:
    // Builds the transport, initializing the single-client accept/read state under the propagated AllowUnsafe.
    def init(server: ServerSocketChannel)(using AllowUnsafe): UdsWireTransport =
        new UdsWireTransport(server, AtomicRef.Unsafe.init[Maybe[SocketChannel]](Absent))
end UdsWireTransport
