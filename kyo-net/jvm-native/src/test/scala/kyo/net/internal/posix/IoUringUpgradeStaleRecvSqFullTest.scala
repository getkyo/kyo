package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** `hasInFlightRead` must count a recv parked on a full submission queue, over a REAL io_uring ring.
  *
  * The io_uring STARTTLS upgrade routes the peer's first post-signal flight through the handle's `upgradeHandoff` slot when the plaintext
  * ReadPump left a kernel-owned "stale recv". The handshake's first read ([[PosixTransport.driveUpgradeRead]]) decides whether such a stale recv
  * is coming by calling [[IoUringDriver.hasInFlightRead]]; a wrong "no recv in flight" answer makes it clear `upgradeActive` and arm a racing
  * recv, so the re-armed stale recv's CQE reaps on the now-normal read path and the handshake's own recv waits for a flight that already arrived,
  * a silent upgrade stall under load (no teardown, no EOF, no error).
  *
  * A recv that hits a full SQ is `unregister`ed from `pending` and parked in `stalledSubmits` (re-armed next reap turn by `reArmStalledSubmits`),
  * so it is genuinely in flight even though it is not in `pending`. Under saturation the stale recv can be sitting in `stalledSubmits` exactly when
  * `driveUpgradeRead` consults `hasInFlightRead`. This leaf pins that contract: an SQ-full-parked recv counts as in flight.
  *
  * The SQ-full is forced structurally on a real depth-1 ring (exactly one SQE) with the reap loop running: a latch (released by the test, not a
  * sleep) pins the reap carrier so a filler read, a target read, and the `hasInFlightRead` probe all drain in ONE pass before the wait submits
  * anything. The filler read consumes the one SQE and stays in flight (`pending`); the target read's `submitRecv` then sees `get_sqe` return Absent
  * and parks in `stalledSubmits`; the probe then reads `hasInFlightRead(targetH)` on the same reap carrier, after the park and before any re-arm.
  * It must be true. Before the fix (`hasInFlightRead` scanned only `pending`) it was false: the regression this guards.
  *
  * Gated by [[PosixTestSockets.assumeUring]] (cancel off Linux / where the depth-1 ring cannot init). Anti-flakiness: the SQ-full condition is
  * structural (depth-1 ring, the reap carrier pinned while the ops enqueue), not timing-driven; the only wait is on the probe promise the reap
  * carrier completes. No sleep, no poll-retry, no unbounded spin.
  */
class IoUringUpgradeStaleRecvSqFullTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** Allocate a REAL io_uring ring at `depth`, build a driver over it with its reap loop started, run `body`, then close the driver. */
    private def withDriver[A](depth: Int)(
        body: IoUringDriver => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("IoUringUpgradeStaleRecvSqFullTest", summon[Frame], s"queue_init failed: rc=$rc")
        val driver = TestDrivers.forBindings(realUring, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withDriver

    "hasInFlightRead counts a recv parked on a full submission queue (real ring)" in {
        PosixTestSockets.assumeUring()
        // Depth-1 real ring, reap loop running. Pin the reap carrier with a latch (released by the test, not a sleep) so the filler read, the target
        // read, and the hasInFlightRead probe all enqueue behind it and drain in ONE pass before the wait submits anything: the filler read consumes
        // the one SQE and stays in flight (pending), the target read's submitRecv then sees get_sqe Absent and parks in stalledSubmits, and the probe
        // reads hasInFlightRead(targetH) on the same reap carrier, after the park and before any re-arm. It must be true.
        withDriver(1) { drv =>
            PosixTestSockets.loopbackPair().map { case (fillerClient, fillerAccepted) =>
                PosixTestSockets.loopbackPair().map { case (targetClient, targetAccepted) =>
                    val fillerH = PosixHandle.socket(fillerAccepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val targetH = PosixHandle.socket(targetAccepted, PosixHandle.DefaultReadBufferSize, Absent)
                    val gate    = new java.util.concurrent.CountDownLatch(1)
                    val pinIn   = Promise.Unsafe.init[Unit, Abort[Closed]]()
                    drv.submitEngineOp { () =>
                        pinIn.completeDiscard(Result.succeed(()))
                        gate.await()
                    }
                    pinIn.safe.get.map { _ =>
                        // Reap carrier pinned: all enqueue behind it and drain together when it releases.
                        val fillerP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        drv.awaitRead(fillerH, fillerP) // consumes the one SQE, stays in flight in `pending` (no peer bytes)
                        val targetP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        drv.awaitRead(targetH, targetP) // SQ full: submitRecv parks this in `stalledSubmits`, not `pending`
                        val probe = Promise.Unsafe.init[Boolean, Abort[Closed]]()
                        // Read the contract on the reap carrier, after the target read parked and before reArmStalledSubmits runs.
                        drv.submitEngineOp { () =>
                            probe.completeDiscard(Result.succeed(drv.hasInFlightRead(targetH)))
                        }
                        // Release the gate BEFORE awaiting the probe: a parked reap carrier would otherwise wedge every later test.
                        gate.countDown()
                        probe.safe.get.map { inFlight =>
                            drv.closeHandle(fillerH)
                            drv.closeHandle(targetH)
                            discard(sock.close(fillerClient))
                            discard(sock.close(targetClient))
                            assert(
                                inFlight,
                                "a recv parked on a full SQ must count as in flight (else the STARTTLS upgrade misjudges 'no stale recv')"
                            )
                        }
                    }
                }
            }
        }
    }

end IoUringUpgradeStaleRecvSqFullTest
