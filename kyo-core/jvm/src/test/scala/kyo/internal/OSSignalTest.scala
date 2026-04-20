package kyo.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.Test

// USR2 signal doesn't exist on Windows, and signal handling is Unix-specific.
class OsSignalTest extends Test:

    private val isLinux = java.lang.System.getProperty("os.name", "").toLowerCase.contains("linux")

    "handles on signal" in {
        assume(isLinux, "Unix signals only")
        val wasHandled = new CountDownLatch(1)

        OsSignal.handle("USR2", () => wasHandled.countDown())

        val signal = new sun.misc.Signal("USR2")
        sun.misc.Signal.raise(signal)

        assert(wasHandled.await(100, TimeUnit.MILLISECONDS))
    }

    "lazy" in {
        assume(isLinux, "Unix signals only")
        var wasHandled = false
        OsSignal.handle("USR2", () => wasHandled = true)
        assert(!wasHandled)
    }
end OsSignalTest
