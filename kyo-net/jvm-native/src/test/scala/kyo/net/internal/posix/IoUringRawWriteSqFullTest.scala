package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome
import kyo.net.internal.transport.WriteResult

/** SQ-full backpressure for a raw (plaintext) write on a [[IoUringDriver]] handle, over a REAL io_uring ring.
  *
  * A raw write appends its bytes to the handle's pending tail and submits a send SQE on the reap carrier ([[IoUringDriver.writeRaw]] ->
  * `flushRaw`). When `kyo_uring_get_sqe` returns Absent (the submission queue is full) `flushRaw` has nothing in flight to re-drive the send, so
  * it parks the handle in `stalledRaw` and leaves the bytes in the tail rather than dropping them or busy-retrying. The reap loop re-flushes
  * every stalled handle once per turn, after `flushSubmits` and the wait free the SQ slots, so a transient SQ-full BACKPRESSURES the write (the
  * bytes wait in the tail) and then the send goes out when a slot frees. The bytes are never lost and the writer never spins.
  *
  * The leaf forces the SQ-full structurally on a real depth-1 ring (exactly one SQE) with the reap loop running: a latch (the test releases it,
  * not a sleep) pins the reap carrier so a filler read, the raw write, and a peer read all drain in ONE pass before the wait submits anything.
  * The filler read consumes the one SQE; the raw write's `flushRaw` then sees `get_sqe` return Absent and parks in `stalledRaw`; the peer read
  * parks too. Releasing the latch lets the reap loop re-flush the parked send and re-arm the parked read over the next turns. The invariant is
  * proven end to end: the peer read delivers exactly the written payload, so the SQ-full write was parked and re-flushed, never dropped.
  *
  * Every leaf is gated by [[PosixTestSockets.assumeUring]] (cancel off Linux / where the production-depth ring cannot init; run the real ring on
  * native Linux).
  *
  * Anti-flakiness: the SQ-full condition is structural (depth-1 ring, the reap carrier pinned while the ops are enqueued), not timing-driven; the
  * only wait is on the peer read promise the driver completes once the re-flushed bytes arrive. No sleep, no poll-retry, no unbounded spin.
  */
class IoUringRawWriteSqFullTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Allocate a REAL io_uring ring at `depth`, wrap it in a recording spy, build a driver over it with its reap loop started, run `body`, then
      * close the driver. The reap loop must run: every SQ op (recv/accept/connect arming, the raw append+flush) drains on the reap carrier, and the
      * stalled-op re-flush/re-arm runs from the reap loop, so the SQ-full backpressure under test only makes progress with the loop running.
      */
    private def withRecordingDriver[A](depth: Int)(
        body: (IoUringDriver, RecordingIoUringBindings) => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
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

    "IoUringDriver raw write SQ-full backpressure (real ring)" - {

        "a raw write whose flush hits SQ-full is parked and re-flushed (not dropped), delivering the bytes to the peer" in {
            PosixTestSockets.assumeUring()
            // Depth-1 real ring, reap loop running. Pin the reap carrier with a latch (released by the test, not a sleep) so the filler read, the
            // raw write, and the peer read all enqueue and drain in ONE pass before the wait submits anything: the filler read consumes the one SQE,
            // the raw write's flushRaw then sees get_sqe Absent and parks in stalledRaw (the bytes stay in the tail), and the peer read parks too.
            // Releasing the latch lets the reap loop re-flush the parked send and re-arm the parked read each turn. The peer read delivering the exact
            // payload proves the SQ-full write was parked and re-flushed, never dropped and never busy-retried.
            withRecordingDriver(1) { (drv, _) =>
                PosixTestSockets.loopbackPair().map { case (fillerClient, fillerAccepted) =>
                    PosixTestSockets.loopbackPair().map { case (client, accepted) =>
                        val fillerH = PosixHandle.socket(fillerAccepted, PosixHandle.DefaultReadBufferSize, Absent)
                        val writeH  = PosixHandle.socket(client, PosixHandle.DefaultReadBufferSize, Absent)
                        val peerH   = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                        val gate    = new java.util.concurrent.CountDownLatch(1)
                        val pinIn   = Promise.Unsafe.init[Unit, Abort[Closed]]()
                        drv.submitEngineOp { () =>
                            pinIn.completeDiscard(Result.succeed(()))
                            gate.await()
                        }
                        pinIn.safe.get.map { _ =>
                            // Reap carrier pinned: all three ops enqueue behind it and drain together when it releases.
                            val fillerP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            drv.awaitRead(fillerH, fillerP) // consumes the one SQE, stays in flight (no peer bytes)
                            val payload = Span.fromUnsafe(Array.tabulate[Byte](48)(i => (i * 3 + 1).toByte))
                            val w       = drv.write(writeH, payload, 0)
                            val peerP   = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                            drv.awaitRead(peerH, peerP)
                            // Release the gate BEFORE asserting on the write result: a failed assertion must never leave the reap carrier
                            // parked (it would wedge every later test on this scheduler).
                            gate.countDown()
                            assert(w == WriteResult.Done, "writeRaw appends and returns Done; the send runs on the reap carrier")
                            Abort.run[Closed](peerP.safe.get).map { result =>
                                // closeHandle closes each handle's fd (fillerAccepted, client, accepted); only the raw filler-client fd has no
                                // handle, so close it directly. Closing `client` here too would double-close the fd writeH already closed.
                                drv.closeHandle(fillerH)
                                drv.closeHandle(writeH)
                                drv.closeHandle(peerH)
                                discard(sock.close(fillerClient))
                                result match
                                    case Result.Success(ReadOutcome.Bytes(got)) =>
                                        assert(
                                            got.toArray.toList == payload.toArray.toList,
                                            s"the re-flushed SQ-full write delivered wrong bytes: ${got.toArray.toList}"
                                        )
                                    case other =>
                                        fail(s"a SQ-full raw write must park and re-flush to the peer, never drop: got $other")
                                end match
                            }
                        }
                    }
                }
            }.map(_ => succeed)
        }
    }

end IoUringRawWriteSqFullTest
