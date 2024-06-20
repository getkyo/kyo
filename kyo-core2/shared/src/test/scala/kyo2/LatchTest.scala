package kyo2

import kyo.*

class LatchTest extends Test:

    "zero" in run {
        for
            latch <- Latch.init(0)
            _     <- latch.release
            _     <- latch.await
        yield succeed
    }

    "countDown + await" in run {
        for
            latch <- Latch.init(1)
            _     <- latch.release
            _     <- latch.await
        yield succeed
    }

    "countDown(2) + await" in run {
        for
            latch <- Latch.init(2)
            _     <- latch.release
            _     <- latch.release
            _     <- latch.await
        yield succeed
    }

    "countDown + fibers + await" in runJVM {
        for
            latch <- Latch.init(1)
            _     <- Async.run(latch.release)
            _     <- latch.await
        yield succeed
    }

    "countDown(2) + fibers + await" in runJVM {
        for
            latch <- Latch.init(2)
            _     <- Async.parallel(latch.release, latch.release)
            _     <- latch.await
        yield succeed
    }

    "contention" in runJVM {
        for
            latch <- Latch.init(1000)
            _     <- Async.parallel(List.fill(1000)(latch.release))
            _     <- latch.await
        yield succeed
    }
end LatchTest
