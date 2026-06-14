package kyo.ffi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kyo.ffi.Ffi.CloseOutcome
import kyo.ffi.internal.GuardCore

/** Tests for the configurable drain timeout in [[GuardCore]]. */
class GuardDrainTimeoutConfigTest extends Test:

    // The leaves mutate the process-global GuardCore.drainTimeoutNanos var (with try/finally restore); run them
    // sequentially so the default-timeout leaf does not observe another leaf's mid-flight override.
    override def config = super.config.sequential

    "default timeout is 5 seconds" in {
        assert(GuardCore.drainTimeoutNanos == 5_000_000_000L)
    }

    "custom timeout via drainTimeoutNanos var" in {
        val original = GuardCore.drainTimeoutNanos
        try
            val custom = 2_000_000_000L // 2 seconds
            GuardCore.drainTimeoutNanos = custom
            assert(GuardCore.DrainTimeoutNanos == custom)
        finally
            GuardCore.drainTimeoutNanos = original
        end try
    }

    "drainInFlight respects configured timeout" in {
        val original = GuardCore.drainTimeoutNanos
        try
            // Set a very short timeout (10ms)
            GuardCore.drainTimeoutNanos = 10L * 1000L * 1000L

            val core = new GuardCore(() => (), () => ()) // no-op platform-closer

            // Simulate an in-flight callback that never ends
            val began = core.beginCallback()
            assert(began == true)

            val closedLatch                         = new CountDownLatch(1)
            @volatile var closeResult: CloseOutcome = CloseOutcome.AlreadyClosed

            val closerThread = new Thread(
                () =>
                    closeResult = core.close()
                    closedLatch.countDown()
                ,
                "drain-config-closer"
            )
            closerThread.setDaemon(true)

            val startNanos = System.nanoTime()
            closerThread.start()

            // Should complete well within 100ms (not the default 5s)
            assert(closedLatch.await(500, TimeUnit.MILLISECONDS) == true)
            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L
            // Drain timed out with one callback still in flight → TimedOut outcome.
            assert(closeResult == CloseOutcome.TimedOut)

            // Verify it didn't take anywhere near the default 5 seconds
            assert(elapsedMs < 500L)
        finally
            GuardCore.drainTimeoutNanos = original
        end try
    }

end GuardDrainTimeoutConfigTest
