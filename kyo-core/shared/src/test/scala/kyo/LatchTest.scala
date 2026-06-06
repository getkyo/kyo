package kyo

import kyo.*

class LatchTest extends kyo.test.Test[Any]:

    "use" in {
        Latch.initWith(1) { latch =>
            for
                _ <- latch.release
                _ <- latch.await
                p <- latch.pending
            yield assert(p == 0)
        }
    }

    "zero" in {
        for
            latch <- Latch.init(0)
            _     <- latch.release
            _     <- latch.await
            p     <- latch.pending
        yield assert(p == 0)
    }

    "countDown + await" in {
        for
            latch <- Latch.init(1)
            _     <- latch.release
            _     <- latch.await
            p     <- latch.pending
        yield assert(p == 0)
    }

    "countDown(2) + await" in {
        for
            latch <- Latch.init(2)
            _     <- latch.release
            _     <- latch.release
            _     <- latch.await
            p     <- latch.pending
        yield assert(p == 0)
    }

    "countDown + fibers + await" in {
        for
            latch <- Latch.init(1)
            _     <- Fiber.initUnscoped(latch.release)
            _     <- latch.await
            p     <- latch.pending
        yield assert(p == 0)
    }

    "countDown(2) + fibers + await" in {
        for
            latch <- Latch.init(2)
            _     <- Async.zip(latch.release, latch.release)
            _     <- latch.await
            p     <- latch.pending
        yield assert(p == 0)
    }

    "contention" in {
        for
            latch <- Latch.init(1000)
            _     <- Async.fill(1000, 1000)(latch.release)
            _     <- latch.await
            p     <- latch.pending
        yield assert(p == 0)
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        "should initialize with correct pending count" in {
            val latch = Latch.Unsafe.init(3)
            assert(latch.pending() == 3)
        }

        "should decrement pending count on release" in {
            val latch = Latch.Unsafe.init(3)
            latch.release()
            assert(latch.pending() == 2)
        }

        "should release all waiting parties when count reaches zero" in {
            val latch         = Latch.Unsafe.init(3)
            var releasedCount = 0

            // Create 3 fibers waiting on the latch
            val fibers = List.fill(3)(latch.await())
            fibers.foreach(_.onComplete(_ => releasedCount += 1))

            // Release the latch 3 times
            for _ <- 1 to 3 do latch.release()

            assert(latch.pending() == 0)
            assert(releasedCount == 3)
        }

        "should create noop latch for n <= 0" in {
            val latch = Latch.Unsafe.init(0)
            assert(latch.pending() == 0)
            val fiber = latch.await()
            assert(fiber.done())
        }

        "should work with multiple releases" in {
            val latch         = Latch.Unsafe.init(2)
            var releasedCount = 0

            val fiber = latch.await()
            fiber.onComplete(_ => releasedCount += 1)

            latch.release()
            assert(latch.pending() == 1)

            latch.release()
            assert(latch.pending() == 0)

            assert(releasedCount == 1)
        }

        "should convert to safe Latch" in {
            val unsafeLatch      = Latch.Unsafe.init(2)
            val safeLatch: Latch = unsafeLatch.safe
            discard(safeLatch)
            succeed("Unsafe.safe returns a safe Latch wrapper (verified by the Latch ascription)")
        }
    }
end LatchTest
