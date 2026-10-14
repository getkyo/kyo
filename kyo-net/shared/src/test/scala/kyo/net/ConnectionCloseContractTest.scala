package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection as InternalConnection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
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

    /** Offers spans until the outbound channel is full and the write pump is parked on a socket the peer no longer drains. */
    private def fillUntilStalled(conn: InternalConnection[?])(using Frame): Boolean < Async =
        val span = Span.fromUnsafe(Array.fill[Byte](64 * 1024)(7))
        Loop(0) { offered =>
            if offered > 16 * 1024 then Loop.done(false)
            else
                conn.outbound.offer(span) match
                    case Result.Success(true)                            => Loop.continue(offered + 1)
                    case Result.Success(false) if parkedOnWritable(conn) => Loop.done(true)
                    case Result.Success(false)                           => Async.sleep(1.millis).andThen(Loop.continue(offered))
                    case _                                               => Loop.done(false)
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
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                             = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                        = released.set(true)
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
    }

    "C2" - {

        "the close-flush grace runs on the transport's clock" - eachBackendOnClock { (transport, control) =>
            val grace             = 7.seconds
            val nonTerminationCap = (timeout.toMillis / 100 / 2).toInt
            val accepted          = Promise.Unsafe.init[Connection, Any]()
            // A parked pump does not prove the send buffer is full: io_uring parks on its own unsent-tail bound while a send is in flight and
            // the kernel can still take bytes. A loopback send buffer grows to megabytes, so a default-depth queue (256 KiB) can flush whole
            // after the close and release with no window. A 32 MiB queue is beyond the largest send buffer Linux and macOS grow by default
            // (4 MiB).
            val config = NetConfig(channelCapacity = 512, closeFlushGrace = grace.grace)
            for
                listener <- transport.listen("127.0.0.1", 0, 16, config) { conn =>
                    accepted.completeDiscard(Result.succeed(conn))
                }.safe.get
                _       <- Scope.ensure(Sync.defer(listener.close()))
                _       <- RawPeer.connect(listener.port, rcvBuf = Present(4096))
                server  <- accepted.safe.get.map(_.asInstanceOf[InternalConnection[?]])
                stalled <- fillUntilStalled(server)
                _ = assert(stalled, "the write pump never parked against the peer that stopped reading")
                _ <- Sync.defer(server.close())
                // Kernel timing decides how many windows still see bytes leave, so the leaf asserts that every window is timed on the transport's
                // clock and that the release comes; the cap turns a release that never comes into a failure carrying the count.
                windows <- Loop(0) { windows =>
                    if windows >= nonTerminationCap then Loop.done(windows)
                    else
                        releasedOrArmed(server.isReleased, control, s"close-flush grace (window ${windows + 1})", grace).map { released =>
                            if released then Loop.done(windows)
                            else control.advance(grace).andThen(Loop.continue(windows + 1))
                        }
                }
            yield
                assert(windows >= 1, "the close released a pump parked on unsent bytes before any grace window")
                assert(
                    server.isReleased,
                    s"a close whose peer stopped reading must be released by the grace; still held after $windows windows"
                )
            end for
        }
    }

end ConnectionCloseContractTest
