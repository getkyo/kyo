package kyo.net

import kyo.*
import kyo.net.internal.NodeNet
import scala.scalajs.js as sjs

/** [[RawPeer]] on JS: a Node `net.Socket`. The socket stays paused except while a `recv` waits, so a leaf that stops calling `recv` stops
  * reading and the kernel receive buffer fills, as on the other platforms. Node exposes no `SO_RCVBUF`, so `rcvBuf` does not apply here.
  */
private[net] object RawPeerPlatform:

    import AllowUnsafe.embrace.danger

    private def net: sjs.Dynamic = NodeNet.asInstanceOf[sjs.Dynamic]

    def connect(port: Int, rcvBuf: Maybe[Int])(using Frame): RawPeer < (Async & Scope) =
        Sync.defer {
            val connected = Promise.Unsafe.init[Unit, Any]()
            val socket    = net.connect(port, "127.0.0.1")
            val peer      = new NodePeer(socket)
            discard(socket.once("connect", (() => connected.completeDiscard(Result.succeed(()))): sjs.Function0[Unit]))
            discard(socket.once("error", ((_: sjs.Any) => connected.completeDiscard(Result.succeed(()))): sjs.Function1[sjs.Any, Unit]))
            (peer, connected)
        }.map { (peer, connected) =>
            Scope.ensure(peer.close()).andThen(connected.safe.get).andThen(peer)
        }

    def listen(rcvBuf: Maybe[Int])(using Frame): RawPeer.Listener < (Async & Scope) =
        Sync.defer {
            val pending   = new sjs.Array[sjs.Dynamic]()
            val waiter    = AtomicRef.Unsafe.init[Maybe[Promise.Unsafe[sjs.Dynamic, Any]]](Absent)
            val listening = Promise.Unsafe.init[Int, Any]()
            val server    = net.createServer()
            discard(server.on(
                "connection",
                { (socket: sjs.Dynamic) =>
                    discard(socket.pause())
                    waiter.getAndSet(Absent) match
                        case Present(p) => p.completeDiscard(Result.succeed(socket))
                        case Absent     => discard(pending.push(socket))
                }: sjs.Function1[sjs.Dynamic, Unit]
            ))
            discard(server.listen(
                0,
                "127.0.0.1",
                (() => listening.completeDiscard(Result.succeed(server.address().port.asInstanceOf[Int]))): sjs.Function0[Unit]
            ))
            (server, pending, waiter, listening)
        }.map { (server, pending, waiter, listening) =>
            Scope.ensure(Sync.defer(discard(server.close()))).andThen(listening.safe.get).map { boundPort =>
                new RawPeer.Listener:
                    val port                                             = boundPort
                    def accept()(using Frame): RawPeer < (Async & Scope) =
                        Sync.defer {
                            val next = Promise.Unsafe.init[sjs.Dynamic, Any]()
                            if pending.length > 0 then next.completeDiscard(Result.succeed(pending.shift()))
                            else waiter.set(Present(next))
                            next
                        }.map(_.safe.get).map { socket =>
                            val peer = new NodePeer(socket)
                            Scope.ensure(peer.close()).andThen(peer)
                        }
            }
        }

    final private class NodePeer(socket: sjs.Dynamic) extends RawPeer:

        private val reads                                               = new sjs.Array[RawPeer.Read]()
        @volatile private var waiting: Maybe[Promise.Unsafe[Unit, Any]] = Absent
        @volatile private var ended: Boolean                            = false

        discard(socket.pause())
        discard(socket.on(
            "data",
            { (chunk: sjs.Dynamic) =>
                discard(socket.pause())
                val bytes = sjs.typedarray.int8Array2ByteArray(new sjs.typedarray.Int8Array(
                    chunk.buffer.asInstanceOf[sjs.typedarray.ArrayBuffer],
                    chunk.byteOffset.asInstanceOf[Int],
                    chunk.length.asInstanceOf[Int]
                ))
                push(RawPeer.Read.Bytes(Span.fromUnsafe(bytes)))
            }: sjs.Function1[sjs.Dynamic, Unit]
        ))
        discard(socket.on("end", (() => end(RawPeer.Read.Fin)): sjs.Function0[Unit]))
        discard(socket.on(
            "error",
            ((e: sjs.Dynamic) => end(RawPeer.Read.Reset(String.valueOf(e.code.asInstanceOf[sjs.Any])))): sjs.Function1[sjs.Dynamic, Unit]
        ))

        private def push(read: RawPeer.Read): Unit =
            discard(reads.push(read))
            waiting.foreach { p =>
                waiting = Absent
                p.completeDiscard(Result.succeed(()))
            }
        end push

        private def end(read: RawPeer.Read): Unit =
            if !ended then
                ended = true
                push(read)

        def send(bytes: Span[Byte])(using Frame): Unit < Async =
            val written = Promise.Unsafe.init[Unit, Any]()
            discard(socket.write(
                sjs.Dynamic.global.Buffer.from(sjs.typedarray.byteArray2Int8Array(bytes.toArray).buffer),
                ((_: sjs.Any) => written.completeDiscard(Result.succeed(()))): sjs.Function1[sjs.Any, Unit]
            ))
            written.safe.get
        end send

        def recv()(using Frame): RawPeer.Read < Async =
            Loop(()) { _ =>
                Sync.defer {
                    if reads.length > 0 then
                        val next = reads(0)
                        // The end stays queued, so reading past it keeps returning it.
                        next match
                            case RawPeer.Read.Bytes(_) => discard(reads.shift())
                            case _                     => ()
                        Loop.done(next): Loop.Outcome[Unit, RawPeer.Read] < Async
                    else
                        val arrived = Promise.Unsafe.init[Unit, Any]()
                        waiting = Present(arrived)
                        if !ended then discard(socket.resume())
                        arrived.safe.get.andThen(Loop.continue(()))
                    end if
                }
            }

        def shutdownWr()(using Frame): Unit < Async = Sync.defer(discard(socket.end()))

        def reset()(using Frame): Unit < Async = Sync.defer(discard(socket.resetAndDestroy()))

        def close()(using Frame): Unit < Async = Sync.defer(discard(socket.destroy()))
    end NodePeer

end RawPeerPlatform
