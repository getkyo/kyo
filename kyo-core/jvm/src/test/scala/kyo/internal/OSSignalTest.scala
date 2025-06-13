package kyo.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.Test

class OsSignalTest extends Test:

    "handles on signal" in {
        val wasHandled = new CountDownLatch(1)

        OsSignal.handle("USR2", () => wasHandled.countDown())

        val signal = new sun.misc.Signal("USR2")
        sun.misc.Signal.raise(signal)

        assert(wasHandled.await(100, TimeUnit.MILLISECONDS))
    }

    "lazy" in {
        var wasHandled = false
        OsSignal.handle("USR2", () => wasHandled = true)
        assert(!wasHandled)
    }
end OsSignalTest
