package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.WriteResult

/** Byte CONSERVATION for a single large raw (plaintext) write on a [[IoUringDriver]] handle, over a REAL io_uring ring.
  *
  * io_uring's `IORING_OP_SEND` without `MSG_WAITALL` may complete with `res < len`: the kernel sends only what the socket send buffer can
  * accept right then and reports the partial count, leaving the caller to re-submit the remainder (io_uring_prep_send(3)). The raw send path
  * ([[IoUringDriver.writeRaw]]) submits ONE send SQE for the whole `[offset, size)` region and returns [[WriteResult.Done]] unconditionally;
  * when that send CQE reaps, [[IoUringDriver]] maps it to `PendingOp.Write` and discards `res` (no `res < len` re-flush, unlike the TLS path's
  * `onTlsSendComplete`). So a genuine kernel short send drops the unsent tail: the peer receives a truncated stream and the bytes past the first
  * partial window never reach the wire.
  *
  * The invariant under test is CONSERVATION: every byte of the single write must reach the peer exactly once, in order, none dropped. A shrunk
  * SO_SNDBUF on the driver socket forces the kernel to short-send a payload far larger than the buffer, the same technique
  * [[PollerIoDriverWriteBackpressureTest]] uses for the readiness arm and the TLS partial leaf in [[IoUringTlsWriteOrderingTest]] uses.
  *
  * Expected real-ring outcome: with the dropped-remainder defect present, the peer can never accumulate the full payload (the tail after the
  * first partial send is never re-submitted), so the conservation drain stalls until the `Async.timeout` deadlock ceiling trips, surfacing a
  * `Timeout` that the leaf converts to a failure. Once `writeRaw`/`complete` re-flush the `[res, len)` remainder, every byte arrives, the drain
  * terminates on the conservation condition well inside the ceiling, and `got == payload` exactly.
  *
  * Every leaf is gated by [[PosixTestSockets.assumeUring]] (cancel off Linux / where the production-depth ring cannot init; run the real ring on
  * native Linux).
  *
  * Anti-flakiness: the peer is drained by parking real recv reads through the driver (`awaitRead` on a plaintext peer handle), a real-event
  * latch that completes only when the kernel delivers bytes; the loop terminates on the byte total reaching the payload length. No sleep, no
  * poll-retry. `Async.timeout` is ONLY the deadlock ceiling (the dropped-tail defect would otherwise hang the drain), never a settle timer: on
  * a conserving implementation the loop completes long before it.
  */
class IoUringRawWritePartialSendTest extends Test:

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

    /** Drain the plaintext peer through the driver, parking real recv reads until `want` bytes have arrived, returning them in arrival order.
      * Each `awaitRead` is a real-event latch completing when the kernel delivers bytes; the loop terminates on the conservation condition
      * (`acc.length >= want`). The dropped-tail defect is caught by the enclosing `Async.timeout` ceiling, which interrupts the stalled
      * `awaitRead`.
      */
    private def drainPeer(drv: IoUringDriver, peerHandle: PosixHandle, want: Int)(using
        Frame
    ): Array[Byte] < (Abort[Closed] & Async) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= want then Loop.done(acc)
            else
                val p = Promise.Unsafe.init[Span[Byte], Abort[Closed]]()
                drv.awaitRead(peerHandle, p)
                p.safe.get.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }
    end drainPeer

    "IoUringDriver raw write conservation across a single large send (real ring)" - {

        "a raw send larger than SO_SNDBUF delivers every byte to the peer (partial send re-flushed, not dropped)" in {
            PosixTestSockets.assumeUring()
            withRecordingDriver { (drv, _) =>
                // Shrunk SO_SNDBUF on the driver side forces the kernel to short-send a payload far larger than the send buffer; a conserving
                // implementation re-flushes the unsent remainder when the send CQE reaps.
                PosixTestSockets.smallBufferedPair(sndBuf = 2048, rcvBuf = 2048).map { case (driverFd, peerFd) =>
                    val handle     = PosixHandle.socket(driverFd, PosixHandle.DefaultReadBufferSize, Absent)
                    val peerHandle = PosixHandle.socket(peerFd, PosixHandle.DefaultReadBufferSize, Absent)

                    val payload = Array.tabulate[Byte](48 * 1024)(i => (i % 251).toByte)

                    val w = drv.write(handle, Span.fromUnsafe(payload), 0)
                    assert(w == WriteResult.Done, s"raw write result=$w")

                    // Async.timeout is ONLY the deadlock ceiling: a conserving drain finishes far inside it; the dropped-tail defect stalls the
                    // drain, so the Timeout fires and is converted to a failure (the drop manifests as the drain never reaching the payload length).
                    Abort.run[Timeout](Async.timeout(10.seconds)(drainPeer(drv, peerHandle, payload.length))).map { outcome =>
                        drv.closeHandle(handle)
                        drv.closeHandle(peerHandle)
                        outcome match
                            case Result.Failure(_: Timeout) =>
                                fail(s"conservation: the drain stalled; the peer never received all ${payload.length} bytes (tail dropped)")
                            case Result.Success(got) =>
                                assert(
                                    got.length == payload.length,
                                    s"conservation: peer must receive every byte of the raw write; got ${got.length} of ${payload.length}"
                                )
                                assert(
                                    got.toList == payload.toList,
                                    "conservation: the received bytes must equal the payload once each, in order (no drop/reorder)"
                                )
                        end match
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringRawWritePartialSendTest
