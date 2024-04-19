package kyo.scheduler

import java.util.concurrent.Executors
import kyo.scheduler.util.Threads
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class InternalClockTest extends AnyFreeSpec with NonImplicitAssertions:

    private def withClock[T](testCode: InternalClock => T): T =
        val executor = Executors.newSingleThreadExecutor(Threads("test-clock"))
        val clock    = new InternalClock(executor)
        try testCode(clock)
        finally
            clock.stop()
            executor.shutdown()
        end try
    end withClock

    "stop" in withClock { clock =>
        val initialMillis = clock.currentMillis()
        clock.stop()
        Thread.sleep(100)
        val finalMillis = clock.currentMillis()
        assert(finalMillis == initialMillis)
    }

    "currentMillis" in withClock { clock =>
        val startMillis = clock.currentMillis()
        Thread.sleep(10)
        val endMillis     = clock.currentMillis()
        val elapsedMillis = endMillis - startMillis
        assert(elapsedMillis >= 8)
        assert(elapsedMillis <= 12)
    }
end InternalClockTest
