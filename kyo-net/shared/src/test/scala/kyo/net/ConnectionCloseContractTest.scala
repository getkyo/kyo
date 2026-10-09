package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.transport.WriteState

/** The connection close contract for the shared transport layer: what `close()` flushes, which teardown sources may release a handle, what a
  * read pump holding bytes delivers, and how a peer's FIN or reset is reported. Leaves run over every backend (plaintext and TLS) through the
  * public transport where the window is reachable there, and over the internal connection with a scripted driver where it is not.
  */
class ConnectionCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    private val spanSize = 64 * 1024

    private val observedRed = Set("kqueue", "nio", "epoll", "io_uring", "node")

    private def pin(reason: String): String => Maybe[String] =
        backend => if observedRed.contains(backend) then Present(reason) else Absent

    /** The failing paths are in the shared connection, so every TLS provider a violating backend drives is pinned; NIO drives only jdk and
      * Node only its own TLS.
      */
    private def pinTls(reason: String): (String, String) => Maybe[String] =
        (backend, provider) =>
            val drives = backend match
                case "nio"  => provider == "jdk"
                case "node" => provider == "node"
                case _      => true
            if observedRed.contains(backend) && drives then Present(reason) else Absent

    private def patterned(i: Int): Array[Byte] = Array.fill[Byte](spanSize)((i % 251).toByte)

    private def expected(spans: Int): Array[Byte] = (0 until spans).toArray.flatMap(patterned)

    private def parkedOnWritable(conn: InternalConnection[?]): Boolean =
        conn.writeState match
            case WriteState.AwaitingWritable(_, _) | WriteState.Backpressured(_, _) => true
            case _                                                                  => false

    /** Polls a channel-state condition that the scenario guarantees will hold; the leaf timeout turns a condition that never holds into a
      * failure.
      */
    private def awaitState(cond: => Boolean)(using Frame): Unit < Async =
        Loop(()) { _ =>
            if cond then Loop.done(())
            else Async.sleep(1.millis).andThen(Loop.continue(()))
        }

    private def readPumpParked(conn: Connection)(using Frame): Boolean =
        conn.inbound.pendingPuts() match
            case Result.Success(n) => n > 0
            case _                 => false

    /** Offers patterned spans, numbered from `from`, until the outbound channel is full and the write pump is parked on a socket the peer no
      * longer drains. Returns the number of the next span.
      */
    private def fillUntilStalled(conn: InternalConnection[?], from: Int)(using Frame): Int < Async =
        Loop(from) { offered =>
            conn.outbound.offer(Span.fromUnsafe(patterned(offered))) match
                case Result.Success(true)                            => Loop.continue(offered + 1)
                case Result.Success(false) if parkedOnWritable(conn) => Loop.done(offered)
                case Result.Success(false)                           => Async.sleep(1.millis).andThen(Loop.continue(offered))
                case Result.Failure(closed)                          => Abort.panic(closed)
                case Result.Panic(t)                                 => Abort.panic(t)
        }

    private def drainToEnd(conn: Connection)(using Frame): Array[Byte] < Async =
        Loop(Chunk.empty[Array[Byte]]) { acc =>
            Abort.run[Closed](conn.inbound.safe.take).map {
                case Result.Success(span) => Loop.continue(acc.append(span.toArray))
                case _                    => Loop.done(acc.toArray.flatten)
            }
        }

    private def takeBytes(conn: Connection, n: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= n then Loop.done(acc)
            else conn.inbound.safe.take.map(span => Loop.continue(acc ++ span.toArray))
        }

    private def describe(received: Array[Byte], want: Array[Byte]): String =
        val firstDiff = received.indices.find(i => i >= want.length || received(i) != want(i))
        s"received ${received.length} of ${want.length} bytes${firstDiff.fold("")(i => s", first difference at $i")}"

    /** A server connection accepted on `transport`, a client connected to it (through `via` when a relay sits between them), both closed at
      * scope exit.
      */
    private def pair(
        transport: Transport,
        config: NetConfig = NetConfig.default,
        clientConfig: NetConfig = NetConfig.default,
        tls: Maybe[(NetTlsConfig, NetTlsConfig)] = Absent,
        relay: Boolean = false
    )(using Frame): (Connection, Connection, Maybe[RawRelay]) < (Async & Abort[NetException] & Scope) =
        val accepted = Promise.Unsafe.init[Connection, Any]()
        val onAccept = (conn: Connection) => accepted.completeDiscard(Result.succeed(conn))
        for
            listener <- tls match
                case Present((serverTls, _)) => transport.listenTls("127.0.0.1", 0, 128, serverTls, config)(onAccept).safe.get
                case Absent                  => transport.listen("127.0.0.1", 0, 128, config)(onAccept).safe.get
            _       <- Scope.ensure(Sync.defer(listener.close()))
            through <- (if relay then RawRelay.init(listener.port).map(Present(_)) else Absent): Maybe[RawRelay] < (Async & Scope)
            port = through.fold(listener.port)(_.port)
            client <- tls match
                case Present((_, clientTls)) =>
                    transport.connectTls("127.0.0.1", port, clientTls, Transport.DefaultConnectTimeout, clientConfig).safe.get
                case Absent => transport.connect("127.0.0.1", port, Transport.DefaultConnectTimeout, clientConfig).safe.get
            _      <- Scope.ensure(Sync.defer(client.close()))
            server <- accepted.safe.get
            _      <- Scope.ensure(Sync.defer(server.close()))
        yield (server, client, through)
        end for
    end pair

    /** A driver that writes one byte per call and parks the pump on writability after each, so the test decides when the peer reads, and that
      * hands the test the read promise so it can deliver a read outcome on cue. `cancel` fails the parked writable the way the real drivers do.
      */
    final private class ScriptedPeerDriver extends IoDriver[Unit]:
        val written                                                                     = AtomicInt.Unsafe.init(0)
        val closeHandleCalls                                                            = AtomicInt.Unsafe.init(0)
        @volatile var parked: Maybe[Promise.Unsafe[Unit, Abort[Closed | NetException]]] = Absent
        @volatile var read: Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]]           = Absent

        def peerReads()(using AllowUnsafe): Unit =
            parked.foreach { p =>
                parked = Absent
                p.completeDiscard(Result.succeed(()))
            }

        def deliver(outcome: ReadOutcome)(using AllowUnsafe): Unit =
            read.foreach { p =>
                read = Absent
                p.completeDiscard(Result.succeed(outcome))
            }

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            read = Present(promise)
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
            parked = Present(promise)
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit   = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                                      =
            discard(written.incrementAndGet())
            if offset + 1 < data.size then WriteResult.Partial(data, offset + 1) else WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit =
            parked.foreach { p =>
                parked = Absent
                p.completeDiscard(Result.fail(Closed("scripted peer", summon[Frame], "canceled")))
            }
            read.foreach { p =>
                read = Absent
                p.completeDiscard(Result.fail(Closed("scripted peer", summon[Frame], "canceled")))
            }
        end cancel
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                        = discard(closeHandleCalls.incrementAndGet())
        def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit     = closeFd()
        def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
            try cancel(handle)
            finally releaseFd(handle, closeFd)
        def close()(using AllowUnsafe, Frame): Unit = ()
        def label: String                           = "ScriptedPeerDriver"
        def handleLabel(handle: Unit): String       = "scripted"
    end ScriptedPeerDriver

    "C1: close flushes every accepted byte" - {

        "a second close() during the flush leaves the flush to finish" -
            eachBackendPending(pin("S1: a second close() takes the re-entrant closeFn branch and releases while the pump holds a span")) {
                transport =>
                    for
                        (s, client, _) <- pair(transport)
                        server = s.asInstanceOf[InternalConnection[?]]
                        first    <- fillUntilStalled(server, 0)
                        _        <- awaitState(readPumpParked(client))
                        spans    <- fillUntilStalled(server, first)
                        _        <- Sync.defer { server.close(); server.close() }
                        received <- drainToEnd(client)
                    yield
                        val want = expected(spans)
                        assert(
                            received.sameElements(want),
                            s"every accepted byte must reach the peer in order: ${describe(received, want)}"
                        )
                    end for
            }

        "a put parked on a full outbound when close() runs is sent with the flush" -
            eachBackendPending(pin("S11: closeAwaitEmpty fails the parked put Closed, so bytes the caller handed over are never sent")) {
                transport =>
                    for
                        (s, client, _) <- pair(transport)
                        server = s.asInstanceOf[InternalConnection[?]]
                        first <- fillUntilStalled(server, 0)
                        _     <- awaitState(readPumpParked(client))
                        spans <- fillUntilStalled(server, first)
                        put = server.outbound.putFiber(Span.fromUnsafe(patterned(spans)))
                        parkedAtClose <- Sync.defer {
                            val parked = !put.done()
                            server.close()
                            parked
                        }
                        received  <- drainToEnd(client)
                        putResult <- put.safe.getResult
                    yield
                        assert(parkedAtClose, "the put must still be parked on the full outbound channel when close() runs")
                        assert(putResult.isSuccess, s"a put parked before close() must be admitted into the flush; got $putResult")
                        val want = expected(spans + 1)
                        assert(
                            received.sameElements(want),
                            s"the parked put's bytes must follow the queued ones: ${describe(received, want)}"
                        )
                    end for
            }

        "a teardown source arriving during the flush leaves the flush to finish" - {

            def scenario(source: (InternalConnection[Unit], ScriptedPeerDriver) => Unit)(using Frame, kyo.test.AssertScope): Unit =
                val driver = new ScriptedPeerDriver
                val conn   = InternalConnection.init[Unit]((), driver, 8)
                discard(conn.start())
                discard(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](3)(1))))
                conn.close()
                assert(driver.closeHandleCalls.get() == 0, "the close must start out waiting for the parked tail")
                source(conn, driver)
                val releasedEarly = driver.closeHandleCalls.get()
                driver.peerReads()
                driver.peerReads()
                assert(
                    releasedEarly == 0,
                    s"a second teardown source must not release while the pump holds a span; closeHandle=$releasedEarly"
                )
                assert(driver.written.get() == 3, s"every byte of the parked span must be written; writes=${driver.written.get()}")
                assert(
                    driver.closeHandleCalls.get() == 1,
                    s"the handle must be released once; closeHandle=${driver.closeHandleCalls.get()}"
                )
            end scenario

            "a second close()".pendingUntilFixed("S1: the re-entrant closeFn releases without checking the pump's held span") in {
                scenario((conn, _) => conn.close())
            }

            "a peer byte".pendingUntilFixed(
                "S1: the read pump's offer to the closing inbound re-enters closeFn, which releases at once"
            ) in {
                scenario((_, driver) => driver.deliver(ReadOutcome.Bytes(Span(9.toByte))))
            }

            "a peer FIN".pendingUntilFixed("S1: the read pump's PeerFin teardown re-enters closeFn, which releases at once") in {
                scenario((_, driver) => driver.deliver(ReadOutcome.PeerFin))
            }
        }

        "in-memory: the peer receives every byte written before close()".pendingUntilFixed(
            "S9: the in-memory close uses out.close(), discarding bytes the peer has not taken"
        ) in {
            val (a, b) = InternalConnection.inMemoryPair()
            val bytes  = Span.fromUnsafe(Array.tabulate[Byte](16)(_.toByte))
            for
                offered  <- Sync.defer(a.outbound.offer(bytes))
                _        <- Sync.defer(a.close())
                received <- drainToEnd(b)
            yield
                assert(offered.contains(true))
                assert(received.sameElements(bytes.toArray), s"bytes written before close() must reach the peer: ${received.length} of 16")
            end for
        }
    }

    "E2: every byte read from the kernel reaches inbound before its end" - {

        val capacityOne = NetConfig(channelCapacity = 1)

        def parkedSpanAtLocalClose(transport: Transport, tls: Maybe[(NetTlsConfig, NetTlsConfig)])(using
            Frame,
            kyo.test.AssertScope
        ): Unit < (Async & Abort[NetException | Closed] & Scope) =
            val first  = Array.fill[Byte](100)(1)
            val second = Array.fill[Byte](100)(2)
            for
                (server, client, _) <- pair(transport, clientConfig = capacityOne, tls = tls)
                _                   <- server.outbound.safe.put(Span.fromUnsafe(first))
                _                   <- awaitState(client.inbound.size().getOrElse(0) == 1)
                _                   <- server.outbound.safe.put(Span.fromUnsafe(second))
                _                   <- awaitState(readPumpParked(client))
                _                   <- Sync.defer(client.close())
                received            <- drainToEnd(client)
            yield
                val want = first ++ second
                assert(
                    received.sameElements(want),
                    s"the span the read pump held must be delivered before the end: ${describe(received, want)}"
                )
            end for
        end parkedSpanAtLocalClose

        "a span the read pump holds parked when close() runs reaches the consumer" -
            eachBackendPending(pin("S2: close() fails the read pump's parked put and onInboundClosedDuringRead drops its bytes")) {
                transport =>
                    parkedSpanAtLocalClose(transport, Absent)
            }

        "TLS: a span the read pump holds parked when close() runs reaches the consumer" - eachBackendTlsPending(NetConfig.default)(
            pinTls("S2: close() fails the read pump's parked put and onInboundClosedDuringRead drops its bytes")
        ) { (transport, serverTls, clientTls) =>
            parkedSpanAtLocalClose(transport, Present((serverTls, clientTls)))
        }

        def parkedSpanAtReclaim(transport: Transport, tls: Maybe[(NetTlsConfig, NetTlsConfig)])(using
            Frame,
            kyo.test.AssertScope
        ): (Array[Byte], Array[Byte], Connection.Status) < (Async & Abort[NetException | Closed] & Scope) =
            val first  = Array.fill[Byte](100)(1)
            val second = Array.fill[Byte](100)(2)
            val config = NetConfig(channelCapacity = 1, peerCloseGrace = 100.millis.grace)
            for
                (server, client, _) <- pair(transport, clientConfig = config, tls = tls)
                _                   <- server.outbound.safe.put(Span.fromUnsafe(first))
                _                   <- awaitState(client.inbound.size().getOrElse(0) == 1)
                _                   <- server.outbound.safe.put(Span.fromUnsafe(second))
                _                   <- awaitState(readPumpParked(client))
                _                   <- Sync.defer(server.close())
                _                   <- Abort.run[Timeout](Async.timeout(10.seconds)(client.onClosing.safe.get))
                received            <- drainToEnd(client)
            yield (received, first ++ second, client.status)
            end for
        end parkedSpanAtReclaim

        "a span the read pump holds parked when a peer-close reclaim runs reaches the consumer" -
            eachBackendPending(pin("S2: the peer-close grace reclaim fails the read pump's parked put and its bytes are dropped")) {
                transport =>
                    parkedSpanAtReclaim(transport, Absent).map { (received, want, _) =>
                        assert(
                            received.sameElements(want),
                            s"the span the read pump held must be delivered before the end: ${describe(received, want)}"
                        )
                    }
            }

        "TLS: a stream cut short by a peer-close reclaim never reports CleanClose" - eachBackendTls { (transport, serverTls, clientTls) =>
            parkedSpanAtReclaim(transport, Present((serverTls, clientTls))).map { (received, want, status) =>
                assert(
                    received.sameElements(want) || status != Connection.Status.CleanClose,
                    s"an inbound stream missing bytes must not report an orderly close: ${describe(received, want)}, status $status"
                )
            }
        }
    }

    "E1: a peer reset is distinguishable from an orderly end" - {

        def resetStatus(transport: Transport, tls: Maybe[(NetTlsConfig, NetTlsConfig)])(using
            Frame,
            kyo.test.AssertScope
        ): Unit < (Async & Abort[NetException | Closed] & Scope) =
            if !RawRelay.available then cancel("raw posix sockets are unavailable on this host")
            else
                val request = Array.fill[Byte](64)(5)
                for
                    (server, client, relay) <- pair(transport, tls = tls, relay = true)
                    _                       <- client.outbound.safe.put(Span.fromUnsafe(request))
                    got                     <- takeBytes(server, request.length)
                    _                       <- relay.get.resetUpstream()
                    ended                   <- Abort.run[Closed](server.inbound.safe.take)
                yield
                    assert(got.sameElements(request), "the bytes sent before the reset must be delivered")
                    assert(ended.isFailure, s"inbound must end after the reset; got $ended")
                    val status = server.status
                    assert(
                        !Set(
                            Connection.Status.Active,
                            Connection.Status.LocalClose,
                            Connection.Status.CleanClose,
                            Connection.Status.Truncated
                        )
                            .contains(status),
                        s"a peer reset must be reported as a reset, neither an orderly close, a truncation, nor a local close; got $status"
                    )
                end for

        "a peer reset is reported as a reset" - eachBackendPending(
            pin("S7: a peer RST ends inbound exactly like a FIN and the status cannot report it (design: Status.Reset, open question 4)")
        ) { transport =>
            resetStatus(transport, Absent)
        }

        "TLS: a peer reset is reported as a reset" - eachBackendTlsPending(NetConfig.default)(
            pinTls("S7: a peer RST ends inbound like a FIN; posix TLS reports LocalClose, NIO Truncated, Node nothing (open question 4)")
        ) { (transport, serverTls, clientTls) =>
            resetStatus(transport, Present((serverTls, clientTls)))
        }
    }

    "H1: a peer FIN ends only inbound" - {

        def responseAfterPeerFin(transport: Transport, tls: Maybe[(NetTlsConfig, NetTlsConfig)])(using
            Frame,
            kyo.test.AssertScope
        ): Unit < (Async & Abort[NetException | Closed] & Scope) =
            if !RawRelay.available then cancel("raw posix sockets are unavailable on this host")
            else
                val request  = Array.fill[Byte](64)(5)
                val response = Array.fill[Byte](64)(6)
                for
                    (server, client, relay) <- pair(transport, tls = tls, relay = true)
                    _                       <- client.outbound.safe.put(Span.fromUnsafe(request))
                    got                     <- takeBytes(server, request.length)
                    _                       <- relay.get.halfCloseUpstream()
                    ended                   <- Abort.run[Closed](server.inbound.safe.take)
                    put                     <- Abort.run[Closed](server.outbound.safe.put(Span.fromUnsafe(response)))
                    _                       <- Sync.defer(server.close())
                    received                <- drainToEnd(client)
                yield
                    assert(got.sameElements(request))
                    assert(ended.isFailure, s"the peer's FIN must end inbound; got $ended")
                    assert(put.isSuccess, s"outbound must stay writable after the peer's FIN; got $put")
                    assert(
                        received.sameElements(response),
                        s"the response written after the peer's FIN must reach it: ${describe(received, response)}"
                    )
                end for

        "the response written after the peer's FIN reaches the peer" - eachBackendPending(pin(
            "S10: a peer FIN runs the full closeFn, closing outbound to new writes (pinned per the design's halfopen recommendation, open question 1)"
        )) { transport =>
            responseAfterPeerFin(transport, Absent)
        }

        "TLS: the response written after the peer's FIN reaches the peer" - eachBackendTlsPending(NetConfig.default)(pinTls(
            "S10: a peer FIN runs the full closeFn, closing outbound to new writes (pinned per the design's halfopen recommendation, open question 1)"
        )) { (transport, serverTls, clientTls) =>
            responseAfterPeerFin(transport, Present((serverTls, clientTls)))
        }
    }

end ConnectionCloseContractTest
