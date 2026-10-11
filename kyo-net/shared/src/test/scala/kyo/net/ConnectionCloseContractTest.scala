package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult
import kyo.net.internal.transport.WriteState

/** The connection close contract for the shared transport layer: what `close()` flushes, and which teardown sources may release a handle.
  * Leaves run over every backend through the public transport where the window is reachable there, and over the internal connection with a
  * scripted driver where it is not.
  */
class ConnectionCloseContractTest extends Test:

    import AllowUnsafe.embrace.danger

    private val spanSize = 64 * 1024

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

    private def describe(received: Array[Byte], want: Array[Byte]): String =
        val firstDiff = received.indices.find(i => i >= want.length || received(i) != want(i))
        s"received ${received.length} of ${want.length} bytes${firstDiff.fold("")(i => s", first difference at $i")}"

    /** A server connection accepted on `transport` and a client connected to it, both closed at scope exit. */
    private def pair(transport: Transport)(using Frame): (Connection, Connection) < (Async & Abort[NetException] & Scope) =
        val accepted = Promise.Unsafe.init[Connection, Any]()
        for
            listener <- transport.listen("127.0.0.1", 0, 128)(conn => accepted.completeDiscard(Result.succeed(conn))).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            client   <- transport.connect("127.0.0.1", listener.port).safe.get
            _        <- Scope.ensure(Sync.defer(client.close()))
            server   <- accepted.safe.get
            _        <- Scope.ensure(Sync.defer(server.close()))
        yield (server, client)
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

    /** A driver whose every write completes at once, recording any write that lands after the handle was released. */
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
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                             = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                        = released.set(true)
        def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit     = closeFd()
        def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
            try cancel(handle)
            finally releaseFd(handle, closeFd)
        def close()(using AllowUnsafe, Frame): Unit = ()
        def label: String                           = "ReleaseOrderDriver"
        def handleLabel(handle: Unit): String       = "release-order"
    end ReleaseOrderDriver

    "C1: close flushes every accepted byte" - {

        "a second close() during the flush leaves the flush to finish" - eachBackendOnVirtualClock { (transport, _) =>
            for
                (s, client) <- pair(transport)
                server = s.asInstanceOf[InternalConnection[?]]
                first    <- fillUntilStalled(server, 0)
                _        <- awaitState(readPumpParked(client))
                spans    <- fillUntilStalled(server, first)
                _        <- Sync.defer { server.close(); server.close() }
                received <- drainToEnd(client)
            yield
                val want = expected(spans)
                assert(received.sameElements(want), s"every accepted byte must reach the peer in order: ${describe(received, want)}")
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

            "a second close()" in {
                scenario((conn, _) => conn.close())
            }

            "a peer byte" in {
                scenario((_, driver) => driver.deliver(ReadOutcome.Bytes(Span(9.toByte))))
            }

            "a peer FIN" in {
                scenario((_, driver) => driver.deliver(ReadOutcome.PeerFin))
            }
        }

        // The cap only turns a deadlock into a failure: the rounds cost 20s on a local Native build, which a slow runner can push past the
        // module's 60s budget.
        "a span offered concurrently with close() is written before the release, or refused".timeout(10.minutes) in {
            if kyo.internal.Platform.isJS || kyo.internal.Platform.isWasm then
                cancel("a single-threaded runtime cannot interleave the offer and the close")
            else
                // The window is a few instructions wide, so rounds of parallel races are driven until one hits it or the bound passes.
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

        "in-memory: the peer receives every byte written before close()" in {
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

        "in-memory: close() leaves its own reader every byte the peer wrote before it, and refuses later peer writes" in {
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
    }

    "C2: close is bounded by closeFlushGrace" - {

        "a window with write progress re-arms the grace, and the first full window with none releases" in {
            Clock.withTimeControl { control =>
                Clock.get.map { clock =>
                    val grace  = 1.second
                    val driver = new ScriptedPeerDriver
                    val conn   = InternalConnection.init[Unit]((), driver, 8, closeFlushGrace = grace, clock = clock)
                    discard(conn.start())
                    discard(conn.outbound.offer(Span.fromUnsafe(Array.fill[Byte](4)(1))))
                    conn.close()
                    for
                        _ <- control.awaitPendingSleeper(grace)
                        _ <- Sync.defer(driver.peerReads())
                        _ <- control.advance(grace)
                        afterProgress = driver.closeHandleCalls.get()
                        _ <- control.awaitPendingSleeper(grace)
                        _ <- control.advance(grace)
                    yield
                        assert(afterProgress == 0, s"a window in which a byte was written must not release; closeHandle=$afterProgress")
                        assert(
                            driver.closeHandleCalls.get() == 1,
                            s"the first full window with no write progress must release; closeHandle=${driver.closeHandleCalls.get()}"
                        )
                        assert(driver.written.get() == 2, s"nothing may be written after the release; writes=${driver.written.get()}")
                        assert(!conn.isOpen, "the released connection must read as closed")
                    end for
                }
            }
        }
    }

end ConnectionCloseContractTest
