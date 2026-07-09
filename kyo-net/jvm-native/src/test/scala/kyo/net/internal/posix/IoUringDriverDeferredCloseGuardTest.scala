package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test
import kyo.net.internal.transport.ReadOutcome

/** Reproduce-first regression for a `CLOSE_WAIT` leak in the deferred fd-close guard: [[IoUringDriver.registerDeferredClose]]'s deferred branch (an
  * in-flight recv races a close) used to register the handle in its private `closeAfterDrain` map WITHOUT holding [[PosixHandle]]'s guard
  * open for that window. `closeAfterDrain` is invisible to the guard, so a concurrent, unrelated `PosixHandle.close` caller that loses the
  * `claimFdClose` race below (e.g. a failed STARTTLS upgrade's `PosixTransport.releaseFailedUpgrade`, which calls `PosixHandle.close`
  * UNCONDITIONALLY regardless of the claim outcome) would see zero active holders and run `freeResources` immediately -- before
  * `closeNow` ever installs the real `close(fd)` credit (`fdCloseSink`). `freeResources` runs at most once per handle, so once it has run
  * with no credit installed, `closeNow`'s LATER `PosixHandle.close` call (once the recv's CQE finally reaps) is a no-op: the just-installed
  * credit sits unconsumed forever and the real `close(fd)` syscall never runs, leaving the socket in `CLOSE_WAIT` past process exit.
  *
  * The fix: `registerDeferredClose` now holds a guard slot ([[PosixHandle.beginDeferredClose]]) for the entire deferred window, acquired
  * BEFORE attempting `claimFdClose`, released only once `closeNow` has installed the credit ([[IoUringDriver.dischargeDeferredClose]]). A
  * concurrent `PosixHandle.close` caller now sees an active holder and correctly defers instead of freeing prematurely, regardless of which
  * side wins the (independent) `claimFdClose` race.
  *
  * Anti-flakiness: awaits `driver.hasInFlightRead(handle)` becoming true (the recv is genuinely kernel-owned) before closing, then drives
  * the racing `PosixHandle.close` call itself through `submitEngineOp`, in the SAME op-drain batch as `closeHandle`'s own submission
  * (`drainEngineOps` runs the whole queued batch to completion before the reap carrier ever waits for or processes a CQE), rather than
  * from a separate fiber woken by a completed promise: waking a parked fiber is a real scheduling hop, and gives the reap carrier's own
  * CQE-driven `closeNow` (triggered by the `shutdown(SHUT_RD)` above, which resolves near-instantly on a loopback pair) ample time to win
  * that race first, so the racer's close would land on an already-closed handle instead of the live one and the bug would not reproduce.
  * Routing the racer through the engine queue instead pins the interleaving deterministically: it runs strictly after
  * `registerDeferredClose` has completed (installed its guard hold under the fix, or not, under the bug) and strictly before the recv's
  * CQE can be reaped. The final assertion awaits `RecordingSocketBindings.closed`, a per-fd latch that only ever completes once the real
  * `close(fd)` syscall actually runs, rather than polling.
  */
class IoUringDriverDeferredCloseGuardTest extends Test:

    import AllowUnsafe.embrace.danger

    "IoUringDriver deferred close guard race" - {

        "keeps a racing PosixHandle.close caller deferred until the real close(fd) credit is installed, so it is never stranded" in {
            PosixTestSockets.assumeUring()
            given Frame   = Frame.internal
            val depth     = math.max(256, kyo.net.TransportConfig.default.ioPoolSize * 64)
            val realUring = Ffi.load[IoUringBindings]
            val realRing  = Buffer.alloc[Byte](realUring.kyo_uring_sizeof().toInt)
            val rc        = realUring.io_uring_queue_init(depth, realRing, 0)
            if rc != 0 then
                realRing.close()
                throw Closed("IoUringDriverDeferredCloseGuardTest", summon[Frame], s"queue_init failed: rc=$rc")
            val spy    = RecordingSocketBindings(Ffi.load[SocketBindings])
            val driver = TestDrivers.forBindings(realUring, realRing, spy)
            discard(driver.start())
            Sync.ensure(Sync.defer(driver.close())) {
                PosixTestSockets.loopbackPair(spy).map { case (client, accepted) =>
                    val handle = PosixHandle.socket(accepted, PosixHandle.DefaultReadBufferSize, Absent)
                    handle.driver = driver
                    Sync.ensure(Sync.defer(discard(spy.close(client)))) {
                        val readPromise = Promise.Unsafe.init[ReadOutcome, Abort[Closed]]()
                        driver.awaitRead(handle, readPromise)
                        assertEventually(Sync.defer(driver.hasInFlightRead(handle))).map { _ =>
                            driver.closeHandle(handle)
                            // Drive the racer through the SAME engine queue as closeHandle's own submission: drainEngineOps runs the
                            // whole batch (closeHandle's op, then this one) to completion before the reap carrier ever waits for or
                            // processes a CQE, so this op is guaranteed to observe registerDeferredClose's completed state (won
                            // claimFdClose, taken the deferred-close guard hold under the fix) and to run strictly before the recv's CQE
                            // can reap -- the exact "racer lands while the close is still deferred" interleaving the bug requires,
                            // pinned deterministically instead of left to a fiber-wakeup-vs-kernel-reap race.
                            val racerClaimedFd = new java.util.concurrent.atomic.AtomicBoolean(true)
                            val racerDone      = Promise.Unsafe.init[Unit, Abort[Closed]]()
                            driver.submitEngineOp { () =>
                                // Simulate releaseFailedUpgrade's exact pattern: it lost the fd claim (registerDeferredClose already won
                                // it above) but calls PosixHandle.close UNCONDITIONALLY regardless of that outcome.
                                racerClaimedFd.set(handle.claimFdClose())
                                PosixHandle.close(handle)
                                racerDone.completeDiscard(Result.succeed(()))
                            }
                            racerDone.safe.get.map { _ =>
                                assert(!racerClaimedFd.get(), "test setup: registerDeferredClose must already own the fd claim")
                                // The recv is still kernel-owned at this point (registerDeferredClose's shutdown(SHUT_RD) above forces
                                // it to EOF soon, but has not necessarily reaped yet); the real close(fd) can only run once that CQE
                                // reaps and discharges closeAfterDrain. Bounded, not a sleep: a real settle would need to reap a CQE on
                                // the driver's own dedicated thread, which happens on its own schedule.
                                Abort.run[Closed | Timeout](Async.timeout(5.seconds)(spy.closed(accepted).safe.get)).map { outcome =>
                                    assert(
                                        outcome.isSuccess,
                                        "the real close(fd) for the deferred handle never ran: the racing PosixHandle.close call stole " +
                                            s"the one-shot terminal free before the credit existed, stranding it forever. Got $outcome"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end IoUringDriverDeferredCloseGuardTest
