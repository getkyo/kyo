package kyo.test.runner

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** JVM-only: verifies that [[Cli.withTestExit]] isolates exit stubs per OS thread. */
class CliConcurrencyTest extends kyo.test.Test[Any]:

    // ── ThreadLocal scoping (two concurrent threads each see their own stub) ──

    "phase5-test-4: withTestExit is thread-local; two concurrent threads intercept independent exit codes" in {
        // Thread A wraps withTestExit { exitForTest(1) } and catches SystemExitException(1).
        // Thread B wraps withTestExit { exitForTest(2) } concurrently and catches SystemExitException(2).
        // Neither thread sees the other's code; real CliPlatform.exit is never invoked.
        val startLatch = new CountDownLatch(2)
        val doneLatch  = new CountDownLatch(2)
        val codeA      = new java.util.concurrent.atomic.AtomicInteger(-1)
        val codeB      = new java.util.concurrent.atomic.AtomicInteger(-1)
        val threadA = new Thread(() =>
            startLatch.countDown()
            startLatch.await()
            try Cli.withTestExit { Cli.exitForTest(1) }
            catch
                case ex: SystemExitException => codeA.set(ex.code)
            end try
            doneLatch.countDown()
        )
        val threadB = new Thread(() =>
            startLatch.countDown()
            startLatch.await()
            try Cli.withTestExit { Cli.exitForTest(2) }
            catch
                case ex: SystemExitException => codeB.set(ex.code)
            end try
            doneLatch.countDown()
        )
        threadA.start()
        threadB.start()
        doneLatch.await(5, TimeUnit.SECONDS)
        assert(codeA.get() == 1, s"Thread A expected code 1, got ${codeA.get()}")
        assert(codeB.get() == 2, s"Thread B expected code 2, got ${codeB.get()}")
    }

end CliConcurrencyTest
