package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.HandleLife
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.ReleaseMode
import kyo.net.internal.transport.ScriptedPeerDriver
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.transport.WriteState

/** The connection close contract, clauses C1 to C6 and X1: what `close()` flushes, how long it may take, when the handle is released and how
  * a loss is reported. Each leaf is named after its clause and runs once per backend cell, plain and TLS, on a transport of the cell's own
  * whose timers run on a controlled clock; the peer is a [[RawPeer]], so what a leaf asserts is what reached the wire.
  */
class ConnectionCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    private def parkedOnWritable(conn: InternalConnection[?]): Boolean =
        conn.writeState match
            case WriteState.AwaitingWritable(_, _) | WriteState.Backpressured(_, _) => true
            case _                                                                  => false

    /** Offers spans until the outbound channel is full and the write pump is parked on a socket the peer no longer drains. The bytes the
      * channel accepted, or `Absent` when the pump never parked.
      */
    private def fillUntilStalled(conn: InternalConnection[?])(using Frame): Maybe[Long] < Async =
        val span = Span.fromUnsafe(Array.fill[Byte](64 * 1024)(7))
        Loop(0) { offered =>
            if offered > 16 * 1024 then Loop.done(Absent)
            else
                conn.outbound.offer(span) match
                    case Result.Success(true)                            => Loop.continue(offered + 1)
                    case Result.Success(false) if parkedOnWritable(conn) => Loop.done(Present(offered.toLong * span.size))
                    case Result.Success(false)                           => Async.sleep(1.millis).andThen(Loop.continue(offered))
                    case _                                               => Loop.done(Absent)
        }
    end fillUntilStalled

    private val spanSize = 64 * 1024

    private def patterned(i: Int): Array[Byte] = Array.fill[Byte](spanSize)((i % 251).toByte)

    /** Offers patterned spans, numbered from `from`, until the outbound channel is full and the write pump is parked on a socket the peer no
      * longer drains. Returns the number of the next span.
      */
    private def fillPatternedUntilStalled(conn: InternalConnection[?], from: Int)(using Frame): Int < Async =
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

    /** The outcome `conn.released` completes with. The bound runs on the live clock and only turns a release that never completes into a
      * failure naming `what`.
      */
    private def releasedOutcome(conn: Connection, what: String)(using Frame, kyo.test.AssertScope): Connection.CloseOutcome < Async =
        Clock.let(Clock.live) {
            Abort.run[Timeout](Async.timeout(armedBudget)(conn.released.safe.get)).map {
                case Result.Success(outcome) => outcome
                case _ => fail(s"$what: released did not complete within ${armedBudget.show}, so no outcome was observed")
            }
        }

    private def dropped(outcome: Connection.CloseOutcome): Boolean =
        outcome match
            case Connection.CloseOutcome.Dropped(_, _) => true
            case Connection.CloseOutcome.Flushed       => false

    /** Advances every close-flush window of `conn` in turn until it is released, at most `cap` windows. The windows advanced. */
    private def advanceUntilReleased(conn: InternalConnection[?], control: Clock.TimeControl, grace: Duration, cap: Int)(using
        Frame,
        kyo.test.AssertScope
    ): Int < Async =
        Loop(0) { windows =>
            if windows >= cap then Loop.done(windows)
            else
                releasedOrArmed(conn.isReleased, control, s"close-flush grace (window ${windows + 1})", grace).map { released =>
                    if released then Loop.done(windows)
                    else control.advance(grace).andThen(Loop.continue(windows + 1))
                }
        }

    /** A server connection whose [[RawPeer]] stopped reading behind a 4 KiB receive buffer, filled until its pump parked and then closed.
      * Returns the connection, its peer and the bytes its outbound channel accepted.
      */
    private def stalledClose(transport: Transport, grace: Duration)(using
        Frame,
        kyo.test.AssertScope
    ): (InternalConnection[?], RawPeer, Long) < (Async & Abort[NetException | Closed] & Scope) =
        val accepted = Promise.Unsafe.init[Connection, Any]()
        // A parked pump does not prove the send buffer is full: io_uring parks on its own unsent-tail bound while a send is in flight and
        // the kernel can still take bytes. A loopback send buffer grows to megabytes, so a default-depth queue (256 KiB) can flush whole
        // after the close and release with no window. A 32 MiB queue is beyond the largest send buffer Linux and macOS grow by default
        // (4 MiB).
        val config = NetConfig(channelCapacity = 512, closeFlushGrace = grace.grace)
        for
            listener <- transport.listen("127.0.0.1", 0, 16, config)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            peer     <- RawPeer.connect(listener.port, rcvBuf = Present(4096))
            server   <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
            stalled  <- fillUntilStalled(server)
            _ = assert(stalled.nonEmpty, "the write pump never parked against the peer that stopped reading")
            _ <- Sync.defer(server.close())
        yield (server, peer, stalled.getOrElse(0L))
        end for
    end stalledClose

    /** Runs `scenario` with a clock whose time moves only when the scenario advances it, for a connection over a scripted driver. The
      * scenario itself runs on the live clock, as [[eachBackendOnClock]]'s do.
      */
    private def onScriptedClock(scenario: (Clock.TimeControl, Clock) => (kyo.test.AssertScope ?=> Unit < Async))(using
        Frame,
        kyo.test.AssertScope
    ): Unit < Async =
        Clock.withTimeControl { control =>
            Clock.get.map(clock => Clock.let(Clock.live)(scenario(control, clock)))
        }

    /** A started connection over `driver` whose close-flush grace runs on `clock`, with `bytes` bytes offered to its outbound channel. */
    private def scripted(driver: ScriptedPeerDriver, clock: Clock, grace: Duration, bytes: Int)(using Frame): InternalConnection[Unit] =
        val conn = InternalConnection.init[Unit]((), driver, 8, closeFlushGrace = grace, clock = clock)
        discard(conn.start())
        discard(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](bytes)(1))))
        conn
    end scripted

    /** A driver whose every write completes at once, recording a write that lands after the handle was released. Its counters are atomic
      * because the offer and the close it races run on different carriers.
      */
    final private class ReleaseOrderDriver extends IoDriver[Unit]:
        val written         = AtomicInt.Unsafe.init(0)
        val released        = AtomicBoolean.Unsafe.init(false)
        val writeAfterClose = AtomicBoolean.Unsafe.init(false)

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit             = ()
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit  = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit    = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                                       =
            if released.get() then writeAfterClose.set(true)
            discard(written.incrementAndGet())
            WriteResult.Done
        end write
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                      = ()
        private val handleLife                                                                                        = HandleLife.init()
        def life(handle: Unit): HandleLife                                                                            = handleLife
        def release(handle: Unit, mode: ReleaseMode, done: Promise.Unsafe[Unit, Any])(using AllowUnsafe, Frame): Unit =
            released.set(true)
            done.completeUnitDiscard()
        end release
        def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit     = closeFd()
        def runOwned(handle: Unit)(op: () => Unit)(using AllowUnsafe, Frame): Unit           = op()
        def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
            try cancel(handle)
            finally releaseFd(handle, closeFd)
        def close()(using AllowUnsafe, Frame): Unit = ()
        def label: String                           = "ReleaseOrderDriver"
        def handleLabel(handle: Unit): String       = "release-order"
    end ReleaseOrderDriver

    "C1" - {

        "a second close() during the flush leaves the flush to finish" - eachBackendOnClock { (transport, _) =>
            val accepted = Promise.Unsafe.init[Connection, Any]()
            for
                listener <- transport.listen("127.0.0.1", 0, 128)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                client   <- transport.connect("127.0.0.1", listener.port).safe.get
                _        <- Scope.ensure(Sync.defer(client.close()))
                server   <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
                _        <- Scope.ensure(Sync.defer(server.close()))
                first    <- fillPatternedUntilStalled(server, 0)
                // Quiescence before the close: once the client's read pump is parked on its full inbound channel no read is armed, so the
                // second fill parks the server against a receive buffer that no longer drains.
                _        <- assertEventually(Sync.defer(client.inbound.pendingPuts().getOrElse(0) > 0))
                spans    <- fillPatternedUntilStalled(server, first)
                _        <- Sync.defer { server.close(); server.close() }
                received <- drainToEnd(client)
            yield
                val want      = (0 until spans).toArray.flatMap(patterned)
                val firstDiff = received.indices.find(i => i >= want.length || received(i) != want(i))
                assert(
                    received.sameElements(want),
                    s"every accepted byte must reach the peer in order: received ${received.length} of ${want.length} bytes" +
                        firstDiff.fold("")(i => s", first difference at $i")
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
                assert(driver.releaseCalls.get() == 0, "the close must start out waiting for the parked tail")
                source(conn, driver)
                val releasedEarly = driver.releaseCalls.get()
                driver.peerReads()
                driver.peerReads()
                assert(
                    releasedEarly == 0,
                    s"a second teardown source must not release while the pump holds a span; releases=$releasedEarly"
                )
                assert(driver.written.get() == 3, s"every byte of the parked span must be written; writes=${driver.written.get()}")
                assert(
                    driver.releaseCalls.get() == 1,
                    s"the handle must be released once; releases=${driver.releaseCalls.get()}"
                )
            end scenario

            "a second close()" in scenario((conn, _) => conn.close())

            "a peer byte" in scenario((_, driver) => driver.deliver(ReadOutcome.Bytes(Span(9.toByte))))

            "a peer FIN" in scenario((_, driver) => driver.deliver(ReadOutcome.PeerFin))
        }

        // The cap only turns a deadlock into a failure: the rounds take about 20s on Native, which a slow runner can push past the module's
        // 60s budget.
        "a span the outbound channel accepted concurrently with close() is never written after the release".timeout(10.minutes) in {
            if kyo.internal.Platform.isJS || kyo.internal.Platform.isWasm then
                cancel("a single-threaded runtime cannot interleave the offer and the close")
            else
                // The window is a few instructions wide, so rounds of parallel races run until one hits it or the bound passes.
                val racesPerRound                                                = 16
                val rounds                                                       = 20000
                def race(gate: Promise.Unsafe[Unit, Any]): Maybe[String] < Async =
                    val driver = new ReleaseOrderDriver
                    val conn   = InternalConnection.init[Unit]((), driver, 8)
                    discard(conn.start())
                    for
                        offerFiber <- Fiber.initUnscoped(gate.safe.get.andThen(Sync.defer(conn.outbound.offer(Span(1.toByte)))))
                        closeFiber <- Fiber.initUnscoped(gate.safe.get.andThen(Sync.defer(conn.close())))
                        offered    <- offerFiber.get
                        _          <- closeFiber.get
                    yield
                        if driver.writeAfterClose.get() then Present("written after the handle was released")
                        else if offered.contains(true) && driver.written.get() != 1 then Present("accepted and never written")
                        else Absent
                    end for
                end race
                Loop(0) { round =>
                    if round >= rounds then Loop.done((round, Absent: Maybe[String]))
                    else
                        val gate = Promise.Unsafe.init[Unit, Any]()
                        Kyo.foreach(Chunk.from(0 until racesPerRound))(_ => Fiber.initUnscoped(race(gate))).map { fibers =>
                            gate.completeDiscard(Result.succeed(()))
                            Kyo.foreach(fibers)(_.get).map { outcomes =>
                                outcomes.collectFirst { case Present(v) => v } match
                                    case Some(v) => Loop.done((round + 1, Present(v): Maybe[String]))
                                    case None    => Loop.continue(round + 1)
                            }
                        }
                }.map { (round, violation) =>
                    assert(
                        violation.isEmpty,
                        s"round $round of $racesPerRound races: a span the outbound channel accepted was ${violation.getOrElse("")}"
                    )
                }
        }

        "in-memory: the local reader keeps the bytes the peer wrote" in {
            val (a, b) = InternalConnection.inMemoryPair()
            val bytes  = Span.fromUnsafe(Array.tabulate[Byte](16)(_.toByte))
            for
                offered  <- Sync.defer(b.outbound.offer(bytes))
                _        <- Sync.defer(a.close())
                received <- drainToEnd(a)
                late     <- Sync.defer(b.outbound.offer(Span(1.toByte)))
            yield
                assert(offered.contains(true))
                assert(
                    received.sameElements(bytes.toArray),
                    s"bytes the peer wrote before close() must stay readable: ${received.length} of 16"
                )
                assert(late.isFailure, s"a peer write after close() must fail Closed; got $late")
            end for
        }

        "every byte written before close() reaches the peer" - eachEndpoint() { (conn, peer) =>
            val payload = Span.fromUnsafe(Array.tabulate[Byte](256 * 1024)(i => (i % 251).toByte))
            for
                _        <- conn.outbound.safe.put(payload)
                _        <- Sync.defer(conn.close())
                received <- peer.recvExactly(payload.size)
            yield assert(
                received.sameElements(payload.toArray),
                s"received ${received.length} of ${payload.size} bytes written before close()"
            )
            end for
        }

        "a tail that drains after the pump tore down advances the close without a timer expiry" - {

            "[scripted]" in onScriptedClock { (_, clock) =>
                val driver = new ScriptedPeerDriver(tailBound = Present(8))
                val conn   = scripted(driver, clock, 5.seconds, 8)
                conn.close()
                assert(!conn.isReleased, "the close released while the driver still owed the kernel 8 bytes")
                driver.peerReads(8)
                releasedOutcome(conn, "a close whose tail drained after the pump tore down").map { outcome =>
                    assert(outcome == Connection.CloseOutcome.Flushed, s"the tail drained whole, yet released reported $outcome")
                }
            }

            // No clock advance anywhere: the grace may be armed, and the release must still come from the tail draining.
            eachBackendTlsOnClock { (transport, _, serverTls, clientTls) =>
                val accepted = Promise.Unsafe.init[Connection, Any]()
                for
                    listener <- transport.listenTls(
                        "127.0.0.1",
                        0,
                        16,
                        serverTls,
                        NetConfig(channelCapacity = 64, closeFlushGrace = 5.seconds.grace)
                    )(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
                    _ <- Scope.ensure(Sync.defer(listener.close()))
                    // A one-span inbound channel nobody takes from parks the client's read pump, so its socket stops draining the server.
                    client <- transport.connectTls("127.0.0.1", listener.port, clientTls, config = NetConfig(channelCapacity = 1)).safe.get
                    _      <- Scope.ensure(Sync.defer(client.close()))
                    server <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
                    spans  <- fillPatternedUntilStalled(server, 0)
                    _      <- Sync.defer(server.close())
                    received <- drainToEnd(client)
                    outcome  <- releasedOutcome(server, "a close whose tail drained once the peer read")
                yield
                    val want = (0 until spans).toArray.flatMap(patterned)
                    assert(received.sameElements(want), s"received ${received.length} of ${want.length} bytes accepted before close()")
                    assert(outcome == Connection.CloseOutcome.Flushed, s"the peer read every byte, yet released reported $outcome")
                end for
            }
        }
    }

    "C2" - {

        "the close-flush grace runs on the transport's clock" - eachBackendOnClock { (transport, control) =>
            val grace = 7.seconds
            for
                (server, _, _) <- stalledClose(transport, grace)
                // Kernel timing decides how many windows still see bytes leave, so the leaf asserts that every window is timed on the transport's
                // clock and that the release comes; the cap turns a release that never comes into a failure carrying the count.
                windows <- advanceUntilReleased(server, control, grace, (timeout.toMillis / 100 / 2).toInt)
            yield
                assert(windows >= 1, "the close released a pump parked on unsent bytes before any grace window")
                assert(
                    server.isReleased,
                    s"a close whose peer stopped reading must be released by the grace; still held after $windows windows"
                )
            end for
        }

        "the descriptor is released within one grace after the peer stopped reading" - eachBackendOnClock { (transport, control) =>
            val grace = 7.seconds
            for
                (server, _, _) <- stalledClose(transport, grace)
                windows        <- advanceUntilReleased(server, control, grace, (timeout.toMillis / 100 / 2).toInt)
                // released completes only once the OS close returned, so a descriptor a kernel-owned send still holds keeps it pending.
                outcome <- releasedOutcome(server, s"a close released by the grace after $windows windows")
            yield assert(dropped(outcome), s"a close whose peer never read again must drop its tail, got $outcome")
            end for
        }

        "an abortive release is seen by the peer as a reset, with no close_notify" - {

            eachBackendOnClock { (transport, control) =>
                val grace = 7.seconds
                for
                    (server, peer, _) <- stalledClose(transport, grace)
                    _                 <- advanceUntilReleased(server, control, grace, (timeout.toMillis / 100 / 2).toInt)
                    (_, ending)       <- peer.recvToEnd()
                    _ = assert(
                        ending match
                            case RawPeer.Read.Reset(_) => true
                            case _                     => false
                        ,
                        s"a release that dropped bytes must reset the connection, the peer saw $ending"
                    )
                    outcome <- releasedOutcome(server, "a close released by the grace")
                yield assert(dropped(outcome), s"the grace must drop the tail, got $outcome")
                end for
            }

            "TLS" - eachBackendTlsOnClock { (transport, control, serverTls, _) =>
                val grace    = 7.seconds
                val provider = serverTls.tlsProvider.getOrElse("")
                val peerTls  = if PeerTls.available(provider) then provider else "boringssl"
                if !PeerTls.available(peerTls) then cancel(s"no in-process TLS engine for the peer on this host ($provider, boringssl)")
                val accepted = Promise.Unsafe.init[Connection, Any]()
                val config   = NetConfig(channelCapacity = 512, closeFlushGrace = grace.grace)
                for
                    listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls, config)(conn =>
                        accepted.completeDiscard(Result.succeed(conn))
                    ).safe.get
                    _       <- Scope.ensure(Sync.defer(listener.close()))
                    raw     <- RawPeer.connect(listener.port, rcvBuf = Present(4096))
                    tls     <- PeerTls.client(raw, peerTls)
                    _       <- tls.handshake()
                    server  <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
                    stalled <- fillUntilStalled(server)
                    _ = assert(stalled.nonEmpty, "the write pump never parked against the peer that stopped reading")
                    _     <- Sync.defer(server.close())
                    _     <- advanceUntilReleased(server, control, grace, (timeout.toMillis / 100 / 2).toInt)
                    ended <- tls.recvToEnd()
                    _ = assert(
                        !ended.closeNotify,
                        "a release that dropped bytes sent close_notify, which tells the peer the stream is complete"
                    )
                    _ = assert(
                        ended.end match
                            case RawPeer.Read.Reset(_) => true
                            case _                     => false
                        ,
                        s"a release that dropped bytes must reset the connection, the peer saw ${ended.end}"
                    )
                    outcome <- releasedOutcome(server, "a TLS close released by the grace")
                yield assert(dropped(outcome), s"the grace must drop the tail, got $outcome")
                end for
            }
        }

        "a closing connection whose peer keeps reading is not cut by the grace" - eachBackendOnClock { (transport, control) =>
            val grace = 7.seconds
            // 8 MiB per window is more than the largest send buffer Linux and macOS grow by default (4 MiB) plus the peer's receive buffer, so
            // every window moves bytes from the server's queue into the kernel.
            val perWindow = 8 * 1024 * 1024
            val spans     = 40 * 1024 * 1024 / spanSize
            val accepted  = Promise.Unsafe.init[Connection, Any]()
            val config    = NetConfig(channelCapacity = spans + 1, closeFlushGrace = grace.grace)
            for
                listener <- transport.listen("127.0.0.1", 0, 16, config)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                peer     <- RawPeer.connect(listener.port)
                server   <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
                _        <- Kyo.foreachDiscard(0 until spans)(i => server.outbound.safe.put(Span.fromUnsafe(patterned(i))))
                _        <- Sync.defer(server.close())
                (received, ending) <- Loop(Chunk.empty[Array[Byte]], 0) { (acc, windows) =>
                    releasedOrArmed(server.isReleased, control, s"close-flush grace (window ${windows + 1})", grace).map { released =>
                        if released || windows > spans * spanSize / perWindow + 4 then
                            peer.recvToEnd().map((rest, last) => Loop.done((acc.append(rest).toArray.flatten, last)))
                        else
                            peer.recvExactly(perWindow).map { read =>
                                control.advance(grace).andThen(Loop.continue(acc.append(read), windows + 1))
                            }
                    }
                }
            yield
                val want = (0 until spans).toArray.flatMap(patterned)
                assert(
                    received.sameElements(want),
                    s"a peer that read every window received ${received.length} of ${want.length} bytes accepted before close()"
                )
                assert(ending == RawPeer.Read.Fin, s"the stream must end with a FIN once every byte left, got $ending")
            end for
        }

        "a pump that wakes with no byte reaching the kernel is released after one window" in onScriptedClock { (control, clock) =>
            val grace  = 5.seconds
            val driver = new ScriptedPeerDriver(tailBound = Present(4))
            val conn   = scripted(driver, clock, grace, 8)
            conn.close()
            for
                _ <- awaitTimer(control, "close-flush grace", grace)
                // The wake re-runs the write into the full tail: the pump parks again on a new instance and no byte reaches the kernel.
                _        <- Sync.defer(driver.signalWritable())
                _        <- control.advance(grace)
                released <- releasedOrArmed(conn.isReleased, control, "close-flush grace (window 2)", grace)
                _ = assert(released, "a window in which no byte reached the kernel must release, however often the pump woke")
                outcome <- releasedOutcome(conn, "a close whose pump moved no byte for a window")
            yield assert(
                outcome match
                    case Connection.CloseOutcome.Dropped(8, _) => true
                    case _                                     => false
                ,
                s"the 4 bytes in the tail and the 4 the pump held were dropped, got $outcome"
            )
            end for
        }

        "a tail count that rises and falls back inside a window is progress" in onScriptedClock { (control, clock) =>
            val grace  = 5.seconds
            val driver = new ScriptedPeerDriver(tailBound = Present(4))
            val conn   = InternalConnection.init[Unit]((), driver, 8, closeFlushGrace = grace, clock = clock)
            discard(conn.start())
            (0 until 3).foreach(_ => discard(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](4)(1)))))
            conn.close()
            for
                _ <- awaitTimer(control, "close-flush grace (window 1)", grace)
                // The kernel takes the whole tail and the pump refills it to the same count inside the window.
                _     <- Sync.defer(driver.peerReads(4))
                _     <- control.advance(grace)
                early <- releasedOrArmed(conn.isReleased, control, "close-flush grace (window 2)", grace)
                _ = assert(!early, "a window in which 4 bytes reached the kernel is progress, though the tail ends it as full as it began")
                _       <- Sync.defer { driver.peerReads(4); driver.peerReads(4) }
                outcome <- releasedOutcome(conn, "a close whose tail drained")
            yield
                assert(outcome == Connection.CloseOutcome.Flushed, s"every byte reached the kernel, yet released reported $outcome")
                assert(driver.ledger.sentTotal == 12, s"the kernel took ${driver.ledger.sentTotal} of 12 bytes")
            end for
        }

        "a second close() keeps the running grace window" in onScriptedClock { (control, clock) =>
            val grace  = 6.seconds
            val driver = new ScriptedPeerDriver(tailBound = Present(4))
            val conn   = scripted(driver, clock, grace, 8)
            conn.close()
            for
                // The first window is armed before the second close(), so the fence after it can only match a window that close() started.
                _        <- awaitTimer(control, "close-flush grace", grace)
                _        <- control.advance(3.seconds)
                _        <- Sync.defer(conn.close())
                _        <- control.advance(3.seconds)
                released <- releasedOrArmed(conn.isReleased, control, "close-flush grace restarted by the second close()", grace)
                _ = assert(
                    released,
                    "the window the first close() started must expire at its own deadline, not one the second close() restarted"
                )
                outcome <- releasedOutcome(conn, "a close whose first window expired")
            yield assert(dropped(outcome), s"no byte left during the window, got $outcome")
            end for
        }
    }

    "C4" - {

        "close() returns before the release and released completes after it" - eachBackendOnClock { (transport, _) =>
            val accepted = Promise.Unsafe.init[Connection, Any]()
            for
                listener <- transport.listen("127.0.0.1", 0, 16, NetConfig(channelCapacity = 64, closeFlushGrace = 5.seconds.grace))(conn =>
                    accepted.completeDiscard(Result.succeed(conn))
                ).safe.get
                _                  <- Scope.ensure(Sync.defer(listener.close()))
                peer               <- RawPeer.connect(listener.port, rcvBuf = Present(4096))
                server             <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
                spans              <- fillPatternedUntilStalled(server, 0)
                early              <- Sync.defer { server.close(); server.released.done() }
                (received, ending) <- peer.recvToEnd()
                outcome            <- releasedOutcome(server, "a close whose peer read to the end")
            yield
                val want = (0 until spans).toArray.flatMap(patterned)
                assert(!early, "released completed inside close(), while the pump still held bytes the peer had not read")
                assert(received.sameElements(want), s"received ${received.length} of ${want.length} bytes accepted before close()")
                assert(ending == RawPeer.Read.Fin, s"the stream must end with a FIN, got $ending")
                assert(outcome == Connection.CloseOutcome.Flushed, s"the peer read every byte, yet released reported $outcome")
            end for
        }

        "in-memory: released completes Flushed at close() with no reader" in {
            val (a, _) = InternalConnection.inMemoryPair()
            discard(a.outbound.offer(Span.fromUnsafe(Array.fill[Byte](16)(1))))
            a.close()
            val outcome = a.released.poll().map(_.map(_.eval))
            assert(
                outcome == Present(Result.succeed(Connection.CloseOutcome.Flushed)),
                s"an in-memory close has every accepted span in the peer's channel, so it is Flushed at once, got $outcome"
            )
        }
    }

    "X1" - {

        "a write after the peer reset fails the connection and released reports Dropped" - eachBackendOnClock { (transport, _) =>
            val accepted = Promise.Unsafe.init[Connection, Any]()
            val span     = Span.fromUnsafe(Array.fill[Byte](16 * 1024)(5))
            for
                listener <- transport.listen("127.0.0.1", 0, 16, NetConfig(channelCapacity = 1))(conn =>
                    accepted.completeDiscard(Result.succeed(conn))
                ).safe.get
                _      <- Scope.ensure(Sync.defer(listener.close()))
                peer   <- RawPeer.connect(listener.port)
                server <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
                _      <- Scope.ensure(Sync.defer(server.close()))
                // With the one-span inbound channel full and nobody taking, the read pump arms no read, so only a write can learn of the reset.
                _ <- Loop(0) { sent =>
                    if server.inbound.pendingPuts().getOrElse(0) > 0 then Loop.done(())
                    else if sent > 1024 then fail("the server's read pump never parked on its full inbound channel")
                    else peer.send(span).andThen(Async.sleep(1.millis)).andThen(Loop.continue(sent + 1))
                }
                _ <- peer.reset()
                _ <- Loop(0) { writes =>
                    if server.released.done() || writes > 1024 then Loop.done(())
                    else Abort.run[Closed](server.outbound.safe.put(span)).andThen(Async.sleep(1.millis)).andThen(Loop.continue(writes + 1))
                }
                outcome <- releasedOutcome(server, "a connection whose writes met the peer's reset")
            yield assert(dropped(outcome), s"a write the peer's reset refused must be reported as Dropped with its cause, got $outcome")
            end for
        }

        "a write accepted before a write error is reported in released as Dropped with the cause" - eachBackendOnClock { (transport, _) =>
            val accepted = Promise.Unsafe.init[Connection, Any]()
            for
                listener <- transport.listen("127.0.0.1", 0, 16, NetConfig(channelCapacity = 64))(conn =>
                    accepted.completeDiscard(Result.succeed(conn))
                ).safe.get
                _      <- Scope.ensure(Sync.defer(listener.close()))
                peer   <- RawPeer.connect(listener.port, rcvBuf = Present(4096))
                server <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
                _      <- Scope.ensure(Sync.defer(server.close()))
                spans  <- fillPatternedUntilStalled(server, 0)
                // The spans the channel holds and the one the pump holds were accepted; the reset fails the send they wait on.
                _       <- peer.reset()
                outcome <- releasedOutcome(server, "a connection whose parked send met the peer's reset")
            yield assert(
                outcome match
                    case Connection.CloseOutcome.Dropped(unsent, _) => unsent > 0
                    case _                                          => false
                ,
                s"the bytes accepted from $spans spans and never sent must be counted in Dropped, got $outcome"
            )
            end for
        }

        "a grace expiry reports the unsent bytes in released" - eachBackendOnClock { (transport, control) =>
            val grace = 7.seconds
            for
                (server, peer, offered) <- stalledClose(transport, grace)
                _                       <- advanceUntilReleased(server, control, grace, (timeout.toMillis / 100 / 2).toInt)
                outcome                 <- releasedOutcome(server, "a close released by the grace")
                (received, _)           <- peer.recvToEnd()
            yield outcome match
                case Connection.CloseOutcome.Dropped(unsent, cause) =>
                    assert(unsent > 0, s"a close the grace ended with bytes still queued reported no unsent bytes ($cause)")
                    assert(
                        received.length + unsent <= offered,
                        s"the peer received ${received.length} bytes and $unsent were reported unsent, more than the $offered accepted"
                    )
                case other => fail(s"a close whose peer never read again must drop its tail, got $other")
            end for
        }
    }

    "W2" - {

        "a record the engine produces while reading reaches the peer without an application write" - eachBackendTls {
            (transport, serverTls, _) =>
                if !PeerTls.available("boringssl") then cancel("the KeyUpdate peer needs a staged BoringSSL on this host")
                val accepted = Promise.Unsafe.init[Connection, Any]()
                for
                    listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls, NetConfig())(conn =>
                        accepted.completeDiscard(Result.succeed(conn))
                    ).safe.get
                    _      <- Scope.ensure(Sync.defer(listener.close()))
                    raw    <- RawPeer.connect(listener.port)
                    tls    <- PeerTls.client(raw, "boringssl")
                    _      <- tls.handshake()
                    server <- accepted.safe.get
                    _      <- Scope.ensure(Sync.defer(server.close()))
                    // A round trip first: what the server sends once the handshake ends (TLS 1.3 session tickets) is on the wire ahead of its
                    // answer, so after it nothing the server wrote is still in flight.
                    _    <- tls.send(Span.fromUnsafe("ping".getBytes("UTF-8")))
                    _    <- server.inbound.safe.take
                    _    <- server.outbound.safe.put(Span.fromUnsafe("pong".getBytes("UTF-8")))
                    pong <- recvPlain(tls, 4)
                    _ = assert(new String(pong, "UTF-8") == "pong", s"setup: the round trip read ${pong.length} bytes")
                    update <- tls.keyUpdate()
                    _      <- raw.send(update)
                    // The bound only turns an answer that never comes into a failure; the server writes nothing meanwhile.
                    reply  <- Clock.let(Clock.live)(Abort.run[Timeout](Async.timeout(armedBudget)(raw.recv())))
                    answer <- reply match
                        case Result.Success(RawPeer.Read.Bytes(span)) => tls.decrypt(span)
                        case Result.Success(ending)                   => fail(s"the stream ended with $ending after the peer's KeyUpdate")
                        case _                                        =>
                            fail(s"no record reached the peer within ${armedBudget.show} of its KeyUpdate, though the server wrote nothing")
                    _ = assert(
                        answer.plain.isEmpty && !answer.closeNotify,
                        s"the server's answer to the KeyUpdate carried data or an alert: $answer"
                    )
                    _     <- server.outbound.safe.put(Span.fromUnsafe("after".getBytes("UTF-8")))
                    after <- recvPlain(tls, 5)
                yield assert(new String(after, "UTF-8") == "after", "the peer decrypts what the server writes under its updated keys")
                end for
        }
    }

    /** Reads and decrypts until `n` plaintext bytes arrived. */
    private def recvPlain(tls: PeerTls, n: Int)(using Frame): Array[Byte] < Async =
        Loop(Chunk.empty[Array[Byte]], 0) { (acc, got) =>
            if got >= n then Loop.done(acc.toArray.flatten)
            else
                tls.peer.recv().map {
                    case RawPeer.Read.Bytes(span) =>
                        tls.decrypt(span).map(d => Loop.continue(acc.append(d.plain.toArray), got + d.plain.size))
                    case other => Abort.panic(new IllegalStateException(s"the stream ended after $got of $n plaintext bytes: $other"))
                }
        }

end ConnectionCloseContractTest
