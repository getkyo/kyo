package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Verifies that write backpressure does not deadlock the inbound channel, and that the ReadPump
  * re-arms exactly once per drain cycle.
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

end ReadPumpBackpressureTest
