package kyo.net

import kyo.*
import kyo.net.internal.transport.Connection
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** XPLAT-3: concurrent read delivery and write flush are independent through the Connection pumps.
  *
  * A `Connection` drives two separate pumps: `ReadPump` (socket → inbound channel) and `WritePump` (outbound channel → socket). The
  * invariant is that the read path and the write path are independent: a write does not block a read, a read does not discard or reorder
  * writes, and a peer-close (PeerFin) on the read side does not prevent a queued write from being flushed.
  *
  * This test exercises three driver shapes where reads and writes coexist:
  *
  *   - **simultaneous flush**: write data pre-queued before start; ReadPump delivers bytes; WritePump flushes the queued span. Both complete
  *     correctly without interference.
  *   - **ordered multi-write**: two write spans queued before start; ReadPump parks; WritePump drains both in submission order. Verifies the
  *     FIFO ordering contract over the outbound channel.
  *   - **write after PeerFin**: PeerFin closes inbound while a write is queued. The write must still be flushed (inbound close does not
  *     discard pending outbound).
  *
  * All drivers are synchronous mock implementations (inline callbacks, no real I/O). The test uses `IoDriver[Unit]` over a
  * `Connection[Unit]` so it stays fully in `shared/` (no JVM-specific handle types), matching the same scope as `XPLAT1Test`.
  */
class XPLAT3Test extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    // Shared stub base: awaitRead and write behaviors vary per scenario.
    abstract class StubDriver extends IoDriver[Unit]:
        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Promise.Unsafe.init[Unit, Any]().asInstanceOf[Fiber.Unsafe[Unit, Any]]
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit  = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                      = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                                                 = ()
        def close()(using AllowUnsafe, Frame): Unit                                                                   = ()
        def label: String                                                                                             = "StubDriver"
        def handleLabel(handle: Unit): String                                                                         = "stub"
    end StubDriver

    "XPLAT3" - {
        "concurrent-read-write-independence" - {

            // Scenario 1: write span pre-queued before start; ReadPump delivers bytes on its first arm.
            // Expected: ReadPump delivers the read bytes to inbound AND WritePump flushes the queued span
            // through driver.write without interference.
            "simultaneous-flush: read delivers to inbound and write flushes via driver.write" in {
                val writeData    = Array[Byte](1, 2, 3)
                val readData     = Array[Byte](10, 20, 30)
                var writtenBytes = List.empty[Byte]
                var readCalls    = 0

                final class SimultaneousDriver extends StubDriver:
                    override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                        AllowUnsafe,
                        Frame
                    ): Unit =
                        readCalls += 1
                        if readCalls == 1 then
                            // Deliver bytes immediately; WritePump is concurrently draining the outbound queue.
                            promise.completeDiscard(Result.succeed(ReadOutcome.Bytes(Span.fromUnsafe(readData))))
                        // second call (re-arm): park so the test can assert without the pump looping
                    end awaitRead
                    override def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
                        writtenBytes = data.slice(offset, data.size).toArray.toList
                        WriteResult.Done
                end SimultaneousDriver

                val driver = new SimultaneousDriver
                val conn   = Connection.init[Unit]((), driver, 8)

                // Pre-queue the write span so WritePump can drain it immediately on start.
                discard(conn.outbound.offer(Span.fromUnsafe(writeData)))

                conn.start()
                // ReadPump: awaitRead #1 delivered Bytes → inbound; awaitRead #2 parks.
                // WritePump: outbound.take → driver.write (Done) → outbound empty → parks.

                // Read side: inbound must have the read bytes.
                val polled = conn.inbound.poll()
                assert(
                    polled match
                        case Result.Success(Maybe.Present(span)) => span.toArray.toList == readData.toList
                        case _                                   => false,
                    s"simultaneous-flush: inbound must have ${readData.toList}, got $polled"
                )

                // Write side: driver.write must have received the queued span.
                assert(
                    writtenBytes == writeData.toList,
                    s"simultaneous-flush: driver.write must have seen ${writeData.toList}, got $writtenBytes"
                )
                succeed
            }

            // Scenario 2: two write spans queued before start; ReadPump parks immediately.
            // Expected: WritePump drains both spans in submission order via driver.write.
            // The ordering contract: the second span is never written before the first.
            "multi-write-ordering: spans delivered to driver.write in submission order" in {
                val span1      = Array[Byte](1)
                val span2      = Array[Byte](2)
                var writeOrder = List.empty[List[Byte]]
                var readCalls  = 0

                final class OrderedWriteDriver extends StubDriver:
                    override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                        AllowUnsafe,
                        Frame
                    ): Unit =
                        readCalls += 1
                        // Park on every arm; the test is purely about write ordering.
                    override def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
                        writeOrder = writeOrder :+ data.slice(offset, data.size).toArray.toList
                        WriteResult.Done
                end OrderedWriteDriver

                val driver = new OrderedWriteDriver
                val conn   = Connection.init[Unit]((), driver, 8)

                // Pre-queue two spans; WritePump must take them in FIFO order.
                discard(conn.outbound.offer(Span.fromUnsafe(span1)))
                discard(conn.outbound.offer(Span.fromUnsafe(span2)))

                conn.start()

                assert(writeOrder.length == 2, s"multi-write-ordering: both spans must be written, got $writeOrder")
                assert(writeOrder(0) == span1.toList, s"multi-write-ordering: span1 must be written first, got ${writeOrder(0)}")
                assert(writeOrder(1) == span2.toList, s"multi-write-ordering: span2 must be written second, got ${writeOrder(1)}")
                succeed
            }

            // Scenario 3: PeerFin closes inbound while a write span is queued.
            // Expected: the write must still be flushed through driver.write. A PeerFin on the read side
            // closes the inbound channel but must NOT discard pending outbound bytes.
            "write-after-peerfin: queued write flushes even after inbound closes via PeerFin" in {
                val writeData    = Array[Byte](7, 8, 9)
                var writtenBytes = List.empty[Byte]
                var readCalls    = 0

                final class PeerFinWithWriteDriver extends StubDriver:
                    override def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using
                        AllowUnsafe,
                        Frame
                    ): Unit =
                        readCalls += 1
                        // Deliver PeerFin immediately; ReadPump tears down inbound. WritePump is unaffected.
                        promise.completeDiscard(Result.succeed(ReadOutcome.PeerFin))
                    end awaitRead
                    override def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult =
                        writtenBytes = data.slice(offset, data.size).toArray.toList
                        WriteResult.Done
                end PeerFinWithWriteDriver

                val driver = new PeerFinWithWriteDriver
                val conn   = Connection.init[Unit]((), driver, 8)

                // Pre-queue the write span before the PeerFin tears down inbound.
                discard(conn.outbound.offer(Span.fromUnsafe(writeData)))

                conn.start()
                // ReadPump: PeerFin → closeFn → connection moves to Closing. WritePump: drains outbound
                // (already queued before close). The outbound is drained before teardownHandle runs.

                assert(
                    writtenBytes == writeData.toList,
                    s"write-after-peerfin: driver.write must see ${writeData.toList} even after PeerFin closes inbound, got $writtenBytes"
                )
                succeed
            }
        }
    }

end XPLAT3Test
