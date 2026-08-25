package kyo.scheduler.regulator

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    "regulates on what the real sleep probe measures" in {
        val config           = Concurrency.defaultConfig
        val timer            = TestTimer()
        val concurrencyDiff  = new AtomicInteger(0)
        val probes           = new AtomicInteger(0)
        var jitterAtDecision = -1.0

        // The regulator reads jitter then load on this thread with no probe between, so the load supplier
        // is where this cycle's deciding jitter can be captured exactly.
        val running = new AtomicReference[Concurrency](null)
        val concurrency = new Concurrency(
            () => {
                jitterAtDecision = running.get().status().regulator.measurementsJitter
                0.9
            },
            diff => { val _ = concurrencyDiff.addAndGet(diff) },
            ms => {
                Sleep(ms)
                val _ = probes.incrementAndGet()
            },
            () => System.nanoTime(),
            timer,
            config
        )
        running.set(concurrency)

        // Virtual scheduling around a real probe: advanceAndRun fires collect and regulate on this thread, so every host runs the same
        // probe count and one regulation decision, only the measurement real. Wall time would let a slow host stretch into an extra cycle.
        timer.advanceAndRun(config.regulateInterval)
        concurrency.stop()

        val expectedProbes = (config.regulateInterval / config.collectInterval).toInt
        val status         = concurrency.status().regulator
        assert(probes.get() == expectedProbes, s"expected $expectedProbes probes, got ${probes.get()}")
        assert(status.probesCompleted == expectedProbes.toLong, "every probe should have completed")
        assert(status.adjustments == 1L, s"expected one regulation cycle, got ${status.adjustments}")

        // The decision must follow the probe's actual jitter; asserting the outcome directly (workers grew) assumes a quiet host, but a
        // contended one sheds workers correctly. The first step in either direction is 1, so the expected diff is exact.
        val expectedDiff =
            if (jitterAtDecision > config.jitterUpperThreshold) -1
            else if (jitterAtDecision < config.jitterLowerThreshold) 1
            else 0
        assert(
            concurrencyDiff.get() == expectedDiff,
            s"probe jitter of ${jitterAtDecision.toLong}ns against a band of " +
                s"[${config.jitterLowerThreshold.toLong}, ${config.jitterUpperThreshold.toLong}]ns " +
                s"calls for $expectedDiff, got ${concurrencyDiff.get()}"
        )
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
