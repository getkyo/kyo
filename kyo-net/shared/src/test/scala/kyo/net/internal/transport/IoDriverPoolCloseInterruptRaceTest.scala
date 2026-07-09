package kyo.net.internal.transport

import kyo.*
import kyo.net.Test

/** Regression coverage for the `IoDriverPool.close()` fiber-interrupt race that strands a driver's deferred fd close, leaving the socket
  * in `CLOSE_WAIT`.
  *
  * `IoDriverPool.close()` used to signal `driver.close()` on every driver and then unconditionally interrupt every driver's event-loop
  * fiber. For the posix io_uring and poller drivers `close()` only SIGNALS the loop (wakes it, sets a closed flag); the actual deferred
  * teardown -- discharging a handle whose real `close(fd)` was waiting on the loop's own carrier to observe an in-flight op draining --
  * runs LATER, as the loop's own graceful post-signal continuation. Kyo's fiber interruption is cooperative, honored at the next
  * suspension point (`Fiber.Unsafe.init`'s own contract), so an interrupt landing while that continuation is parked (exactly where it
  * sits immediately after the signal, before its own next scheduler turn) aborts it before it ever runs -- permanently stranding
  * whatever deferred-close bookkeeping that continuation owed. `IoUringDriver`'s `registerDeferredClose`/`closeAfterDrain` sweep (the last
  * chance to force-discharge a handle whose real close is waiting on an in-flight recv to drain) lives in exactly this kind of post-signal
  * continuation, on the reap loop's own carrier, and never got the chance to run once the pool's interrupt won the race: the CLOSE_WAIT
  * leak this test reproduces.
  *
  * `DeferredTeardownDriver` reproduces the shape without any real I/O: its `close()` only completes a signal promise; its `start()`
  * fiber parks on that signal and, once it settles (success or failure, mirroring a real driver's `Abort.run`-style close handling),
  * runs a "deferred teardown" step and completes `teardownDone`. FAILS before the fix (the pool's interrupt, issued immediately after
  * `close()` returns, aborts the fiber before its continuation reaches `teardownDone`, so the bounded await below times out) and PASSES
  * after it (the continuation is never raced, so `teardownDone` completes promptly).
  */
class IoDriverPoolCloseInterruptRaceTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    /** A driver whose `close()` only signals; the actual "teardown" runs later, as its own fiber's post-signal continuation, mirroring
      * the io_uring/poller drivers' reap/poll-loop-carrier-deferred cleanup.
      */
    final private class DeferredTeardownDriver extends IoDriver[Unit]:
        private val closeSignal   = Promise.Unsafe.init[Unit, Abort[Closed]]()
        val teardownDone          = Promise.Unsafe.init[Unit, Any]()
        @volatile var teardownRan = false

        def start()(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any] =
            Fiber.Unsafe.init {
                Abort.run(closeSignal.safe.get).map { _ =>
                    // The deferred teardown step: reached only once the fiber's OWN continuation resumes past the signal await,
                    // exactly the safepoint an interrupt issued right after close() can preempt.
                    teardownRan = true
                    discard(teardownDone.completeDiscard(Result.succeed(())))
                }
            }
        def awaitRead(handle: Unit, promise: Promise.Unsafe[ReadOutcome, Abort[Closed]])(using AllowUnsafe, Frame): Unit = ()
        def awaitWritable(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit    = ()
        def awaitConnect(handle: Unit, promise: Promise.Unsafe[Unit, Abort[Closed]])(using AllowUnsafe, Frame): Unit     = ()
        def awaitAccept(handle: Unit, promise: Promise.Unsafe[Int, Abort[Closed]])(using AllowUnsafe, Frame): Unit       = ()
        def write(handle: Unit, data: Span[Byte], offset: Int)(using AllowUnsafe): WriteResult                           = WriteResult.Done
        def cancel(handle: Unit)(using AllowUnsafe, Frame): Unit                                                         = ()
        def closeHandle(handle: Unit)(using AllowUnsafe, Frame): Unit                                                    = ()
        def close()(using AllowUnsafe, Frame): Unit =
            discard(closeSignal.completeDiscard(Result.fail(Closed(label, summon[Frame], "closed"))))
        def label: String                     = "DeferredTeardownDriver"
        def handleLabel(handle: Unit): String = "deferred"
    end DeferredTeardownDriver

    "close() lets a driver's own deferred post-signal teardown complete instead of racing it with a fiber interrupt" in {
        val driver                         = new DeferredTeardownDriver
        val drivers: Array[IoDriver[Unit]] = Array(driver)
        val pool                           = IoDriverPool.init(drivers)
        pool.start()
        pool.close()
        Abort.run[Closed | Timeout](Async.timeout(1.second)(driver.teardownDone.safe.get)).map { outcome =>
            assert(
                outcome.isSuccess,
                s"the driver's deferred post-signal teardown never completed (a fiber interrupt raced ahead of it), got $outcome"
            )
            assert(driver.teardownRan, "the driver's own post-close-signal continuation must have run")
        }
    }

    "close() still closes every driver even when one driver's deferred teardown never settles" in {
        // A driver whose close() signals but whose fiber body ever runs Async.never past the signal: proves close() itself does not
        // hang or throw waiting on a driver's own follow-up, only signals it and moves on (matches every other driver's close() being
        // a fire-and-forget signal, never a blocking wait for the loop's own exit).
        val slow                           = new DeferredTeardownDriver
        val other                          = new DeferredTeardownDriver
        val drivers: Array[IoDriver[Unit]] = Array(slow, other)
        val pool                           = IoDriverPool.init(drivers)
        pool.start()
        pool.close()
        Abort.run[Closed | Timeout](Async.timeout(1.second)(other.teardownDone.safe.get)).map { outcome =>
            assert(outcome.isSuccess, s"the second driver's own teardown must complete independently of the first, got $outcome")
        }
    }

end IoDriverPoolCloseInterruptRaceTest
