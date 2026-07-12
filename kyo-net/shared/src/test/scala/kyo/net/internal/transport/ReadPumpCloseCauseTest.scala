package kyo.net.internal.transport

import kyo.*
import kyo.net.Test

/** R-003: `ReadPump.teardown` stops stringifying-and-dropping its reason. The structural cause is recorded on the owning [[Connection]]
  * (`readTeardownCause`), NEVER threaded through `kyo.Channel` (Channel gets zero new fields or members for this concern; the teardown
  * cause rides Connection, which already carries the channels, pumps, and state).
  *
  * Each leaf builds a real `Connection[Unit]` over a `CapturingDriver` that captures the read promise ReadPump arms via `awaitRead`, then
  * completes that promise directly to drive `ReadPump.onComplete` -> `teardown` with no real socket. ReadPump reuses ONE promise object
  * across reads (`becomeAvailable`, not a fresh promise per read, unlike WritePump), so `driver.armed` is captured once and completed
  * repeatedly to drive a multi-read scenario.
  */
class ReadPumpCloseCauseTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** A driver that captures the read promise ReadPump arms via `awaitRead`, so a test can complete it directly without any real socket.
      * Every other `IoDriver[Unit]` method is an inert stub: this test exercises only the read side.
      */
    final private class CapturingDriver extends IoDriver[Unit]:
        var armed: Maybe[Promise.Unsafe[ReadOutcome, Abort[Closed]]] = Absent
        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit =
            armed = Present(promise)
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit  = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                        = WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                      = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                                                 = ()
        def close()(using AllowUnsafe, Frame): Unit                                                                   = ()
        def label: String                                                                                             = "CapturingDriver"
        def handleLabel(handle: Unit): String                                                                         = "capturing"
    end CapturingDriver

    "a peer FIN records the structural teardown cause on the Connection before closeFn runs" in {
        val driver = new CapturingDriver
        val conn   = Connection.init[Unit]((), driver, 8)
        conn.start()
        assert(conn.readTeardownCause == Absent, "no teardown cause recorded before any read outcome")
        val armed = driver.armed.getOrElse(fail("ReadPump must arm a read on start()"))
        armed.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
        conn.readTeardownCause match
            case Present(cause) =>
                assert(cause.getMessage.contains("Transport"), s"resource must name Transport, got ${cause.getMessage}")
                assert(cause.getMessage.contains("PeerFin"), s"details must name PeerFin, got ${cause.getMessage}")
            case Absent => fail("readTeardownCause must be recorded after a PeerFin teardown")
        end match
        assert(conn.onClosing.done(), "closeFn must have run once the cause was recorded")
    }

    "a driver-produced Closed on the read promise's failure channel is recorded BY IDENTITY, never restated" in {
        val driver       = new CapturingDriver
        val conn         = Connection.init[Unit]((), driver, 8)
        val driverClosed = Closed("IoUringDriver", summon[Frame], "submit failed")
        conn.start()
        val armed = driver.armed.getOrElse(fail("ReadPump must arm a read on start()"))
        armed.completeDiscard(Result.fail(driverClosed))
        conn.readTeardownCause match
            case Present(cause) =>
                assert(
                    cause.asInstanceOf[AnyRef] eq driverClosed.asInstanceOf[AnyRef],
                    "the driver's own Closed must thread through by reference identity, never wrapped or restated"
                )
            case Absent => fail("readTeardownCause must be recorded after a driver-failure teardown")
        end match
        assert(conn.onClosing.done(), "closeFn must have run once the cause was recorded")
    }

    "the bytes the pump staged ahead of EOF are still takeable after the cause-carrying teardown, and the drained-out waiter sees " +
        "the channel's OWN synthesized Closed, never the recorded reason (Channel is untouched, zero new fields)" in {
            val driver = new CapturingDriver
            val conn   = Connection.init[Unit]((), driver, 8)
            conn.start()
            // ReadPump reuses one promise object across reads (becomeAvailable), so the same `armed` handle drives all three outcomes below.
            val armed = driver.armed.getOrElse(fail("ReadPump must arm a read on start()"))
            armed.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Array[Byte](1, 2, 3)))))
            armed.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(Array[Byte](4, 5)))))
            armed.completeDiscard(Result.succeed(ReadOutcome.PeerFin))

            // Both staged spans survive the teardown, in order: nothing is dropped as a discarded close() backlog.
            conn.inbound.poll() match
                case Result.Success(Present(span)) => assert(span.toArray.toList == List[Byte](1, 2, 3))
                case other                         => fail(s"the first staged span must survive the teardown, got $other")
            conn.inbound.poll() match
                case Result.Success(Present(span)) => assert(span.toArray.toList == List[Byte](4, 5))
                case other                         => fail(s"the second staged span must survive the teardown, got $other")

            // Post-drain: the channel is empty and closing. A further poll fails with the CHANNEL's OWN synthesized Closed ("Queue", from
            // kyo.Queue), never the recorded PeerFin reason: Channel carries no reason-threading hook (zero new fields/members).
            conn.inbound.poll() match
                case Result.Failure(closed) =>
                    assert(
                        closed.getMessage.contains("Queue"),
                        s"the drained-out waiter must see the channel's own Closed, got ${closed.getMessage}"
                    )
                    assert(
                        !closed.getMessage.contains("PeerFin"),
                        s"the channel's own Closed must NOT carry the recorded reason, got ${closed.getMessage}"
                    )
                case other => fail(s"a poll after drain must fail with the channel's synthesized Closed, got $other")
            end match

            // The structural cause R-003 exists to preserve is still available, on the Connection.
            conn.readTeardownCause match
                case Present(cause) => assert(cause.getMessage.contains("PeerFin"))
                case Absent         => fail("readTeardownCause must remain recorded on the Connection after the drain")
            end match
        }

end ReadPumpCloseCauseTest
