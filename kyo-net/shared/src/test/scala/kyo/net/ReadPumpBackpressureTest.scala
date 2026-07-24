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
        @volatile var captured: Boolean                           = false
        var capturedWritable: Promise.Unsafe[Unit, Abort[Closed]] = null.asInstanceOf[Promise.Unsafe[Unit, Abort[Closed]]]

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            // Deliver a span immediately to simulate inbound data arriving. This fires the ReadPump's
            // onComplete synchronously (inline), so inbound has data before start() returns.
            promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Array[Byte](99)))))
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            capturedWritable = promise // park: never complete it in this test
            captured = true
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit   = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
            // Partial on first write to park the pump; Done on retry.
            if !captured then WriteResult.Partial(data, math.max(1, data.size / 2))
            else WriteResult.Done
        end write
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit      = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = ()
        def close()(using AllowUnsafe, Frame): Unit                   = ()
        def label: String                                             = "ParkingWriteDriver"
        def handleLabel(handle: Unit): String                         = "stub"
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
            val inboundResult = conn.inbound.poll()
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
                def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
                def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit  = ()
                def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
                def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult = WriteResult.Done
                def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                               = ()
                def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                          = ()
                def close()(using AllowUnsafe, Frame): Unit                                            = ()
                def label: String                                                                      = "CountingReadDriver"
                def handleLabel(handle: Unit): String                                                  = "stub"
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
        // arrives while the pump is backpressured is structurally unobservable through the read path. The fix adds a read-independent
        // `isPeerClosed` state test the grace timer polls on expiry (exercised in the sibling section below). Here the connection uses the default
        // `peerCloseGrace = Infinity` (reclaim opt-out, so no grace timer is armed) and the spy driver leaves `isPeerClosed` at its no-op default
        // (false): the pump still stops arming reads at cap+1 (model unchanged), and with no grace timer and no peer-close signal the handle is
        // held, the pre-guard behavior a backend without the override retains.
        "a backpressured read arms no further read; with no peer-close detection the handle is held" in {
            val cap = 1
            final class BackpressureFinDriver extends IoDriver[Unit]:
                val awaitReadCalls   = AtomicInt.Unsafe.init(0)
                val closeHandleCalls = AtomicInt.Unsafe.init(0)
                def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
                def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                    // Deliver one span per arm through the channel-filling arm: the first `cap` arms are accepted, arm cap+1 overflows and
                    // parks the pump on the put. No arm follows the overflow, so this never fires past cap+1 (the guard is defensive).
                    if awaitReadCalls.incrementAndGet() <= cap + 1 then
                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Array[Byte](1)))))
                def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
                def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit  = ()
                def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
                def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult = WriteResult.Done
                def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                               = ()
                def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = discard(closeHandleCalls.incrementAndGet())
                def close()(using AllowUnsafe, Frame): Unit                   = ()
                def label: String                                             = "BackpressureFinDriver"
                def handleLabel(handle: Unit): String                         = "stub"
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
                // No read is armed, the default Infinity grace arms no timer, and the spy driver's isPeerClosed stays false, so the handle is
                // held: the behavior a backend with no peer-close detection keeps. The grace-driven reclaim is asserted in the sibling section
                // with a driver that implements isPeerClosed.
                assert(
                    driver.closeHandleCalls.get() == 0,
                    s"with no peer-close detection the backpressured handle must be held (not reclaimed); closeHandle=${driver.closeHandleCalls.get()}"
                )
            }
        }
    }

    "peer-close grace reclaims an abandoned backpressured connection without harming a live one" - {

        /** A spy driver that fills a capacity-`cap` inbound channel (one span per read arm, distinct bytes so order is checkable), parks the pump
          * on the overflow, and exposes a `peerClosed` latch the test flips to simulate a FIN. `isPeerClosed` reads that latch (the poll-on-expiry
          * grace polls it on each timer expiry). Counts `closeHandle`.
          */
        final class WatchDriver(cap: Int) extends IoDriver[Unit]:
            val awaitReadCalls                = AtomicInt.Unsafe.init(0)
            val closeHandleCalls              = AtomicInt.Unsafe.init(0)
            @volatile var peerClosed: Boolean = false
            def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
            def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                val n = awaitReadCalls.incrementAndGet()
                if n <= cap + 1 then promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Array[Byte](n.toByte)))))
            override def isPeerClosed(handle: Unit)(using AllowUnsafe, Frame): Boolean                                    = peerClosed
            def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
            def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit  = ()
            def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
            def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                        = WriteResult.Done
            def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                      = ()
            def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit = discard(closeHandleCalls.incrementAndGet())
            def close()(using AllowUnsafe, Frame): Unit                   = ()
            def label: String                                             = "WatchDriver"
            def handleLabel(handle: Unit): String                         = "stub"
        end WatchDriver

        // Abandoned case: the pump parks, the peer FIN arrives while parked, the consumer never drains. Advancing past the grace fires the expiry,
        // which polls isPeerClosed and reclaims. Time is controlled so the expiry is deterministic, not a real-time wait.
        "a peer FIN with no consumer progress reclaims after the grace elapses" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new WatchDriver(1)
                    val conn   = Connection.init[Unit]((), driver, channelCapacity = 1, grace = 100.millis, clock = clock)
                    conn.start()             // fills the channel, parks on the overflow put, arms the grace timer
                    driver.peerClosed = true // peer FIN: the grace expiry polls isPeerClosed and observes it
                    tc.advance(100.millis).map { _ =>
                        assert(
                            driver.closeHandleCalls.get() == 1,
                            s"the grace must reclaim an abandoned backpressured connection; closeHandle=${driver.closeHandleCalls.get()}"
                        )
                    }
                }
            }
        }

        // Live-consumer case: the pump parks, the peer FIN arrives, but the consumer drains before any expiry. Progress disarms the timer
        // synchronously on the take, so advancing fully past the grace still must NOT reclaim, and the overflow span the pump held is delivered.
        "a peer FIN with consumer progress within the grace does not reclaim and loses no bytes" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new WatchDriver(1)
                    val conn   = Connection.init[Unit]((), driver, channelCapacity = 1, grace = 10.seconds, clock = clock)
                    conn.start()             // channel = [1], overflow [2] parked, grace timer armed on park
                    driver.peerClosed = true // peer FIN latched: the drain below disarms the grace before any expiry polls it
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

        // Re-arm case: the first expiry finds the peer still live (isPeerClosed false), so it re-arms instead of reclaiming; the next expiry, after
        // the peer closes, reclaims. Proves a non-reclaiming expiry keeps waiting rather than settling the episode.
        "a grace expiry with the peer still live re-arms, and reclaims only once the peer closes" in {
            Clock.withTimeControl { tc =>
                Clock.get.map { clock =>
                    val driver = new WatchDriver(1)
                    val conn   = Connection.init[Unit]((), driver, channelCapacity = 1, grace = 100.millis, clock = clock)
                    conn.start()                      // parks on the overflow put, arms the grace timer; peer still live (peerClosed=false)
                    tc.advance(100.millis).map { _ => // first expiry: isPeerClosed false -> re-arm, no reclaim
                        assert(
                            driver.closeHandleCalls.get() == 0,
                            s"a live peer must not reclaim on expiry; closeHandle=${driver.closeHandleCalls.get()}"
                        )
                        driver.peerClosed = true          // peer FIN now arrives
                        tc.advance(100.millis).map { _ => // second expiry: isPeerClosed true -> reclaim
                            assert(
                                driver.closeHandleCalls.get() == 1,
                                s"the re-armed timer must reclaim once the peer closes; closeHandle=${driver.closeHandleCalls.get()}"
                            )
                        }
                    }
                }
            }
        }
    }

end ReadPumpBackpressureTest
