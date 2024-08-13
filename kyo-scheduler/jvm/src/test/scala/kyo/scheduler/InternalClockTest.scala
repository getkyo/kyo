package kyo.scheduler

import java.util.concurrent.Executors
import kyo.scheduler.util.Threads
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec

class InternalClockTest extends AnyFreeSpec with NonImplicitAssertions {

    "stop" in withClock { clock =>
        val initialMillis = clock.currentMillis()
        clock.stop()
        Thread.sleep(10)
        val finalMillis = clock.currentMillis()
        assert(finalMillis < initialMillis + 5)
    }

    "currentMillis" in withClock { clock =>
        val startMillis = clock.currentMillis()
        Thread.sleep(100)
        val endMillis     = clock.currentMillis()
        val elapsedMillis = endMillis - startMillis
        assert(elapsedMillis >= 50)
        assert(elapsedMillis <= 150)
    }

    private def withClock[A](testCode: InternalClock => A): A = {
        val executor = Executors.newSingleThreadExecutor(Threads("test-clock"))
        val clock    = new InternalClock(executor)
        try testCode(clock)
        finally {
            clock.stop()
            executor.shutdown()
        }
    }
}
