package kyo.scheduler.regulator

import kyo.scheduler.TestTimer
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
