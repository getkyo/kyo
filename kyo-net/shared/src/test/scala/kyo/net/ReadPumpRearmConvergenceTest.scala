package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** LIVE-2: The pump converges to delivery under residual liveness cases: a trampoline-drop re-dispatch, a dropped ET edge, and a
  * short-submit-like window that forces multiple retry arms.
  *
  * These scenarios are all modeled through the same mechanism: the driver returns WouldBlock on an arm where data was nominally
  * available (the ET edge or event was dropped or the submit did not land), then delivers on the next arm. The pump must re-arm after
  * each WouldBlock and eventually deliver the bytes, proving that no re-arm is the last arm and no data is silently dropped.
  *
  * Two scenarios:
  *
  *   - **trampoline-resume**: WouldBlock on arm 1, bytes on arm 2. Models the `.map`-trampoline drop: the async dispatch re-queues
  *     and the second arm delivers. One retry suffices.
  *
  *   - **multi-transient-arms**: WouldBlock on arms 1-3, bytes on arm 4. Models a sequence of dropped ET edges or short-submit windows
  *     that each return empty. The pump must not stall after any of these; it re-arms each time and eventually converges.
  *
  * All drivers are synchronous mock implementations (inline callbacks, no real I/O). No Thread.sleep; all handoffs are via
  * synchronous inline promise completion.
  */
class ReadPumpRearmConvergenceTest extends Test:

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

    "LIVE2" - {

        // Scenario 1: WouldBlock on arm 1, bytes on arm 2.
        // Models the B2b trampoline drop: the first arm returns empty (the re-dispatch missed the data),
        // the second arm delivers. The pump must re-arm after WouldBlock and not stall.
        "trampoline-resume: one wouldblock then bytes delivered on re-arm" in {
            val deliveredBytes = Array[Byte](11, 22, 33)
            var armCount       = 0

            final class TrampolineDriver extends StubDriver:
                override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                    AllowUnsafe,
                    Frame
                ): Unit =
                    armCount += 1
                    armCount match
                        case 1 => promise.completeDiscard(Result.succeed(ReadOutcome.WouldBlock)) // trampoline drop
                        case 2 => promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(deliveredBytes))))
                        case _ => ()                                                              // subsequent arms: park
                    end match
                end awaitRead
            end TrampolineDriver

            val driver = new TrampolineDriver
            val conn   = Connection.init[Unit]((), driver, 8)
            conn.start()

            assert(armCount >= 2, s"trampoline-resume: pump must re-arm after WouldBlock; got $armCount arms")
            val polled = conn.inbound.poll()
            assert(
                polled match
                    case Result.Success(Maybe.Present(span)) => span.toArray.toList == deliveredBytes.toList
                    case _                                   => false,
                s"trampoline-resume: inbound must contain ${deliveredBytes.toList} after re-arm, got $polled"
            )
            assert(conn.isOpen, "trampoline-resume: connection must remain open after bytes delivery")
            succeed
        }

        // Scenario 2: WouldBlock on arms 1-3, bytes on arm 4.
        // Models a sequence of dropped ET edges or short-submit windows that each return empty.
        // The pump must not stall after any of these transient empty returns; it must re-arm each time
        // and eventually converge to delivery. This pins that a chain of empty arms does not strand the
        // read pump regardless of how many transient empties precede the real data.
        "multi-transient-arms: converges to delivery after three empty arms" in {
            val deliveredBytes = Array[Byte](44, 55, 66)
            val emptyArms      = 3
            var armCount       = 0

            final class MultiTransientDriver extends StubDriver:
                override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                    AllowUnsafe,
                    Frame
                ): Unit =
                    armCount += 1
                    if armCount <= emptyArms then
                        // Empty return: models dropped ET edge, short-submit window, or transient no-data.
                        promise.completeDiscard(Result.succeed(ReadOutcome.WouldBlock))
                    else if armCount == emptyArms + 1 then
                        promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(deliveredBytes))))
                    end if
                    // subsequent arms: park; the test is done
                end awaitRead
            end MultiTransientDriver

            val driver = new MultiTransientDriver
            val conn   = Connection.init[Unit]((), driver, 8)
            conn.start()

            assert(
                armCount >= emptyArms + 1,
                s"multi-transient-arms: pump must reach arm ${emptyArms + 1}; got $armCount"
            )
            val polled = conn.inbound.poll()
            assert(
                polled match
                    case Result.Success(Maybe.Present(span)) => span.toArray.toList == deliveredBytes.toList
                    case _                                   => false,
                s"multi-transient-arms: inbound must contain ${deliveredBytes.toList} after $emptyArms empty arms, got $polled"
            )
            assert(conn.isOpen, "multi-transient-arms: connection must remain open after convergence")
            succeed
        }
    }

end ReadPumpRearmConvergenceTest
