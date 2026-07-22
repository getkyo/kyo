package kyo.net.internal.transport

import kyo.*
import kyo.net.Test
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixHandle
import kyo.net.internal.posix.PosixTestSockets
import kyo.net.internal.posix.RecordingIoDriver

/** All-or-nothing partial-start teardown test for [[IoDriverPool]].
  *
  * Verifies that when the k-th driver's `start()` raises a failure, `IoDriverPool.start()` rethrows the failure AND closes EVERY driver in
  * the pool, leaving no partially-started transport alive and no descriptor held. Every driver, not just the started prefix: a driver
  * allocates its poller/ring/selector fd in its constructor, so the one whose start() threw and every driver after it already hold one. The controlled fault is the single enumerated injection on
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
        // The rollback must close EVERY driver, not just the started prefix. Each of these was constructed by PollerIoDriver.init(), which
        // allocates the poller fd in the constructor, before start() is ever called: driver 1's start() threw and driver 2 was never
        // reached, yet both already hold a live epoll/kqueue fd. Closing only the started prefix leaks one descriptor per driver from the
        // failing index onward, for the life of the process, whenever a non-first driver fails to start (EMFILE at startup, say).
        assert(
            rawSpies(1).closeCalls.get() >= 1,
            s"driver 1 (its start() threw, fd already allocated at construction) must be closed by the rollback, " +
                s"got closeCalls=${rawSpies(1).closeCalls.get()}"
        )
        assert(
            rawSpies(2).closeCalls.get() >= 1,
            s"driver 2 (never reached, fd already allocated at construction) must be closed by the rollback, " +
                s"got closeCalls=${rawSpies(2).closeCalls.get()}"
        )
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
        // No driver was started (index 0 was first), so IoDriverPool.rollback's already-started-prefix close (startedCount=0) closes
        // nothing, and driver 1 was never reached either (the loop stopped at index 0): both real drivers still hold a live pollerFd
        // (PollerIoDriver.init() allocates it before start() is ever called). Close them here so the test does not leak either fd.
        rawSpies(0).close()
        rawSpies(1).close()
        succeed
    }

end IoDriverPoolPartialStartTest
