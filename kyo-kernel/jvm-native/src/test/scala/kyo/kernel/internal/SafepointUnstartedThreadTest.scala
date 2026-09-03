package kyo.kernel.internal

import org.scalatest.freespec.AnyFreeSpec

class SafepointUnstartedThreadTest extends AnyFreeSpec:

    "stop misses a thread that never evaluated" in {
        assert(!Safepoint.stop(new Thread()))
    }

end SafepointUnstartedThreadTest
