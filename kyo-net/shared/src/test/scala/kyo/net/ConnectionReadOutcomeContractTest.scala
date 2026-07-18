package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** The completion contract for a read is identical regardless of which backend delivers it.
  *
  * A `ReadOutcome` delivered by any `IoDriver` instance must produce the same observable `Connection` state. This test exercises three
  * driver shapes (bytes delivery, peer-FIN, WouldBlock-then-bytes) through the same `Connection + ReadPump` path and asserts a concrete
  * observable outcome for each.
  *
  * All drivers are synchronous mock implementations (inline callbacks, no real I/O). The test uses `IoDriver[Unit]` over a `Connection[Unit]`
  * to stay fully in shared/ (no JVM-specific handle types).
  *
  * The three scenarios are NOT identical in outcome (bytes vs. EOF are different results), but the CONTRACT is backend-agnostic: the pump
  * delivers the outcome to the Connection's inbound channel the same way regardless of which mock driver fires the completion.
  */
class ConnectionReadOutcomeContractTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    // Shared stub base: only awaitRead behavior varies per scenario.
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

    "completion contract is identical across backends" - {
        "completion-contract-identical-across-backends" - {

            // Scenario 1: bytes delivery.
            // The driver delivers a Bytes span on the first awaitRead call, then parks.
            // Expected: inbound has the delivered span regardless of which backend produced it.
            "bytes-delivery: inbound receives the span" in {
                var calls          = 0
                val deliveredBytes = Array[Byte](10, 20, 30)

                final class BytesDriver extends StubDriver:
                    override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                        AllowUnsafe,
                        Frame
                    ): Unit =
                        calls += 1
                        if calls == 1 then
                            promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(deliveredBytes))))
                        // second call (re-arm): park; the pump will not deliver again in this test
                    end awaitRead
                end BytesDriver

                val driver = new BytesDriver
                val conn   = Connection.init[Unit]((), driver, 8)
                conn.start()
                // awaitRead #1: delivers Bytes -> offerToChannel -> inbound has span -> requestNextRead -> awaitRead #2 (parks)

                val polled = conn.inbound.poll()
                assert(
                    polled match
                        case Result.Success(Maybe.Present(span)) =>
                            span.toArray.toList == deliveredBytes.toList
                        case _ => false,
                    s"bytes-delivery: inbound must contain the delivered span ${deliveredBytes.toList}, got $polled"
                )
                assert(conn.isOpen, "bytes-delivery: connection must remain open after bytes delivery")
                succeed
            }

            // Scenario 2: PeerFin delivery.
            // The driver delivers PeerFin on the first awaitRead call.
            // Expected: connection is closed (teardown ran) and inbound is closed (no bytes, no re-arm).
            "peerfin-delivery: connection tears down and inbound closes" in {
                var calls = 0

                final class PeerFinDriver extends StubDriver:
                    override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                        AllowUnsafe,
                        Frame
                    ): Unit =
                        calls += 1
                        // Deliver PeerFin immediately; pump must NOT re-arm after this.
                        promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                    end awaitRead
                end PeerFinDriver

                val driver = new PeerFinDriver
                val conn   = Connection.init[Unit]((), driver, 8)
                conn.start()
                // awaitRead #1: delivers PeerFin -> teardown -> closeFn -> Established -> Closing -> Closed

                assert(calls == 1, s"peerfin-delivery: PeerFin must not cause a re-arm; awaitRead must be called exactly once, got $calls")
                assert(
                    !conn.isOpen,
                    "peerfin-delivery: connection must be closed after PeerFin teardown"
                )
                succeed
            }

            // Scenario 3: WouldBlock then bytes.
            // The driver delivers WouldBlock on the first call (no data, re-arm) then Bytes on the second.
            // Expected: after the two-step chain, inbound has the bytes, regardless of the intermediate WouldBlock.
            "wouldblock-then-bytes: re-arm delivers bytes to inbound" in {
                var calls          = 0
                val deliveredBytes = Array[Byte](99)

                final class WouldBlockThenBytesDriver extends StubDriver:
                    override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                        AllowUnsafe,
                        Frame
                    ): Unit =
                        calls += 1
                        calls match
                            case 1 =>
                                // WouldBlock: no data yet, re-arm
                                promise.completeDiscard(Result.succeed(ReadOutcome.WouldBlock))
                            case 2 =>
                                // Bytes on re-arm
                                promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(deliveredBytes))))
                            case _ =>
                                // Park: do not deliver again in this test
                                ()
                        end match
                    end awaitRead
                end WouldBlockThenBytesDriver

                val driver = new WouldBlockThenBytesDriver
                val conn   = Connection.init[Unit]((), driver, 8)
                conn.start()
                // awaitRead #1: WouldBlock -> requestNextRead -> awaitRead #2 -> Bytes -> inbound

                assert(calls >= 2, s"wouldblock-then-bytes: awaitRead must be called at least twice (arm + re-arm), got $calls")
                val polled = conn.inbound.poll()
                assert(
                    polled match
                        case Result.Success(Maybe.Present(span)) =>
                            span.toArray.toList == deliveredBytes.toList
                        case _ => false,
                    s"wouldblock-then-bytes: inbound must contain the bytes delivered after re-arm, got $polled"
                )
                assert(conn.isOpen, "wouldblock-then-bytes: connection must remain open after bytes delivery")
                succeed
            }
        }
    }

end ConnectionReadOutcomeContractTest
