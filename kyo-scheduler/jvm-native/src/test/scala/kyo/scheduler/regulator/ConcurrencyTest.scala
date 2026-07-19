package kyo.scheduler.regulator

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

    "calibration raises the grow threshold above a noisy platform floor" in new Context {
        loadAvg = 0.9
        // dev lands at 0.9M: inside the configured (0.8M, 1.0M) dead band, so the
        // regulator holds forever even though load exceeds the target.
        jitter = 1800000

        timer.advanceAndRun(regulateInterval)
        assert(updates.isEmpty)

        // A platform floor at the observed jitter re-anchors the band (grow below 1.8M).
        concurrency.calibrate(900000)
        timer.advanceAndRun(regulateInterval)
        assert(updates == List(1))
    }

    "calibration below the configured thresholds keeps the configured behavior" in new Context {
        loadAvg = 0.9
        jitter = jitterLowerThreshold // dev = 0.4M: below the configured grow threshold

        concurrency.calibrate(100000) // quiet platform: configured values stay in force
        timer.advanceAndRun(regulateInterval * 2)
        assert(updates == List(1, 2))
    }

    "calibration is capped so extreme startup noise cannot disable regulation" in new Context {
        loadAvg = 0.9
        jitter = 30000000 // dev = 15M: above even the 10x-capped shrink threshold

        concurrency.calibrate(1e12)
        timer.advanceAndRun(regulateInterval)
        assert(updates == List(-1))
    }

    "probe jitter stays below regulator threshold with real sleep" in {
        try {
            val timer           = InternalTimer(kyo.scheduler.TestExecutors.scheduled)
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
        } finally ()
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
