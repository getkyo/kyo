package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Verifies that write backpressure does not deadlock the inbound channel, that the ReadPump re-arms exactly once per drain cycle, and
  * (the last leaf) reproduces the read-backpressure peer-close gap behind the kyo-netJVM/test CLOSE_WAIT descriptor leak: a pump parked
  * on a full inbound channel arms no driver read, so a peer FIN is structurally unobservable and an unclosed connection is never reclaimed.
  *
  * When a write parks (AwaitingWritable), the connection's inbound channel must still be drainable. Backpressure on the write side must
  * not block the read side. This exercises the write-coupling discipline: the WritePump parks independently of the ReadPump.
  *
  * All driver callbacks are synchronous (inline), so the write-park, the inbound delivery, and the poll all happen within the
  * synchronous start/offer/poll call chain. No sleep or latch is needed: after conn.start() the inbound already has data (the read
  * driver delivers it inline), and after conn.outbound.offer the write pump is already parked (the write driver parks inline).
  */
class ReadPumpBackpressureTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    final private class ParkingWriteDriver extends IoDriver[Unit]:
        @volatile var captured: Boolean                                          = false
        var capturedWritable: Promise.Unsafe[Unit, Abort[Closed | NetException]] =
            null.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed | NetException]]]

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            // Deliver a span immediately to simulate inbound data arriving. This fires the ReadPump's
            // onComplete synchronously (inline), so inbound has data before start() returns.
            promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Array[Byte](99)))))
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
            capturedWritable = promise // park: never complete it in this test
            captured = true
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit   = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                                      =
            // Partial on first write to park the pump; Done on retry.
            if !captured then WriteResult.Partial(data, math.max(1, data.size / 2))
            else WriteResult.Done
        end write
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                             = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                        = ()
        def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit     = closeFd()
        def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
            try cancel(handle)
            finally releaseFd(handle, closeFd)
        def close()(using AllowUnsafe, Frame): Unit = ()
        def label: String                           = "ParkingWriteDriver"
        def handleLabel(handle: Unit): String       = "stub"
    end ParkingWriteDriver

    "write backpressure does not deadlock inbound" - {

        // Given: the inbound channel filled then drained
        // When: the write pump is parked (AwaitingWritable)
        // Then: inbound data is still deliverable (no deadlock between write backpressure and inbound)
        "backpressure-does-not-deadlock-inbound" in {
            val driver = new ParkingWriteDriver
            val conn   = Connection.init[Unit]((), driver, 8)
            // start() fires readPump and writePump. The readPump's driver.awaitRead delivers a span
            // immediately (inline), so inbound has data before start() returns.
            conn.start()

            // Offer a span to the outbound channel; the pump will take it, hit Partial, park.
            // This is synchronous: offer -> flush -> onTake fires -> doWrite -> Partial -> awaitWritable
            // sets driver.captured = true. All happens inside the offer call.
            val offerResult = conn.outbound.offer(Span.fromUnsafe(Array[Byte](1, 2, 3, 4)))
            assert(offerResult == Result.succeed(true), s"offer to outbound channel must succeed, got $offerResult")

            // driver.captured is set synchronously inside the offer call above (awaitWritable is
            // called inline). No sleep needed: the pump is already parked at this point.
            assert(driver.captured, "pump must park on writability after outbound offer")

            // While the write pump is parked, inbound must still be drainable.
            // awaitRead in the driver delivered a span synchronously during start(), so the ReadPump
            // placed it into conn.inbound before start() returned.
            val inboundResult  = conn.inbound.poll()
            val inboundHasData = inboundResult match
                case Result.Success(Maybe.Present(_)) => true
                case _                                => false

            assert(inboundHasData, s"inbound channel must be drainable even while write pump is parked, poll returned $inboundResult")
            succeed
        }

        // Given: the inbound channel receives one span and the ReadPump re-arms
        // When: the span is delivered and the pump calls requestNextRead
        // Then: exactly one awaitRead re-arm call fires (not zero, not two)
        //
        // This exercises the re-arm path in ReadPump.requestNextRead: after a Bytes delivery that
        // the channel accepts, the pump calls becomeAvailable() (resets the IOPromise) and then
        // driver.awaitRead exactly once. No batching, no double-arm.
        "read-rearm-exactly-once-per-drain" in {
            var awaitReadCalls = 0

            // Driver delivers one span on the first awaitRead call (the initial arm), then parks on
            // the second call (the re-arm after delivery). Parking on the second call prevents an
            // infinite loop and lets us observe the call count precisely.
            final class CountingReadDriver extends IoDriver[Unit]:
                def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
                def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                    awaitReadCalls += 1
                    if awaitReadCalls == 1 then
                        // Initial arm: deliver a span. The pump offers it to inbound and immediately
                        // calls requestNextRead -> awaitRead again (the re-arm, call #2).
                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Array[Byte](7)))))
                    end if
                    // Re-arm (awaitReadCalls == 2) and beyond: park. The pump waits for the next delivery.
                end awaitRead
                def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using
                    AllowUnsafe,
                    Frame
                ): Unit = ()
                def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using
                    AllowUnsafe,
                    Frame
                ): Unit = ()
                def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
                    ()
                def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult = WriteResult.Done
                def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                               = ()
                def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                          = ()
                def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit       = closeFd()
                def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit   =
                    try cancel(handle)
                    finally releaseFd(handle, closeFd)
                def close()(using AllowUnsafe, Frame): Unit = ()
                def label: String                           = "CountingReadDriver"
                def handleLabel(handle: Unit): String       = "stub"
            end CountingReadDriver

            val driver = new CountingReadDriver
            val conn   = Connection.init[Unit]((), driver, 8)
            conn.start()
            // Synchronous chain within start():
            // - ReadPump.start() -> awaitRead #1 (call #1) -> delivers Bytes([7])
            // - onComplete -> offerToChannel -> channel accepts -> requestNextRead -> awaitRead #2 (call #2, parks)

            assert(
                awaitReadCalls == 2,
                s"initial arm + exactly one re-arm expected after a single span delivery; got awaitReadCalls=$awaitReadCalls"
            )

            // The delivered span must be in the inbound channel.
            val polled = conn.inbound.poll()
            assert(
                polled match
                    case Result.Success(Maybe.Present(span)) => span.toArray.toList == List[Byte](7)
                    case _                                   => false,
                s"inbound must contain the delivered span [7]; got $polled"
            )

            // Polling does not trigger another re-arm (the pump is already parked waiting for the driver).
            assert(awaitReadCalls == 2, s"poll must not trigger additional re-arms; awaitReadCalls stayed at $awaitReadCalls")
            succeed
        }
    }

    "read backpressure leaves no peer-close signal (CLOSE_WAIT leak reproduction)" - {

        // The mechanism behind the kyo-netJVM/test CLOSE_WAIT descriptor leak, at the Connection level, backend-agnostic and deterministic.
        //
        // When the inbound channel fills, the ReadPump parks on an in-memory channel put (ReadPump.offerToChannel) and arms NO driver read.
        // IoDriver's only EOF path is `awaitRead` completing with ReadOutcome.PeerFin, under a one-read-per-handle contract, so a peer FIN that
        // arrives while the pump is backpressured is structurally unobservable through the read path. A parked pump therefore arms a
        // read-independent `awaitPeerClose` watch (exercised in the sibling section below). Here the connection uses the default
        // `peerCloseGrace = Infinity` (reclaim opt-out, so no watch is armed) and the spy driver leaves `awaitPeerClose` at its never-completing
        // default: the pump still stops arming reads at cap+1, and with no peer-close signal the handle is held, the behavior a backend without
        // the override retains.
        "a backpressured read arms no further read; with no peer-close detection the handle is held" in {
            val cap = 1
            final class BackpressureFinDriver extends IoDriver[Unit]:
                val awaitReadCalls                                             = AtomicInt.Unsafe.init(0)
                val closeHandleCalls                                           = AtomicInt.Unsafe.init(0)
                def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
                def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                    // Deliver one span per arm through the channel-filling arm: the first `cap` arms are accepted, arm cap+1 overflows and
                    // parks the pump on the put. No arm follows the overflow, so this never fires past cap+1 (the guard is defensive).
                    if awaitReadCalls.incrementAndGet() <= cap + 1 then
                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Array[Byte](1)))))
                def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using
                    AllowUnsafe,
                    Frame
                ): Unit = ()
                def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using
                    AllowUnsafe,
                    Frame
                ): Unit = ()
                def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
                    ()
                def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult = WriteResult.Done
                def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                               = ()
                def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = discard(closeHandleCalls.incrementAndGet())
                def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit     = closeFd()
                def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
                    try cancel(handle)
                    finally releaseFd(handle, closeFd)
                def close()(using AllowUnsafe, Frame): Unit = ()
                def label: String                           = "BackpressureFinDriver"
                def handleLabel(handle: Unit): String       = "stub"
            end BackpressureFinDriver

            val driver = new BackpressureFinDriver
            val conn   = Connection.init[Unit]((), driver, cap)
            conn.start()
            Sync.defer {
                // start() arms read #1 (delivers a span, the channel accepts, the pump re-arms #2); read #2 delivers the overflow span,
                // which the full channel rejects, so the pump parks on the put and arms no read #3.
                assert(
                    driver.awaitReadCalls.get() == cap + 1,
                    s"a backpressured ReadPump must stop arming reads at cap+1=${cap + 1}; got ${driver.awaitReadCalls.get()}"
                )
                // No read is armed and the default Infinity grace arms no watch, so the handle is held: the behavior a backend with no
                // peer-close detection keeps. The watch-driven reclaim is asserted in the sibling section with a driver that implements it.
                assert(
                    driver.closeHandleCalls.get() == 0,
                    s"with no peer-close detection the backpressured handle must be held (not reclaimed); closeHandle=${driver.closeHandleCalls.get()}"
                )
            }
        }
    }

    "peer-close grace reclaims an abandoned backpressured connection without harming a live one" - {

        /** A spy driver that fills a capacity-`cap` inbound channel (one span per read arm, distinct bytes so order is checkable), parks the pump
          * on the overflow, and holds the pump's peer-close watch. `closePeer` stands for the backend observing a FIN: it latches and completes
          * the held watch, so a watch registered after it completes at once. Counts `closeHandle` and the watch's registrations and withdrawals.
          */
        final class WatchDriver(cap: Int) extends IoDriver[Unit]:
            val awaitReadCalls                                                  = AtomicInt.Unsafe.init(0)
            val closeHandleCalls                                                = AtomicInt.Unsafe.init(0)
            val watches                                                         = AtomicInt.Unsafe.init(0)
            val cancels                                                         = AtomicInt.Unsafe.init(0)
            @volatile var peerClosed: Boolean                                   = false
            @volatile var watch: Maybe[Promise.Unsafe[Unit, Abort[Closed]]]     = Absent
            @volatile var lastWatch: Maybe[Promise.Unsafe[Unit, Abort[Closed]]] = Absent
            def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]      =
                Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
            def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                val n = awaitReadCalls.incrementAndGet()
                if n <= cap + 1 then promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Array[Byte](n.toByte)))))
            override def awaitPeerClose(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                discard(watches.incrementAndGet())
                lastWatch = Present(promise)
                if peerClosed then promise.completeDiscard(Result.succeed(()))
                else watch = Present(promise)
            end awaitPeerClose
            override def cancelPeerCloseWatch(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                if watch.exists(_.equals(promise)) then
                    discard(cancels.incrementAndGet())
                    watch = Absent
            def closePeer()(using AllowUnsafe): Unit =
                peerClosed = true
                val held = watch
                watch = Absent
                held.foreach(_.completeDiscard(Result.succeed(())))
            end closePeer
            def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit =
                ()
            def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit = ()
            def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed | NetException]])(using AllowUnsafe, Frame): Unit   = ()
            def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult = WriteResult.Done
            def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                               = ()
            def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                    = discard(closeHandleCalls.incrementAndGet())
            def releaseFd(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit = closeFd()
            def closeListener(handle: Unit, closeFd: () => Unit)(using AllowUnsafe, Frame): Unit =
                try cancel(handle)
                finally releaseFd(handle, closeFd)
            def close()(using AllowUnsafe, Frame): Unit = ()
            def label: String                           = "WatchDriver"
            def handleLabel(handle: Unit): String       = "stub"
        end WatchDriver

        // Abandoned case with a zero grace: the pump parks and watches, the peer FIN arrives, and the observed close reclaims at once. No timer
        // is armed at any point, so the reclaim needs no clock advance.
        "a peer FIN on a parked connection with a zero grace reclaims at once, arming no timer" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new WatchDriver(1)
                    val conn   = Connection.init[Unit]((), driver, channelCapacity = 1, grace = Duration.Zero, clock = clock)
                    conn.start()
                    driver.closePeer()
                    tc.registeredSleeps.map { sleeps =>
                        assert(
                            driver.watches.get() == 1,
                            s"a parked pump must watch for the peer's close once; watches=${driver.watches.get()}"
                        )
                        assert(sleeps == 0, s"a zero grace must arm no timer; sleeps=$sleeps")
                        assert(
                            driver.closeHandleCalls.get() == 1,
                            s"the observed close must reclaim the abandoned connection; closeHandle=${driver.closeHandleCalls.get()}"
                        )
                    }
                }
            }
        }

        // Abandoned case with a positive grace: the observed close arms exactly one timer of the grace, and its expiry reclaims.
        "a peer FIN on a parked connection arms one grace timer and reclaims when it expires" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new WatchDriver(1)
                    val conn   = Connection.init[Unit]((), driver, channelCapacity = 1, grace = 100.millis, clock = clock)
                    conn.start()
                    for
                        beforeClose <- tc.registeredSleeps
                        _ = driver.closePeer()
                        _     <- tc.advance(99.millis)
                        early <- Sync.defer(driver.closeHandleCalls.get())
                        _     <- tc.advance(1.millis)
                        after <- tc.registeredSleeps
                    yield
                        assert(beforeClose == 0, s"no timer may be armed before the peer closes; sleeps=$beforeClose")
                        assert(early == 0, s"the close must wait the grace for the consumer; closeHandle=$early")
                        assert(after == 1, s"the observed close must arm exactly one timer; sleeps=$after")
                        assert(
                            driver.closeHandleCalls.get() == 1,
                            s"the grace expiry must reclaim; closeHandle=${driver.closeHandleCalls.get()}"
                        )
                    end for
                }
            }
        }

        // Live-peer case: the pump parks and the peer stays open across many grace windows. Nothing polls, so no timer is ever armed and the
        // connection is held.
        "a parked connection whose peer stays open arms no timer and is not reclaimed" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new WatchDriver(1)
                    val conn   = Connection.init[Unit]((), driver, channelCapacity = 1, grace = 100.millis, clock = clock)
                    conn.start()
                    Loop.repeat(5)(tc.advance(100.millis)).andThen(tc.registeredSleeps).map { sleeps =>
                        assert(sleeps == 0, s"an open peer must arm no timer; sleeps=$sleeps")
                        assert(driver.watches.get() == 1, s"the parked pump watches once; watches=${driver.watches.get()}")
                        assert(
                            driver.closeHandleCalls.get() == 0,
                            s"an open peer must not reclaim; closeHandle=${driver.closeHandleCalls.get()}"
                        )
                    }
                }
            }
        }

        // A watch that completes after the consumer made progress belongs to a settled episode and must not reclaim.
        "a peer-close watch completing after consumer progress does not reclaim" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new WatchDriver(1)
                    val conn   = Connection.init[Unit]((), driver, channelCapacity = 1, grace = Duration.Zero, clock = clock)
                    conn.start()
                    val stale = driver.lastWatch
                    discard(conn.inbound.poll())
                    stale.foreach(_.completeDiscard(Result.succeed(())))
                    tc.advance(1.second).map { _ =>
                        assert(stale.nonEmpty, "the parked pump must have registered a watch")
                        assert(
                            driver.closeHandleCalls.get() == 0,
                            s"a stale watch must not reclaim; closeHandle=${driver.closeHandleCalls.get()}"
                        )
                    }
                }
            }
        }

        // Live-consumer case: the pump parks, the peer FIN arrives, but the consumer drains before the grace expires. Progress disarms the
        // timer synchronously on the take, so advancing fully past the grace still must NOT reclaim, and the overflow span is delivered.
        "a peer FIN with consumer progress within the grace does not reclaim and loses no bytes" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new WatchDriver(1)
                    val conn   = Connection.init[Unit]((), driver, channelCapacity = 1, grace = 10.seconds, clock = clock)
                    conn.start()       // channel = [1], overflow [2] parked, peer-close watch registered
                    driver.closePeer() // peer FIN observed: one grace timer armed
                    val a =
                        conn.inbound.poll() // take span 1; frees the slot, the parked overflow transfers, progress disarms synchronously
                    val b = conn.inbound.poll() // take span 2 (the overflow) -> proves it was not dropped
                    tc.advance(10.seconds).map { _ => // fully elapse the grace: the disarmed timer must still not reclaim
                        assert(a.exists(_.exists(_.toArray.sameElements(Array[Byte](1)))), s"first span must be delivered; got $a")
                        assert(
                            b.exists(_.exists(_.toArray.sameElements(Array[Byte](2)))),
                            s"the overflow span must be delivered, not dropped; got $b"
                        )
                        assert(
                            driver.closeHandleCalls.get() == 0,
                            s"consumer progress within the grace must NOT reclaim; closeHandle=${driver.closeHandleCalls.get()}"
                        )
                    }
                }
            }
        }

        // Progress withdraws the watch, so a backend releases whatever it holds for it (an io_uring poll, a probe read).
        "consumer progress withdraws the peer-close watch" in {
            val driver = new WatchDriver(1)
            val conn   = Connection.init[Unit]((), driver, channelCapacity = 1, grace = 100.millis)
            conn.start()
            discard(conn.inbound.poll())
            Sync.defer {
                assert(driver.watches.get() == 1, s"the parked pump must watch once; watches=${driver.watches.get()}")
                assert(driver.cancels.get() == 1, s"progress must withdraw the watch; cancels=${driver.cancels.get()}")
                assert(driver.watch.isEmpty, "no watch may stay registered once the pump progressed")
            }
        }
    }

end ReadPumpBackpressureTest
