package kyo.scheduler.regulator

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kyo.scheduler.InternalTimer
import kyo.scheduler.TestTimer
import kyo.scheduler.util.Sleep
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.duration.*

class ConcurrencyTest extends AnyFreeSpec with NonImplicitAssertions {

    "up" in new Context {
        loadAvg = 0.9
        jitter = jitterLowerThreshold

        timer.advanceAndRun(regulateInterval * 2)
        assert(probes == 192)
        assert(updates == List(1, 2))
    }

    "down" in new Context {
        loadAvg = 0.9
        jitter = jitterUpperThreshold * 10

        timer.advanceAndRun(regulateInterval * 2)
        assert(probes == 134)
        assert(updates == List(-1, -2))
    }

    "noop" in new Context {
        loadAvg = 0.9
        jitter = (jitterUpperThreshold * 1.7).toInt

        timer.advanceAndRun(regulateInterval * 2)
        assert(probes == 184)
        assert(updates.isEmpty)
    }

    "probe jitter stays below regulator threshold with real sleep" in {
        val executor = Executors.newScheduledThreadPool(2)
        try {
            val timer           = InternalTimer(executor)
            val concurrencyDiff = new AtomicInteger(0)

            val concurrency = new Concurrency(
                () => 0.9,
                diff => { val _ = concurrencyDiff.addAndGet(diff) },
                Sleep(_),
                () => System.nanoTime(),
                timer
            )

            // Let the regulator run for several regulation cycles
            // Default: collectInterval=10ms, regulateInterval=1500ms
            Thread.sleep(5000)

            concurrency.stop()
            val totalDiff = concurrencyDiff.get()

            // With stable jitter and high load, the regulator should
            // be scaling UP or staying neutral, not reducing workers.
            assert(
                totalDiff >= -8,
                s"Concurrency regulator reduced workers by $totalDiff, " +
                    "indicating excessive probe jitter"
            )
        } finally {
            executor.shutdown()
        }
    }

    trait Context {
        val timer                = TestTimer()
        var loadAvg: Double      = 0.8
        var jitter: Long         = 0
        var probes               = 0
        var updates              = Seq.empty[Int]
        val collectWindow        = 200
        val collectInterval      = 10.millis
        val regulateInterval     = 1000.millis
        val jitterUpperThreshold = 1000000
        val jitterLowerThreshold = 800000
        val loadAvgTarget        = 0.8
        val stepExp              = 1.3

        val concurrency =
            new Concurrency(
                () => loadAvg,
                diff => updates :+= diff,
                _ => {
                    probes += 1
                    if (probes % 2 == 0)
                        timer.advance(jitter.nanos)
                },
                () => timer.currentNanos,
                timer,
                Config(
                    collectWindow,
                    collectInterval,
                    regulateInterval,
                    jitterUpperThreshold,
                    jitterLowerThreshold,
                    loadAvgTarget,
                    stepExp
                )
            )
    }
}
