package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** Wire-ORDER conservation across back-to-back raw (plaintext) writes on a single [[IoUringDriver]] handle, over a REAL io_uring ring.
  *
  * Because the raw send path ([[IoUringDriver.writeRaw]]) returns [[WriteResult.Done]] as soon as the send SQE is accepted, the write pump
  * issues the next raw write before the prior send's CQE has reaped, so two raw send SQEs for the same `writeFd` can be in flight at once. The
  * raw path has no single-in-flight guard (unlike the TLS path's `sendInFlight`) and applies no `IOSQE_IO_LINK`, and io_uring does not guarantee
  * SQEs execute or complete in submission order: if the first send finds the socket buffer full and is poll-armed while the second finds space,
  * the second write's bytes can hit the wire before the first's, corrupting the byte stream (liburing discussion #1102). This is the raw twin of
  * the hazard [[IoUringTlsWriteOrderingTest]] guards for TLS.
  *
  * The invariant under test is CONSERVATION + ORDER: the two writes must arrive on the wire as `p1 ++ p2` exactly, none reordered, dropped, or
  * duplicated. A shrunk SO_SNDBUF widens the reorder window (the first large write cannot complete immediately, so the second is submitted while
  * the first is still poll-deferred), the same technique the TLS ordering and backpressure leaves use. `p1` and `p2` carry distinct
  * position-encoding byte patterns so a reorder or interleave is visible as a concrete byte mismatch, not just a length shortfall. The scenario is
  * looped so a single reorder on any iteration fails the leaf (the "loop the scenario to make it reliable" rule).
  *
  * Expected real-ring outcome: with the unguarded back-to-back raw sends, an out-of-order completion puts `p2`'s bytes (or part of them) on the
  * wire before `p1`'s tail, so `got != p1 ++ p2` and the leaf FAILS; a dropped tail (the partial-send defect) shows as a length shortfall caught
  * by the same equality. Once the raw path holds at most one send in flight per handle (or links the SQEs with MSG_WAITALL), every byte arrives in
  * submission order and the equality holds on every iteration.
  *
  * Every leaf is gated by [[PosixTestSockets.assumeUring]] (cancel off Linux / where the production-depth ring cannot init; run the real ring on
  * native Linux).
  *
  * Anti-flakiness: the peer is drained by parking real recv reads through the driver (`awaitRead` on a plaintext peer handle), terminating on the
  * byte total reaching `p1.length + p2.length`. No sleep, no poll-retry. `Async.timeout` is ONLY the deadlock ceiling (a dropped tail would
  * otherwise hang the drain), never a settle timer.
  */
class IoUringRawWriteOrderingTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Allocate a real io_uring ring at production depth, wrap it in a recording spy, build a driver, run `body`, then close the driver. */
    private def withRecordingDriver[A](
        body: (IoUringDriver, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("RecordingIoUringBindings", summon[Frame], s"queue_init failed: rc=$rc")
        val recording = RecordingIoUringBindings(realUring, realRing)
        val driver    = TestDrivers.forBindings(recording, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver, recording))
    end withRecordingDriver

    /** Drain the plaintext peer through the driver until `want` bytes have arrived, returning them in arrival order. Each `awaitRead` is a
      * real-event latch completing when the kernel delivers bytes; the loop terminates on `acc.length >= want`. The dropped/reordered defect is
      * caught by the enclosing `Async.timeout` ceiling (a stalled drain) and by the byte-equality assertion (a reorder).
      */
    private def drainPeer(drv: IoUringDriver, peerHandle: PosixHandle, want: Int)(using
        Frame
    ): Array[Byte] < (Abort[Closed] & Async) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= want then Loop.done(acc)
            else
                val p = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                drv.awaitRead(peerHandle, p)
                p.safe.get.map {
                    case ReadOutcome.Bytes(chunk) => Loop.continue(acc ++ chunk.toArray)
                    case _                        => Loop.done(acc)
                }
        }
    end drainPeer

    "IoUringDriver raw write order conservation across back-to-back sends (real ring)" - {

        "two back-to-back raw writes deliver p1 then p2 on the wire in order, none reordered or duplicated" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver { (drv, _) =>
                // Distinct position-encoding patterns large enough that p1 partial-sends through the shrunk SO_SNDBUF, so the second write is
                // submitted while the first send is still poll-deferred (the reorder window).
                val p1       = Array.tabulate[Byte](40 * 1024)(i => (i % 251).toByte)
                val p2       = Array.tabulate[Byte](20 * 1024)(i => ((i + 7) % 251).toByte)
                val expected = (p1 ++ p2).toList

                // Async.timeout is ONLY the deadlock ceiling: a conserving drain finishes far inside it; a reordered or
                // dropped send stalls the drain so the Timeout fires, which the Abort.run converts to a failure.
                Abort.run[Timeout](Async.timeout(80.seconds)(Loop(0) { iteration =>
                    if iteration >= 8 then Loop.done(succeed)
                    else
                        PosixTestSockets.smallBufferedPair(sndBuf = 2048, rcvBuf = 2048).map { case (driverFd, peerFd) =>
                            val handle     = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                            val peerHandle = PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent)

                            // No await between the two writes: mirrors the WritePump issuing the next take as soon as writeRaw returns Done.
                            val w1 = drv.write(handle, Span.fromUnsafe(p1), 0)
                            assert(w1 == WriteResult.Done, s"write 1 result=$w1")
                            val w2 = drv.write(handle, Span.fromUnsafe(p2), 0)
                            assert(w2 == WriteResult.Done, s"write 2 result=$w2")

                            drainPeer(drv, peerHandle, expected.length).map { got =>
                                drv.closeHandle(handle)
                                drv.closeHandle(peerHandle)
                                assert(
                                    got.length == expected.length,
                                    s"iteration $iteration: peer must receive every byte of both writes; got ${got.length} of ${expected.length}"
                                )
                                assert(
                                    got.toList == expected,
                                    s"iteration $iteration: received bytes must equal p1 ++ p2 once each, in order (no reorder/dup/drop)"
                                )
                                Loop.continue(iteration + 1)
                            }
                        }
                })).map {
                    case Result.Failure(_: Timeout) =>
                        fail("ordering: the drain stalled across 8 iterations; a reordered or dropped send left bytes undelivered")
                    case Result.Success(assertion) => assertion
                }
            }
        }
    }

end IoUringRawWriteOrderingTest
