package kyo.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// USR2 signal doesn't exist on Windows, and signal handling is Unix-specific.
class OsSignalTest extends kyo.test.Test[Any]:

    // Both leaves install a handler for the SAME OS signal, and sun.misc.Signal keeps only one
    // handler per signal; under concurrent leaf execution the "lazy" leaf can replace this leaf's
    // handler before it raises, so the countDown never fires. Run the leaves sequentially.
    override def config = super.config.sequential

    private val isLinux = java.lang.System.getProperty("os.name", "").toLowerCase.contains("linux")

    "handles on signal" in {
        assume(isLinux, "Unix signals only")
        val wasHandled = new CountDownLatch(1)

        OsSignal.handle("USR2", () => wasHandled.countDown())

        val signal = new sun.misc.Signal("USR2")
        sun.misc.Signal.raise(signal)

        // With sequential leaves the handler is not clobbered; it fires on the JVM signal-dispatch
        // thread within milliseconds. The few-second bound is just a safety cap for a real failure.
        assert(wasHandled.await(5, TimeUnit.SECONDS))
    }

    "lazy" in {
        assume(isLinux, "Unix signals only")
        var wasHandled = false
        OsSignal.handle("USR2", () => wasHandled = true)
        assert(!wasHandled)
    }
end OsSignalTest
