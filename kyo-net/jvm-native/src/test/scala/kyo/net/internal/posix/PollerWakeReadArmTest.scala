package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Deterministic guard for the read-side counterpart of the B' write-stall: [[PollerIoDriver.submitChange]] coalescing its wakeup against a
  * STALE `wakePending` flag and never draining, stranding a re-armed read (or a cancel/deregister) that had no other event left to wake the
  * poll loop.
  *
  * Background: [[PollerWakeEngineOpTest]] pins the identical gap on `submitEngineOp`, which was made to fire an UNCONDITIONAL wake after that
  * investigation found `wakePending.compareAndSet(false, true)` can observe a STALE `true` -- one left over from an EARLIER wake whose
  * underlying OS-level signal the poll loop already consumed this cycle, not one still in flight -- and skip its own wake entirely.
  * `submitChange` (the read-registration and cancel/deregister path: `awaitRead`, `cancel`, `closeHandle`) uses the exact same
  * `changeQueue.offer(cmd); if wakePending.compareAndSet(false, true) then triggerWake()` pattern and was never updated when
  * `submitEngineOp` was fixed, leaving the same stale-coalescing window open on the read side.
  *
  * The read side usually self-heals: a coalesced-away wake still leaves the command sitting in `changeQueue`, and the poll loop's
  * unconditional per-cycle `drainChanges()` picks it up on the very next cycle IF `backend.poll()` returns for some OTHER reason. The gap only
  * strands a connection PERMANENTLY when the poll loop's current `backend.poll()` call is parked with nothing else pending: no other fd on
  * this driver has any future activity to return the park, so the missed wake is the connection's only chance and `drainChanges()` never
  * runs again. This matches the connection-reuse hangs observed under kyo-http's pool/concurrency stress leaves (`HttpClientTest`
  * "concurrent contention with more fibers than pool slots", "connection reuse with varying data", etc.): under load, `drainFifos()` does
  * more work per cycle, widening the stale-`wakePending` window and making a same-cycle cross-thread `submitChange` far more likely to land
  * in it.
  *
  * Reproduced here without any timing dependency, mirroring [[PollerWakeEngineOpTest]] exactly: `wakePending` is pre-set `true` directly (the
  * exact condition the race produces), and `cancel` (routed through `submitChange`) must still wake the poll loop.
  *
  * Gate: `PosixTestSockets.assumePoller()` (epoll/kqueue only; io_uring uses a separate driver with its own unconditional wake).
  */
class PollerWakeReadArmTest extends Test:

    import AllowUnsafe.embrace.danger

    "submitChange issues an unconditional wake even when wakePending is already set (stale-coalescing condition)" in {
        PosixTestSockets.assumePoller()
        PosixTestSockets.loopbackPair().map { case (clientFd, acceptedFd) =>
            val real     = PollerBackend.default()
            val pollerFd = real.create()
            val backend  = RecordingPollerBackend(real)
            val driver   = TestDrivers.forBackend(backend, pollerFd, Ffi.load[SocketBindings])
            try
                // Pre-set the exact condition the race produces: wakePending already true, NOT because a wake is genuinely in flight, but
                // because a prior wake's underlying OS signal was already consumed and the poll loop has not yet reached its next
                // wakePending.set(false) reset. A guarded wakePending.compareAndSet(false, true) would see this stale true and skip its own
                // wake, exactly the bug under test.
                driver.wakePending.set(true)
                val before = backend.wakeCount.get()

                val handle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                driver.cancel(handle)

                val after = backend.wakeCount.get()
                assert(
                    after == before + 1,
                    s"submitChange must issue an UNCONDITIONAL wake even when wakePending is already set (the stale-coalescing " +
                        s"condition); wakeCount went $before -> $after (a guarded wakeup coalesces away and a re-armed read -- with no " +
                        s"other pending event on this driver to return the park -- strands forever)"
                )
                PosixTestSockets.closePeerForEof(Ffi.load[SocketBindings], acceptedFd)
            finally driver.close()
            end try
        }
    }

end PollerWakeReadArmTest
