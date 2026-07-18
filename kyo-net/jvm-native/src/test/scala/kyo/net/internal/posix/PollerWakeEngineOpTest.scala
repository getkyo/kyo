package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.Test

/** Deterministic guard for the post-upgrade TLS-write wakeup stall: a TLS write's [[PollerIoDriver.submitEngineOp]] coalescing its wakeup against a STALE
  * `wakePending` flag and never draining.
  *
  * Background: [[PollerIoDriver.writeTls]] does not flush synchronously. It calls `submitEngineOp(thunk)` (the thunk is the actual
  * encrypt-and-send) and returns `Done` immediately; the real send runs later, when the poll-loop carrier drains the engine FIFO.
  * `submitEngineOp` triggers the poll-loop wakeup the SAME coalesced way `submitChange` does (the connect-arm class of bug fixed by
  * [[PollerWakeConnectTest]]): `engineQueue.offer(op); if wakePending.compareAndSet(false, true) then triggerWake()`.
  *
  * The poll loop resets `wakePending` to `false` only at the TOP of each cycle (before `drainChanges()` and the blocking park), and
  * `drainFifos()` (which drains the engine queue) runs only AFTER the park returns. So between [a cycle's `drainFifos()` finishing,
  * having consumed and serviced a prior wake] and [the NEXT cycle's `wakePending.set(false)` reset], `wakePending` is `true` but
  * STALE: it represents a wake whose underlying OS-level signal has already been delivered and cleared, not one still in flight. A
  * `submitEngineOp` whose CAS lands in that window observes the stale `true`, skips its own wake, and the carrier goes on to reset the
  * flag and park again, having never been told the op exists. Unlike the read path (re-armed on every later event), a TLS write's
  * `submitEngineOp` is a one-shot submission with no retry: a wake lost here strands the write forever, with no later event to recover
  * it. This matches the captured STARTTLS repeated-upgrade strand exactly: the server's `outbound.put` resolves (`WritePump` already
  * sees `Done`), but the actual socket send never happens.
  *
  * Reproduced here without any timing dependency: `wakePending` is pre-set `true` directly (the exact condition the race above
  * produces), mirroring how [[kyo.net.internal.NioIoDriverTest]]'s `awaitConnectIssuesUnconditionalWakeupEvenWhenCoalescingPending`
  * pins the analogous connect-arm gap. `submitEngineOp` must wake the poll loop regardless of `wakePending`'s prior value, the same
  * unconditional guarantee `IoUringDriver.submitEngineOp` already gives via its always-fire `wakeReapLoop()`.
  *
  * Gate: `PosixTestSockets.assumePoller()` (epoll/kqueue only; io_uring uses a separate driver with its own unconditional wake).
  */
class PollerWakeEngineOpTest extends Test:

    import AllowUnsafe.embrace.danger

    "submitEngineOp issues an unconditional wake even when wakePending is already set (stale-coalescing condition)" in {
        PosixTestSockets.assumePoller()
        val real     = PollerBackend.default()
        val pollerFd = real.create()
        val backend  = RecordingPollerBackend(real)
        val driver   = TestDrivers.forBackend(backend, pollerFd, Ffi.load[SocketBindings])
        try
            // Pre-set the exact condition the race produces: wakePending already true, NOT because a wake is genuinely in flight, but
            // because a prior wake's underlying OS signal was already consumed and the poll loop has not yet reached its next
            // wakePending.set(false) reset. A guarded wakePending.compareAndSet(false, true) would see this stale true and skip its
            // own wake, exactly the bug under test.
            driver.wakePending.set(true)
            val before = backend.wakeCount.get()

            driver.submitEngineOp(() => ())

            val after = backend.wakeCount.get()
            assert(
                after == before + 1,
                s"submitEngineOp must issue an UNCONDITIONAL wake even when wakePending is already set (the stale-coalescing " +
                    s"condition); wakeCount went $before -> $after (a guarded wakeup coalesces away and the engine op -- a TLS " +
                    s"write's only chance to reach the wire -- strands with no retry)"
            )
        finally driver.close()
        end try
    }

end PollerWakeEngineOpTest
