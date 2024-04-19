package kyo.scheduler

import java.util.concurrent.Executors
import kyo.scheduler.util.Threads
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class ClockTest extends AnyFreeSpec with NonImplicitAssertions:

    private def withClock[T](testCode: Clock => T): T =
        val executor = Executors.newSingleThreadExecutor(Threads("test-clock"))
        val clock    = new Clock(executor)
        try testCode(clock)
        finally
            clock.stop()
            executor.shutdown()
        end try
    end withClock

    "currentMillis" - {
        "returns the current milliseconds" in withClock { clock =>
            val startMillis = System.currentTimeMillis()
            val clockMillis = clock.currentMillis()
            val endMillis   = System.currentTimeMillis()
            assert(clockMillis >= startMillis - 1)
            assert(clockMillis <= endMillis + 1)
        }
    }

    "stop" - {
        "stops the clock" in withClock { clock =>
            val initialMillis = clock.currentMillis()
            clock.stop()
            Thread.sleep(100)
            val finalMillis = clock.currentMillis()
            assert(finalMillis == initialMillis)
        }
    }

    "clock accuracy" - {
        "provides accurate milliseconds over time" in withClock { clock =>
            val startMillis = clock.currentMillis()
            Thread.sleep(10)
            val endMillis     = clock.currentMillis()
            val elapsedMillis = endMillis - startMillis
            assert(elapsedMillis >= 9)
            assert(elapsedMillis <= 11)
        }
    }
end ClockTest
