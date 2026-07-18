package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** No backend permanently stalls a live connection when the first event is lost or delayed.
  *
  * Each backend has a class of "lost event": a suppressed wake (io_uring), a dropped interest bit (NIO), a kqueue EV_CLEAR edge that
  * fires before the consumer is ready (kqueue). In each case the contract is: after the lost event the I/O loop retries and the
  * connection eventually makes progress. At the stub-driver level this class of failure is modeled as a WouldBlock return on the first
  * arm followed by actual data on a subsequent arm.
  *
  * Three scenarios, each sharing the same mechanism but varying the number of suppressed arms:
  *
  *   - **one suppressed arm**: the minimal lost-event case.
  *   - **two suppressed arms**: a sequence of two lost events before data arrives.
  *   - **write path independent of read stall**: a write succeeds even when the read arm is repeatedly suppressed, proving the two
  *     pumps do not block each other.
  *
  * None of these scenarios use the real I/O backends; they drive the Connection+ReadPump state machine through synchronous stub
  * drivers, proving the pump's re-arm contract is backend-agnostic. Real-backend liveness (the indefinite park + wake returning it)
  * is exercised by the transport integration tests; this test pins the state-machine layer.
  *
  * All drivers are synchronous mock implementations (inline callbacks, no real I/O). No Thread.sleep.
  */
class ConnectionLostEventLivenessTest extends Test:

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

    "no permanent stall on a lost event" - {
        "no-permanent-stall-on-lost-event" - {

            // Scenario 1: one suppressed arm (WouldBlock) before data arrives.
            // The minimal lost-event case: the first arm returns empty, the second delivers.
            // Assert: no stall, bytes delivered, connection remains open.
            "one-suppressed-arm: recovers and delivers" in {
                val data     = Array[Byte](1, 2, 3)
                var armCount = 0

                final class OneSuppressDriver extends StubDriver:
                    override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                        AllowUnsafe,
                        Frame
                    ): Unit =
                        armCount += 1
                        armCount match
                            case 1 => promise.completeDiscard(Result.succeed(ReadOutcome.WouldBlock))
                            case 2 => promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(data))))
                            case _ => ()
                        end match
                    end awaitRead
                end OneSuppressDriver

                val driver = new OneSuppressDriver
                val conn   = Connection.init[Unit]((), driver, 8)
                conn.start()

                assert(armCount >= 2, s"one-suppressed-arm: pump must re-arm at least twice; got $armCount")
                val polled = conn.inbound.poll()
                assert(
                    polled match
                        case Result.Success(Maybe.Present(span)) => span.toArray.toList == data.toList
                        case _                                   => false,
                    s"one-suppressed-arm: inbound must contain ${data.toList}, got $polled"
                )
                assert(conn.isOpen, "one-suppressed-arm: connection must remain open")
                succeed
            }

            // Scenario 2: two suppressed arms (WouldBlock x 2) before data arrives.
            // A sequence of two lost events; the pump must re-arm after each one and converge.
            "two-suppressed-arms: recovers after two empty returns" in {
                val data     = Array[Byte](4, 5, 6)
                var armCount = 0

                final class TwoSuppressDriver extends StubDriver:
                    override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                        AllowUnsafe,
                        Frame
                    ): Unit =
                        armCount += 1
                        armCount match
                            case 1 | 2 => promise.completeDiscard(Result.succeed(ReadOutcome.WouldBlock))
                            case 3     => promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(data))))
                            case _     => ()
                        end match
                    end awaitRead
                end TwoSuppressDriver

                val driver = new TwoSuppressDriver
                val conn   = Connection.init[Unit]((), driver, 8)
                conn.start()

                assert(armCount >= 3, s"two-suppressed-arms: pump must reach arm 3; got $armCount")
                val polled = conn.inbound.poll()
                assert(
                    polled match
                        case Result.Success(Maybe.Present(span)) => span.toArray.toList == data.toList
                        case _                                   => false,
                    s"two-suppressed-arms: inbound must contain ${data.toList}, got $polled"
                )
                assert(conn.isOpen, "two-suppressed-arms: connection must remain open")
                succeed
            }

            // Scenario 3: write path is independent of suppressed read arms.
            // Even when the read pump is repeatedly suppressed (WouldBlock on reads), a queued write
            // must still be delivered to the driver. This pins that the two pumps do not block each other:
            // a stalled read never prevents a write from completing.
            "write-independent-of-suppressed-read: write delivers despite two empty read arms" in {
                val readData     = Array[Byte](7, 8, 9)
                val writeData    = Array[Byte](10, 11, 12)
                var readArmCount = 0
                var writtenBytes = List.empty[Byte]

                final class SuppressedReadWriteDriver extends StubDriver:
                    override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                        AllowUnsafe,
                        Frame
                    ): Unit =
                        readArmCount += 1
                        readArmCount match
                            case 1 | 2 => promise.completeDiscard(Result.succeed(ReadOutcome.WouldBlock))
                            case 3     => promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(readData))))
                            case _     => ()
                        end match
                    end awaitRead
                    override def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
                        writtenBytes = data.slice(offset, data.size).toArray.toList
                        WriteResult.Done
                end SuppressedReadWriteDriver

                val driver = new SuppressedReadWriteDriver
                val conn   = Connection.init[Unit]((), driver, 8)

                // Pre-queue a write so WritePump can drain it immediately on start.
                discard(conn.outbound.offer(Span.fromUnsafe(writeData)))

                conn.start()

                // Write must have landed regardless of the suppressed read arms.
                assert(
                    writtenBytes == writeData.toList,
                    s"write-independent: driver.write must have seen ${writeData.toList} despite suppressed reads, got $writtenBytes"
                )
                // Read still delivers after recovery.
                val polled = conn.inbound.poll()
                assert(
                    polled match
                        case Result.Success(Maybe.Present(span)) => span.toArray.toList == readData.toList
                        case _                                   => false,
                    s"write-independent: inbound must contain ${readData.toList}, got $polled"
                )
                assert(conn.isOpen, "write-independent: connection must remain open after both paths converge")
                succeed
            }
        }
    }

end ConnectionLostEventLivenessTest
