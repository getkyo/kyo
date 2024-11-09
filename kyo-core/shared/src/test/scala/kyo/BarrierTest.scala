package kyo

import kyo.*

class BarrierTest extends Test:

    "zero" in run {
        for
            barrier <- Barrier.init(0)
            _       <- barrier.await
        yield succeed
    }

    "await" in run {
        for
            barrier <- Barrier.init(1)
            _       <- barrier.await
        yield succeed
    }

    "await(2)" in run {
        for
            barrier <- Barrier.init(2)
            _       <- Async.parallel(barrier.await, barrier.await)
        yield succeed
    }

    "await + fibers" in runJVM {
        for
            barrier <- Barrier.init(1)
            _       <- Async.run(barrier.await)
            _       <- barrier.await
        yield succeed
    }

    "await(2) + fibers" in runJVM {
        for
            barrier <- Barrier.init(2)
            _       <- Async.parallel(barrier.await, barrier.await)
        yield succeed
    }

    "contention" in runJVM {
        for
            barrier <- Barrier.init(1000)
            _       <- Async.parallelUnbounded(List.fill(1000)(barrier.await))
        yield succeed
    }

    "pending count" in run {
        for
            barrier <- Barrier.init(2)
            count1  <- barrier.pending
            _       <- Async.parallel(barrier.await, barrier.await)
            count2  <- barrier.pending
        yield
            assert(count1 == 2)
            assert(count2 == 0)
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        "should initialize with correct pending count" in {
            val barrier = Barrier.Unsafe.init(3)
            assert(barrier.pending() == 3)
        }

        "should decrement pending count on await" in {
            val barrier = Barrier.Unsafe.init(3)
            barrier.await()
            assert(barrier.pending() == 2)
        }

        "should release all parties when last one arrives" in {
            val barrier       = Barrier.Unsafe.init(3)
            var releasedCount = 0

            // Simulate 3 parties arriving
            for _ <- 1 to 3 do
                val fiber = barrier.await()
                fiber.onComplete(_ => releasedCount += 1)

            assert(barrier.pending() == 0)
            assert(releasedCount == 3)
        }

        "should create noop barrier for n <= 0" in {
            val barrier = Barrier.Unsafe.init(0)
            assert(barrier.pending() == 0)
            val fiber = barrier.await()
            assert(fiber.done())
        }

        "should work with multiple awaits" in {
            val barrier       = Barrier.Unsafe.init(2)
            var releasedCount = 0

            // First await
            val fiber1 = barrier.await()
            fiber1.onComplete(_ => releasedCount += 1)
            assert(barrier.pending() == 1)

            // Second await
            val fiber2 = barrier.await()
            fiber2.onComplete(_ => releasedCount += 1)
            assert(barrier.pending() == 0)

            assert(releasedCount == 2)
        }

        "should convert to safe Barrier" in {
            val unsafeBarrier = Barrier.Unsafe.init(2)
            val safeBarrier   = unsafeBarrier.safe

            assert(safeBarrier.isInstanceOf[Barrier])
        }
    }

end BarrierTest
