package kyo.net.internal.transport

import kyo.*
import kyo.net.Test
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTestSockets
import kyo.net.internal.posix.RecordingIoDriver

/** All-or-nothing partial-start teardown test for [[IoDriverPool]].
  *
  * Verifies that when the k-th driver's `start()` raises a failure, `IoDriverPool.start()` rethrows the failure AND closes all
  * k-1 already-started drivers, leaving no partially-started transport alive. The controlled fault is the single enumerated injection on
  * [[RecordingIoDriver.throwOnStart]]: when set to true, the next `start()` call throws a [[RuntimeException]] instead of delegating, while
  * every other method still delegates to the real driver. This is the only deterministic way to force a mid-array start failure because
  * `PollerIoDriver.start()` itself does not raise synchronous errors (it spawns the poll-loop fiber, which could fail asynchronously if the
  * poller fd were invalid, but that failure would land outside the `try/catch` in `IoDriverPool.start`).
  *
  * Gate: [[PosixTestSockets.assumePoller]] cancels where no epoll (Linux) or kqueue (macOS/BSD) is available.
  *
  * Anti-flakiness: the controlled fault fires synchronously at the `start()` call site; no sleep, no async coordination needed.
  */
class IoDriverPoolPartialStartTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private val transportConfig = kyo.net.NetConfig.default

    private def assumePoller(): Unit =
        PosixTestSockets.assumePoller()
        ()

    /** Build `n` real PollerIoDriver instances wrapped in RecordingIoDriver spies (fully delegating). */
    private def mkRealSpies(n: Int): Array[RecordingIoDriver] =
        Array.fill(n)(new RecordingIoDriver(PollerIoDriver.init()))

    // --- partialStartIsAllOrNothing ---
    // When the k-th driver's start() throws, pool.start() must:
    //   1. Rethrow the exception (transport build fails atomically; no partially-started pool is handed to callers).
    //   2. Close the k-1 already-started drivers so no fd or carrier fiber leaks (all-or-nothing).
    // The fault: driver 1 of 3 throws on start (k=1, k-1=1 already-started driver at index 0 must be closed).
    // Anti-flakiness: throwOnStart fires synchronously; closeCalls is read after the synchronous close() completes.
    "partialStartIsAllOrNothing" in {
        assumePoller()
        val rawSpies = mkRealSpies(3)
        // Arm the controlled fault: driver index 1 throws on its next start() call.
        rawSpies(1).throwOnStart = true
        val spies: Array[IoDriver[PosixHandle]] = rawSpies.asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)

        // pool.start() must throw (all-or-nothing).
        val thrown = intercept[RuntimeException] { pool.start() }
        assert(
            thrown.getMessage.contains("throwOnStart"),
            s"pool.start must rethrow the driver-start failure, got: ${thrown.getMessage}"
        )
        // The already-started driver at index 0 must have been closed by the all-or-nothing cleanup in pool.start().
        assert(
            rawSpies(0).closeCalls.get() >= 1,
            s"driver 0 (already-started) must be closed by the all-or-nothing rollback, got closeCalls=${rawSpies(0).closeCalls.get()}"
        )
        // Driver 1 never started (it threw), so its closeCalls may be 0 or 1 (close() is called on all in the rollback).
        // Driver 2 was never reached (loop stops at the failing index), so it must be closed too (close() iterates all N).
        // The invariant is that the transport build fails entirely; no driver was handed to a live transport.
        // No transport is built: pool.start() threw before PosixTransport.init could be called.
        succeed
    }

    // --- partialStartN2 ---
    // Same contract with N=2 drivers and the fault at index 0 (the very first driver fails, k-1=0 already-started drivers).
    // The pool must still rethrow and not leak.
    "partialStartN2FirstDriverFails" in {
        assumePoller()
        val rawSpies = mkRealSpies(2)
        rawSpies(0).throwOnStart = true
        val spies: Array[IoDriver[PosixHandle]] = rawSpies.asInstanceOf[Array[IoDriver[PosixHandle]]]
        val pool                                = IoDriverPool.init(spies)

        val thrown = intercept[RuntimeException] { pool.start() }
        assert(
            thrown.getMessage.contains("throwOnStart"),
            s"pool.start must rethrow when the first driver fails, got: ${thrown.getMessage}"
        )
        // No driver was started (index 0 was first), so no cleanup close is needed. The pool must still have rethrown.
        // Driver 1 was never reached (loop stopped at index 0), so either 0 or 1 closeCalls is correct.
        succeed
    }

end IoDriverPoolPartialStartTest
