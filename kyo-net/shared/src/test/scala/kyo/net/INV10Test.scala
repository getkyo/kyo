package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** INV-10: WouldBlock (EAGAIN) must not be conflated with PeerFin (orderly EOF).
  *
  * These are the two non-bytes outcomes a driver can deliver:
  *
  *   - `WouldBlock`: no data is ready on the socket. The pump MUST re-arm (call `awaitRead` again) and MUST NOT close the channel.
  *   - `PeerFin`: the peer closed its write side. The pump MUST tear down (close the inbound channel) and MUST NOT re-arm.
  *
  * ReadOutcomeTest covers the distinction at the type level. This test drives a real Connection with a counting mock driver, so the pump's
  * behavioral response is exercised end-to-end.
  *
  * Concretely: the driver delivers WouldBlock on the first call, then PeerFin on the second. The pump's response chain is synchronous
  * (inline callbacks), so the full lifecycle completes within `conn.start()`. Assertions follow.
  */
class INV10Test extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "INV10" - {

        // Given: an EAGAIN (WouldBlock) read followed by a peer-FIN read on the same connection.
        // When: each completes.
        // Then: WouldBlock causes exactly one re-arm (awaitRead called again); PeerFin causes teardown
        //       (connection closed, no further re-arm). The two outcomes are never conflated.
        "eof-distinguished-from-zero-length-non-eof" in {
            var awaitReadCalls = 0

            // Driver that delivers WouldBlock on the first awaitRead (EAGAIN: re-arm) and PeerFin
            // on the second (orderly EOF: teardown). Both completions are synchronous (inline within
            // awaitRead), so the full pump lifecycle completes within conn.start().
            //
            // If the pump incorrectly treated WouldBlock as a terminal outcome, it would not re-arm
            // and awaitReadCalls would stay at 1. If it incorrectly re-armed after PeerFin,
            // awaitReadCalls would exceed 2.
            final class EagainThenFinDriver extends IoDriver[Unit]:
                def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
                    Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
                def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
                    awaitReadCalls += 1
                    awaitReadCalls match
                        case 1 =>
                            // WouldBlock: EAGAIN, not EOF. Pump must re-arm (call awaitRead once more).
                            promise.completeDiscard(Result.succeed(ReadOutcome.WouldBlock))
                        case 2 =>
                            // PeerFin: orderly EOF. Pump must teardown, NOT re-arm.
                            promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                        case n =>
                            // A third call means the pump re-armed after PeerFin: that is a bug.
                            // Still complete to avoid a hung test, but the assert below will catch it.
                            promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                    end match
                end awaitRead
                def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
                def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit  = ()
                def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
                def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult = WriteResult.Done
                def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                               = ()
                def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                          = ()
                def close()(using AllowUnsafe, Frame): Unit                                            = ()
                def label: String                                                                      = "EagainThenFinDriver"
                def handleLabel(handle: Unit): String                                                  = "stub"
            end EagainThenFinDriver

            val driver = new EagainThenFinDriver
            val conn   = Connection.init[Unit]((), driver, 8)
            conn.start()
            // Synchronous chain after start():
            // - readPump.start() -> awaitRead #1 -> WouldBlock -> requestNextRead -> awaitRead #2
            // - awaitRead #2 -> PeerFin -> teardown() -> closeFn()
            // - closeFn: Established -> Closing -> Closed (outbound is empty, teardownHandle runs inline)

            assert(
                awaitReadCalls == 2,
                s"WouldBlock must cause exactly one re-arm; PeerFin must NOT cause a re-arm; " +
                    s"expected awaitReadCalls=2, got $awaitReadCalls"
            )
            assert(
                !conn.isOpen,
                "PeerFin must trigger teardown; connection must be closed after start() completes"
            )
            // WouldBlock and PeerFin carry no bytes; inbound must be empty (closed) after teardown.
            // poll() on a closed-and-empty channel returns a Closed failure, not data or Absent.
            val inboundPoll = conn.inbound.poll()
            assert(
                inboundPoll match
                    case Result.Failure(_: Closed) => true
                    case _                         => false,
                s"inbound must be closed after PeerFin teardown; got $inboundPoll"
            )
            succeed
        }
    }

end INV10Test
