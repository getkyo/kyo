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
            _       <- Async.parallel(List.fill(1000)(barrier.await))
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
end BarrierTest
