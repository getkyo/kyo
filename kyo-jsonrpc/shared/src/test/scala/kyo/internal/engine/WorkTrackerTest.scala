package kyo.internal.engine

import kyo.*

class WorkTrackerTest extends kyo.JsonRpcTest:

    // Unsafe: acquire and release are the unsafe-tier calls the engine makes from completion callbacks; the tests drive them the same way.
    import AllowUnsafe.embrace.danger

    "awaitIdle completes at once when no work is in flight" in {
        val tracker = WorkTracker.init()
        tracker.awaitIdle.andThen(assert(tracker.count() == 0))
    }

    "awaitIdle waits until the last unit of work is released" in {
        val tracker = WorkTracker.init()
        tracker.acquire()
        tracker.acquire()
        for
            waiter    <- Fiber.initUnscoped(tracker.awaitIdle)
            _         <- assertEventually(Sync.defer(tracker.idleWaiters() == 1))
            _         <- Sync.defer(tracker.release())
            afterOne  <- waiter.done
            _         <- Sync.defer(tracker.release())
            _         <- waiter.get
            remaining <- Sync.defer(tracker.count())
        yield
            assert(!afterOne, "awaitIdle returned while one unit of work was still in flight")
            assert(remaining == 0)
        end for
    }

    "work acquired after a drain is waited for" in {
        // The sequence that exposed a count and a drain signal held in separate cells: the first drain completes a signal, and a new unit of
        // work must be paired with a fresh one, never with the completed signal.
        val tracker = WorkTracker.init()
        tracker.acquire()
        tracker.release()
        tracker.acquire()
        for
            waiter <- Fiber.initUnscoped(tracker.awaitIdle)
            _      <- assertEventually(Sync.defer(tracker.idleWaiters() == 1))
            before <- waiter.done
            _      <- Sync.defer(tracker.release())
            _      <- waiter.get
        yield assert(!before, "awaitIdle returned on the signal of an earlier drain")
        end for
    }

    "the mirror follows the count" in {
        val mirror  = AtomicInt.Unsafe.init(0)
        val tracker = WorkTracker.initMirrored(Present(mirror))
        tracker.acquire()
        tracker.acquire()
        val afterAcquire = mirror.get()
        tracker.release()
        val afterRelease = mirror.get()
        assert(afterAcquire == 2 && afterRelease == 1 && tracker.count() == 1)
    }

    "under concurrent acquire and release a waiter never returns while the work it observed is still counted" in {
        val workers = 16
        val rounds  = 200
        val tracker = WorkTracker.init()
        for
            earlyReturns <- AtomicInt.init(0)
            // A probe holds one unit of work of its own and starts a waiter. The waiter checks, when awaitIdle returns, whether the probe
            // still holds that unit; the probe clears the flag only just before releasing it, so a waiter that returns while the unit is
            // counted records an early return. Churn workers cross zero concurrently, which is when a torn read of the count and the drain
            // signal would let a waiter through.
            probe =
                for
                    held    <- AtomicBoolean.init(true)
                    started <- Latch.init(1)
                    _       <- Sync.defer(tracker.acquire())
                    waiter  <- Fiber.initUnscoped {
                        started.release.andThen(tracker.awaitIdle).andThen {
                            held.get.map(stillHeld => if stillHeld then earlyReturns.incrementAndGet.unit else Kyo.unit)
                        }
                    }
                    _ <- started.await
                    _ <- held.set(false)
                    _ <- Sync.defer(tracker.release())
                    _ <- waiter.get
                yield ()
            churn = Kyo.foreachDiscard(1 to rounds)(_ => Sync.defer { tracker.acquire(); tracker.release() })
            _ <- Async.foreachDiscard(1 to workers, workers) { i =>
                if i % 2 == 0 then churn
                else Kyo.foreachDiscard(1 to rounds / 4)(_ => probe)
            }
            _     <- tracker.awaitIdle
            count <- Sync.defer(tracker.count())
            early <- earlyReturns.get
        yield
            assert(early == 0, s"$early waiters returned while the work they observed was still in flight")
            assert(count == 0)
        end for
    }

end WorkTrackerTest
