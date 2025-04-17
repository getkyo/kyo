package kyo.scheduler.regulator

import kyo.scheduler.TestTimer
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.duration.*
import scala.util.Random

class AdmissionTest extends AnyFreeSpec with NonImplicitAssertions {

    "isn't affected if queuing delay is below low" in new Context {
        loadAvg = 0.7
        jitter = jitterLowerThreshold

        timer.advanceAndRun(regulateInterval * 2)
        assert(admission.percent() == 100)
    }

    "reduces admission percent when queuing delay is high" in new Context {
        loadAvg = 0.9
        jitter = jitterUpperThreshold * 10

        timer.advanceAndRun(regulateInterval * 2)
        assert(admission.percent() == 97)
    }

    "reject" - {

        "no key" in new Context {
            loadAvg = 0.9
            jitter = jitterUpperThreshold * 10

            timer.advanceAndRun(regulateInterval * 7)
            assert(admission.percent() == 41)

            val samples  = 100000
            val rejected = Seq.fill(samples)(()).count(_ => admission.reject())
            assert(Math.abs(100 - 41 - rejected * 100 / samples) <= 5)
        }

        "int key" in new Context {
            loadAvg = 0.9
            jitter = jitterUpperThreshold * 10

            timer.advanceAndRun(regulateInterval * 7)
            assert(admission.percent() == 41)

            val samples  = 10000
            val rejected = Seq.fill(samples)(Random.nextInt()).count(admission.reject)
            assert(Math.abs(100 - 41 - rejected * 100 / samples) <= 5)
        }

        "string key" in new Context {
            loadAvg = 0.9
            jitter = jitterUpperThreshold * 10

            timer.advanceAndRun(regulateInterval * 7)
            assert(admission.percent() == 41)

            val samples  = 10000
            val rejected = Seq.fill(samples)(Random.nextString(10)).count(admission.reject)
            assert(Math.abs(100 - 41 - rejected * 100 / samples) <= 5)
        }
    }

    "rotation window" - {
        "rejection set varies over time with fixed load" in new Context {
            loadAvg = 0.9
            jitter = jitterUpperThreshold * 10

            timer.advanceAndRun(regulateInterval * 2)
            assert(admission.percent() == 97)

            val keys           = (1 to 1000).toSet
            val window1Rejects = keys.filter(admission.reject)
            assert(window1Rejects.size > 0)

            timer.advance(1.hour)
            assert(admission.percent() == 97)
            val window2Rejects = keys.filter(admission.reject)
            assert(window2Rejects.size > 0)

            timer.advance(1.hour)
            assert(admission.percent() == 97)
            val window3Rejects = keys.filter(admission.reject)
            assert(window3Rejects.size > 0)

            assert(window1Rejects != window2Rejects)
            assert(window2Rejects != window3Rejects)
            assert(window1Rejects != window3Rejects)

            assert((window1Rejects.size - 30).abs <= 10)
            assert((window2Rejects.size - 30).abs <= 10)
            assert((window3Rejects.size - 30).abs <= 10)
        }

        "different loads give proportionally different rejection sets" in new Context {
            jitter = jitterUpperThreshold * 10

            loadAvg = 0.9
            timer.advanceAndRun(regulateInterval * 2)
            assert(admission.percent() == 97)

            val keys              = (1 to 1000).toSet
            val highAcceptRejects = keys.filter(admission.reject)

            loadAvg = 0.95
            timer.advanceAndRun(regulateInterval * 4)
            assert(admission.percent() == 59)

            timer.advance(1.hour)
            val lowAcceptRejects = keys.filter(admission.reject)

            assert(lowAcceptRejects.size > highAcceptRejects.size)
        }
    }

    trait Context {
        val timer           = TestTimer()
        var loadAvg: Double = 0.8
        var jitter          = 0L

        val collectWindow        = 10
        val collectInterval      = 10.millis
        val regulateInterval     = 100.millis
        val jitterUpperThreshold = 100
        val jitterLowerThreshold = 80
        val loadAvgTarget        = 0.8
        val stepExp              = 1.5
        var probes               = 0

        val admission = new Admission(
            () => loadAvg,
            task => {
                probes += 1
                if (probes % 2 == 0)
                    timer.currentNanos += jitter * 1000000
                task.run(0, null, Long.MaxValue)
                ()
            },
            () => timer.currentNanos / 1000000,
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
