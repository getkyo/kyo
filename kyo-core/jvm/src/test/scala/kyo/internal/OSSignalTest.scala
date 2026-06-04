package kyo.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// USR2 signal doesn't exist on Windows, and signal handling is Unix-specific.
class OsSignalTest extends kyo.test.Test[Any]:

    private val isLinux = java.lang.System.getProperty("os.name", "").toLowerCase.contains("linux")

    "handles on signal" in {
        assume(isLinux, "Unix signals only")
        val wasHandled = new CountDownLatch(1)

        OsSignal.handle("USR2", () => wasHandled.countDown())

        val signal = new sun.misc.Signal("USR2")
        sun.misc.Signal.raise(signal)

        // The handler runs on the JVM signal-dispatch thread, which can be CPU-starved under the
        // test runner's busy global pool, so signal delivery is delayed well past a tight bound.
        // The generous timeout only matters if the handler never fires (real failure); when it does
        // fire it completes far sooner.
        assert(wasHandled.await(10, TimeUnit.SECONDS))
    }

    "lazy" in {
        assume(isLinux, "Unix signals only")
        var wasHandled = false
        OsSignal.handle("USR2", () => wasHandled = true)
        assert(!wasHandled)
    }
end OsSignalTest
