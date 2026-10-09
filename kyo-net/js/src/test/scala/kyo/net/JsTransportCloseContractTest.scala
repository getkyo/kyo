package kyo.net

import kyo.*
import kyo.net.internal.JsHandle
import kyo.net.internal.JsIoDriver
import kyo.net.internal.JsTransport
import kyo.net.internal.NodeNet
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.WriteState
import scala.scalajs.js as sjs

/** Close-contract leaves for the Node transport, driven from the far side by a raw Node `net.Socket` the test pauses and resumes.
  *
  * Payload byte `i` is `i % 251`, so the raw peer checks order and completeness as bytes arrive without holding the stream. Every wait is a
  * Node event (`data`, `end`, `close`, a `newListener` for `drain`) or a promise; virtual time drives the close-flush grace.
  */
class JsTransportCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    private def net: sjs.Dynamic = NodeNet.asInstanceOf[sjs.Dynamic]

    private def payloadByte(offset: Long): Byte = (offset % 251).toByte

    private def payloadSpan(from: Long, size: Int): Span[Byte] =
        Span.fromUnsafe(Array.tabulate[Byte](size)(i => payloadByte(from + i)))

    /** The far side of a connection: a raw Node socket, paused from creation so the kernel buffers fill once the kyo side writes enough. */
    final private class RawPeer(val socket: sjs.Dynamic):
        var received: Long                         = 0L
        var firstMismatch: Long                    = -1L
        private var target: Long                   = 0L
        private var reached                        = Promise.Unsafe.init[Unit, Any]()
        private val ended                          = Promise.Unsafe.init[String, Any]()
        private def settleEnd(event: String): Unit =
            ended.completeDiscard(Result.succeed(event))
            reached.completeDiscard(Result.succeed(()))

        discard(socket.pause())
        discard(socket.on(
            "data",
            { (chunk: sjs.typedarray.Uint8Array) =>
                var i = 0
                while i < chunk.length do
                    if firstMismatch < 0 && chunk(i).toByte != payloadByte(received + i) then firstMismatch = received + i
                    i += 1
                received += chunk.length
                if received >= target then
                    discard(socket.pause())
                    reached.completeDiscard(Result.succeed(()))
            }: sjs.Function1[sjs.typedarray.Uint8Array, Unit]
        ))
        discard(socket.on("end", (() => settleEnd("end")): sjs.Function0[Unit]))
        discard(socket.on("close", (() => settleEnd("close")): sjs.Function0[Unit]))
        discard(socket.on("error", ((e: sjs.Dynamic) => settleEnd(s"error ${e.code}")): sjs.Function1[sjs.Dynamic, Unit]))

        /** Read until `bytes` have arrived in total or the stream ended, then pause again. */
        def readUntil(bytes: Long)(using Frame): Unit < Async =
            Sync.defer {
                target = bytes
                reached = Promise.Unsafe.init[Unit, Any]()
                if received >= bytes || ended.done() then reached.completeDiscard(Result.succeed(()))
                else discard(socket.resume())
                reached.safe
            }.map(_.get)

        def readToEnd(using Frame): String < Async =
            Sync.defer {
                target = Long.MaxValue
                discard(socket.resume())
                ended.safe
            }.map(_.get)
    end RawPeer

    private def connectPeer(port: Int): RawPeer = new RawPeer(net.connect(port, "127.0.0.1"))

    /** Completes when the WritePump registers its `drain` listener, which it does exactly when it parks on a write Node only buffered. */
    private def drainListenerAdded(socket: sjs.Dynamic)(using Frame): Fiber[Unit, Any] =
        val promise                               = Promise.Unsafe.init[Unit, Any]()
        lazy val fn: sjs.Function1[sjs.Any, Unit] = (event: sjs.Any) =>
            if event.toString == "drain" then
                discard(socket.removeListener("newListener", fn))
                promise.completeDiscard(Result.succeed(()))
        discard(socket.on("newListener", fn))
        promise.safe
    end drainListenerAdded

    private def nextEvent(socket: sjs.Dynamic, event: String)(using Frame): Fiber[Unit, Any] =
        val promise = Promise.Unsafe.init[Unit, Any]()
        discard(socket.once(event, ((_: sjs.Any) => promise.completeDiscard(Result.succeed(()))): sjs.Function1[sjs.Any, Unit]))
        promise.safe
    end nextEvent

    /** A JsTransport whose single driver closes at scope exit. */
    private def ownTransport(clock: Clock)(using Frame): (JsTransport, JsIoDriver) < (Sync & Scope) =
        Sync.defer(JsTransport.init(clock = clock)).map { t =>
            val driver = t.pool.next().asInstanceOf[JsIoDriver]
            Scope.ensure(Sync.defer(driver.close())).andThen((t, driver))
        }

    private def acceptOne(transport: Transport, config: NetConfig)(using
        Frame
    ): (Listener, Fiber[Connection, Any]) < (Async & Abort[NetException] & Scope) =
        val accepted = Promise.Unsafe.init[Connection, Any]()
        transport.listen("127.0.0.1", 0, 128, config)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get.map { listener =>
            Scope.ensure(Sync.defer(listener.close())).andThen((listener, accepted.safe))
        }
    end acceptOne

    /** The payload bytes outbound accepted, offering consecutive spans until an offer is refused. */
    private def offerUntilFull(conn: Connection, spanSize: Int): Long =
        @scala.annotation.tailrec
        def loop(offered: Long): Long =
            conn.outbound.offer(payloadSpan(offered, spanSize)) match
                case Result.Success(true) => loop(offered + spanSize)
                case _                    => offered
        loop(0L)
    end offerUntilFull

    "a peer FIN flushes every span accepted into outbound before the stream ends" - eachBackendPending { name =>
        if name == "node" then
            Present("N1: Node's allowHalfOpen=false auto-end after a peer FIN stops drain, so spans still queued in outbound are dropped")
        else Absent
    } { transport =>
        val spanSize  = 256 * 1024
        val spanCount = 64
        for
            (listener, acceptedF) <- acceptOne(transport, NetConfig(channelCapacity = spanCount))
            peer = connectPeer(listener.port)
            _      <- Scope.ensure(Sync.defer(discard(peer.socket.destroy())))
            server <- acceptedF.get
            _      <- Scope.ensure(Sync.defer(server.close()))
            // Offers run without yielding, so the pump has taken nothing yet and outbound holds spanCount spans: more than the paused
            // peer's kernel buffers can absorb before the FIN is read.
            acceptedBytes <- Sync.defer(offerUntilFull(server, spanSize))
            _             <- Sync.defer(discard(peer.socket.end()))
            inboundEnd    <- Abort.run[Closed](server.inbound.safe.take)
            endedBy       <- peer.readToEnd
        yield
            assert(acceptedBytes >= spanSize.toLong * spanCount, s"outbound must accept $spanCount spans, accepted $acceptedBytes bytes")
            assert(inboundEnd.isFailure, s"a peer FIN must end inbound, got $inboundEnd")
            assert(peer.firstMismatch < 0, s"bytes must arrive in order; first mismatch at offset ${peer.firstMismatch}")
            assert(
                peer.received == acceptedBytes,
                s"every accepted byte must reach the peer before the stream ends ($endedBy): received ${peer.received} of $acceptedBytes"
            )
        end for
    }

    "a closing connection whose peer keeps reading is not cut by closeFlushGrace".pendingUntilFixed(
        "N2: the grace judges a slow but progressing Node reader stalled after one window and closeHandle destroys the socket one window later"
    ) in {
        val grace = 10.seconds
        // Each round reads more than the loopback kernel buffers can hold (Linux and macOS cap them below 10 MiB), so bytes left the driver
        // inside every window.
        val round   = 12L * 1024 * 1024
        val rounds  = 3
        val payload = 40 * 1024 * 1024
        Clock.withTimeControl { tc =>
            Clock.get.map { clock =>
                for
                    (transport, _)        <- ownTransport(clock)
                    (listener, acceptedF) <- acceptOne(transport, NetConfig(closeFlushGrace = grace.grace))
                    peer = connectPeer(listener.port)
                    _      <- Scope.ensure(Sync.defer(discard(peer.socket.destroy())))
                    server <- acceptedF.get
                    socket = server.asInstanceOf[InternalConnection[JsHandle]].handle.socket
                    parked = drainListenerAdded(socket)
                    offered <- Sync.defer(server.outbound.offer(payloadSpan(0L, payload)))
                    _       <- parked.get
                    _       <- Sync.defer(server.close())
                    _       <- tc.awaitPendingSleeper(grace)
                    _       <- Kyo.foreachDiscard(1 to rounds) { r =>
                        peer.readUntil(round * r).andThen(tc.advance(grace))
                    }
                    endedBy <- peer.readToEnd
                yield
                    assert(offered == Result.succeed(true), s"outbound must accept the payload, got $offered")
                    assert(peer.firstMismatch < 0, s"bytes must arrive in order; first mismatch at offset ${peer.firstMismatch}")
                    assert(
                        peer.received == payload.toLong,
                        s"a peer that read in every grace window must receive every byte before the stream ends ($endedBy): " +
                            s"received ${peer.received} of $payload"
                    )
                end for
            }
        }
    }

    "a STARTTLS upgrade over a write parked on drain settles that write".pendingUntilFixed(
        "N4: cancel leaves the parked writable pending and the upgrade's removeAllListeners strips its drain/close/error listeners"
    ) in {
        for
            (transport, driver) <- ownTransport(Clock.live)
            serverSock = Promise.Unsafe.init[sjs.Dynamic, Any]()
            server     = net.createServer(
                { (sock: sjs.Dynamic) =>
                    discard(sock.pause())
                    serverSock.completeDiscard(Result.succeed(sock))
                }: sjs.Function1[sjs.Dynamic, Unit]
            )
            listening = nextEvent(server, "listening")
            _    <- Sync.defer(discard(server.listen(0, "127.0.0.1")))
            _    <- listening.get
            _    <- Scope.ensure(Sync.defer(discard(server.close())))
            conn <- transport.connect("127.0.0.1", server.address().port.asInstanceOf[Int]).safe.get
            _    <- Scope.ensure(Sync.defer(conn.close()))
            raw  <- serverSock.safe.get
            _    <- Scope.ensure(Sync.defer(discard(raw.destroy())))
            internal = conn.asInstanceOf[InternalConnection[JsHandle]]
            socket   = internal.handle.socket
            parked   = drainListenerAdded(socket)
            _ <- Sync.defer(discard(conn.outbound.offer(payloadSpan(0L, 32 * 1024 * 1024))))
            _ <- parked.get
            upgrade = transport.upgradeToTls(conn, NetTlsConfig(trustAll = true), NetConfig.DefaultChannelCapacity)
            closed  = nextEvent(socket, "close")
            _       <- Sync.defer(conn.close())
            outcome <- Abort.run[NetException](upgrade.safe.get)
            _       <- closed.get
        yield
            val dump    = kyo.internal.Diagnostics.dumpAll()
            val section =
                dump.split("=== ").find(_.startsWith("JsIoDriver@" + java.lang.System.identityHashCode(driver))).getOrElse(dump)
            assert(outcome.isFailure, s"the abandoned upgrade must fail, got $outcome")
            val parkedAfter = internal.writeState match
                case WriteState.AwaitingWritable(_, _) | WriteState.Backpressured(_, _) => true
                case _                                                                  => false
            assert(
                !parkedAfter,
                s"the write parked on drain must settle once its socket is gone, writeState=${internal.writeState.getClass.getSimpleName}; driver: $section"
            )
            assert(section.contains("pendingWritables=0"), s"no writable may stay pending on the driver: $section")
        end for
    }

end JsTransportCloseContractTest
