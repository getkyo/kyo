package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** LIVE-1: A read is delivered by the wake path AND by the fallback re-arm path when the wake is suppressed.
  *
  * Two scenarios are exercised through the same Connection+ReadPump path using synchronous stub drivers:
  *
  *   - **wake-primary**: The driver delivers bytes on the first `awaitRead` arm (modeling the wake returning the park promptly). The
  *     pump must deliver the bytes to `inbound` in a single arm, with no retry required.
  *
  *   - **floor-recovery**: The driver returns WouldBlock on the first N arms (modeling a suppressed or delayed wake), then delivers
  *     bytes. The pump must not stall: it re-arms after each WouldBlock and eventually delivers. This pins that the retry loop (driven
  *     by the bounded fallback or the reassert backstop) recovers from N suppressed arms without permanently parking.
  *
  * All drivers are synchronous mock implementations (inline callbacks, no real I/O). No Thread.sleep; all handoffs are via promise
  * completion or synchronous inline callbacks.
  */
class LIVE1Test extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    abstract class StubDriver extends IoDriver[Unit]:
        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit  = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                        = WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                      = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                                                 = ()
        def close()(using AllowUnsafe, Frame): Unit                                                                   = ()
        def label: String                                                                                             = "StubDriver"
        def handleLabel(handle: Unit): String                                                                         = "stub"
    end StubDriver

    "LIVE1" - {

        // Scenario 1: bytes arrive on the first arm (wake-primary path).
        // The driver delivers bytes immediately; the pump must place them in inbound without re-arming.
        // This pins LIVE-1: the primary wake path delivers in one shot.
        "wake-primary: bytes delivered on first arm without retry" in {
            val deliveredBytes = Array[Byte](1, 2, 3)
            var armCount       = 0

            final class WakeDriver extends StubDriver:
                override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                    AllowUnsafe,
                    Frame
                ): Unit =
                    armCount += 1
                    if armCount == 1 then
                        // Deliver immediately (models wake returning the park with data).
                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(deliveredBytes))))
                    // second arm (re-arm after delivery): park; the test is done
                end awaitRead
            end WakeDriver

            val driver = new WakeDriver
            val conn   = Connection.init[Unit]((), driver, 8)
            conn.start()

            assert(armCount >= 1, s"wake-primary: awaitRead must be called at least once, got $armCount")
            val polled = conn.inbound.poll()
            assert(
                polled match
                    case Result.Success(Maybe.Present(span)) => span.toArray.toList == deliveredBytes.toList
                    case _                                   => false,
                s"wake-primary: inbound must contain ${deliveredBytes.toList}, got $polled"
            )
            assert(conn.isOpen, "wake-primary: connection must remain open after bytes delivery")
            succeed
        }

        // Scenario 2: wake is suppressed for the first N arms (WouldBlock), then bytes arrive.
        // This models the lost-wake class: the park returns empty (no data yet), the pump re-arms,
        // and eventually the data arrives. The bounded fallback / reassert backstop makes the re-arm
        // happen; the pump must not permanently stall.
        "rearm-convergence: bytes delivered after N suppressed arms" in {
            val deliveredBytes = Array[Byte](7, 8, 9)
            val suppressCount  = 4 // simulate 4 suppressed arms before the data arrives
            var armCount       = 0

            final class DelayedDriver extends StubDriver:
                override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                    AllowUnsafe,
                    Frame
                ): Unit =
                    armCount += 1
                    if armCount <= suppressCount then
                        // Return WouldBlock: no data yet (models suppressed wake / floor returning empty).
                        promise.completeDiscard(Result.succeed(ReadOutcome.WouldBlock))
                    else if armCount == suppressCount + 1 then
                        // Data arrives after N suppressed arms.
                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(deliveredBytes))))
                    end if
                    // subsequent arms: park; the test is done
                end awaitRead
            end DelayedDriver

            val driver = new DelayedDriver
            val conn   = Connection.init[Unit]((), driver, 8)
            conn.start()

            assert(
                armCount >= suppressCount + 1,
                s"floor-recovery: pump must re-arm at least ${suppressCount + 1} times; got $armCount"
            )
            val polled = conn.inbound.poll()
            assert(
                polled match
                    case Result.Success(Maybe.Present(span)) => span.toArray.toList == deliveredBytes.toList
                    case _                                   => false,
                s"floor-recovery: inbound must contain ${deliveredBytes.toList} after $suppressCount suppressed arms, got $polled"
            )
            assert(conn.isOpen, "floor-recovery: connection must remain open after bytes delivery")
            succeed
        }
    }

end LIVE1Test
