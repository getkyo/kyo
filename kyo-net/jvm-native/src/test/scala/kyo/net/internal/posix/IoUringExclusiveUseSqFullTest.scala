package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** [[PosixHandle.recvInFlight]] (the always-on exclusive-use guard) must NOT false-positive when a recv parks on a full submission queue
  * and is later re-armed by [[IoUringDriver.reArmStalledSubmits]], over a REAL io_uring ring.
  *
  * `submitRecv` sets `recvInFlight = true` only once a real SQE is submitted, deliberately NOT for the SQ-full park case (see its own
  * doc): nothing kernel-owned touches the buffer while parked, so the flag staying `false` there is correct, and the later re-arm calls
  * `submitRecv` directly (bypassing the `awaitRead`/`submitDeferredRecv` entry the guard's check actually gates) rather than through any
  * path that could re-trip the guard. This leaf exercises that reasoning against a REAL SQ-full condition rather than trusting the
  * analysis alone: a normal-size ring (as every other stress/regression test in this module uses) essentially never saturates its SQ, so
  * this path has no other test coverage.
  *
  * The SQ-full is forced structurally exactly as [[IoUringUpgradeStaleRecvSqFullTest]] does: a real depth-1 ring with the reap loop
  * running, a latch (released by the test, not a sleep) pinning the reap carrier so a filler read and a target read both enqueue and
  * drain together in one pass before the first submit-and-wait flushes the SQ. The filler read consumes the one SQE; the target read's
  * `submitRecv` then sees `get_sqe` return Absent and parks in `stalledSubmits`.
  *
  * Asserts: (1) `recvInFlight` is `false` for the target handle while parked (the SQE is not yet live); (2) once the pinned batch drains
  * and the SQ is flushed, `reArmStalledSubmits` re-arms the target for real (its recv reaches `pending`, `recvInFlight` becomes `true`) --
  * WITHOUT ever tripping the exclusive-use guard (no `Closed` failure, no spurious `closeHandle`); (3) real bytes sent after the re-arm
  * are delivered correctly to the target's promise, proving the park-then-rearm cycle round-trips cleanly under the guard.
  *
  * Gated by [[PosixTestSockets.assumeUring]] (cancel off Linux / where the depth-1 ring cannot init). Anti-flakiness: the SQ-full
  * condition is structural (depth-1 ring, the reap carrier pinned while both reads enqueue), not timing-driven; every wait is on a real,
  * observable state transition or a promise the reap carrier completes. No sleep, no unbounded spin.
  */
class IoUringExclusiveUseSqFullTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    private def awaitCondition(bound: Duration)(cond: => Boolean)(using Frame): Boolean < Async =
        val deadline = java.lang.System.nanoTime() + bound.toNanos
        Loop(()) { _ =>
            if cond then Loop.done(true)
            else if java.lang.System.nanoTime() >= deadline then Loop.done(false)
            else Async.sleep(2.millis).andThen(Loop.continue(()))
        }
    end awaitCondition

    /** Allocate a REAL io_uring ring at `depth`, build a driver over it with its reap loop started, run `body`, then close the driver. */
    private def withDriver[A](depth: Int)(
        body: IoUringDriver => A < (Abort[Closed] & Async)
    )(using Frame): A < (Abort[Closed] & Async) =
        val realUring = Ffi.load[IoUringBindings]
        val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
        val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
        if rc != 0 then
            realRing.close()
            throw Closed("IoUringExclusiveUseSqFullTest", summon[Frame], s"queue_init failed: rc=$rc")
        val driver = TestDrivers.forBindings(realUring, realRing)
        discard(driver.start())
        Sync.ensure(Sync.defer(driver.close()))(body(driver))
    end withDriver

    "recvInFlight stays false while parked on a full SQ and re-arms cleanly without tripping the guard (real ring)" in {
        PosixTestSockets.assumeUring()
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
                        // Reap carrier pinned: both reads enqueue behind it and drain together when it releases.
                        val fillerP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        drv.awaitRead(fillerH, fillerP) // consumes the one SQE, stays in flight in `pending` (no peer bytes)
                        val targetP = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        drv.awaitRead(targetH, targetP) // SQ full: submitRecv parks this in `stalledSubmits`, recvInFlight stays false
                        val parkedProbe = Promise.Unsafe.init[Boolean, Abort[Closed]]()
                        drv.submitEngineOp { () =>
                            parkedProbe.completeDiscard(Result.succeed(targetH.recvInFlight))
                        }
                        // Release the gate BEFORE awaiting the probe: a parked reap carrier would otherwise wedge every later test.
                        gate.countDown()
                        parkedProbe.safe.get.map { inFlightWhileParked =>
                            assert(
                                !inFlightWhileParked,
                                "recvInFlight must stay false while the recv is only parked in stalledSubmits (no live SQE yet)"
                            )
                            // Wait for the real re-arm: reArmStalledSubmits runs every reap turn once the pinned batch's submit-and-wait flushes
                            // the SQ, giving the target a genuine SQE. hasInFlightRead(targetH) becomes true once it registers in `pending`.
                            awaitCondition(5.seconds)(drv.hasInFlightRead(targetH)).map { rearmed =>
                                assert(
                                    rearmed,
                                    "target recv was never re-armed after the SQ-full park (a hang, not the guard hazard under test)"
                                )
                                // The guard must not have fired during the rearm: the target's own promise must still be pending (not failed
                                // Closed by the exclusive-use guard), and recvInFlight is now true (a real SQE is live for it).
                                assert(
                                    targetP.poll().isEmpty,
                                    s"target promise must still be pending after a clean re-arm; got ${targetP.poll()}"
                                )
                                assert(targetH.recvInFlight, "recvInFlight must be true once the re-armed SQE is genuinely submitted")

                                val payload = Array[Byte](11, 22, 33, 44)
                                assert(sock.sendNow(
                                    targetClient,
                                    Buffer.fromArray[Byte](payload),
                                    payload.length.toLong,
                                    0
                                ).value == payload.length.toLong)

                                awaitCondition(5.seconds)(targetP.poll().isDefined).map { resolved =>
                                    assert(resolved, "target recv never resolved after real data arrived post-rearm")
                                    targetP.poll() match
                                        case Present(Result.Success(ReadOutcome.Bytes(bytes))) =>
                                            assert(
                                                bytes.toArrayUnsafe.toSeq == payload.toSeq,
                                                s"target resolved with ${bytes.toArrayUnsafe.toSeq} != sent payload ${payload.toSeq}"
                                            )
                                        case other => fail(s"expected Success(Bytes), got $other")
                                    end match
                                    drv.closeHandle(fillerH)
                                    drv.closeHandle(targetH)
                                    discard(sock.close(fillerClient))
                                    discard(sock.close(targetClient))
                                    succeed
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end IoUringExclusiveUseSqFullTest
