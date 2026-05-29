package kyo.internal

import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*

final private[kyo] class UdsWireTransport(server: ServerSocketChannel) extends WireTransport:

    // Single client-at-a-time MVP: the first accepted connection wires send/incoming;
    // subsequent accepts are dropped. Multi-client requires a per-conn map, deferred to
    // the consumer-module roadmap.
    // class construction is always wrapped in Sync.defer at the call site
    // (JsonRpcTransportJvm.unixDomain), so Unsafe.init runs inside a deferred block.
    private val activeChannelRef: AtomicRef.Unsafe[Maybe[SocketChannel]] =
        AtomicRef.Unsafe.init[Maybe[SocketChannel]](Absent)(using AllowUnsafe.embrace.danger)

    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        Sync.defer {
            // AtomicRef.Unsafe.get inside Sync.defer for SocketChannel handoff
            activeChannelRef.get()(using AllowUnsafe.embrace.danger) match
                case Present(socket) =>
                    val buffer = ByteBuffer.wrap(bytes.toArray)
                    while buffer.hasRemaining do discard(socket.write(buffer))
                case Absent =>
                    ()
        }

    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
        Stream.unfold[Unit, Chunk[Byte], Async & Abort[Closed]](()) { _ =>
            Sync.defer {
                // AtomicRef.Unsafe access for accept-then-read MVP
                val socket = activeChannelRef.get()(using AllowUnsafe.embrace.danger) match
                    case Present(s) => s
                    case Absent =>
                        val s = server.accept()
                        discard(activeChannelRef.compareAndSet(Absent, Present(s))(using AllowUnsafe.embrace.danger))
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
        Sync.defer {
            // AtomicRef.Unsafe.get inside Sync.defer for SocketChannel handoff
            activeChannelRef.get()(using AllowUnsafe.embrace.danger).foreach(_.close())
            server.close()
        }
end UdsWireTransport
