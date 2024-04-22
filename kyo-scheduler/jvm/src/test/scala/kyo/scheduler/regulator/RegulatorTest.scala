package kyo.scheduler.regulator

import kyo.scheduler.TestTimer
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.duration.*

class RegulatorTest extends AnyFreeSpec with NonImplicitAssertions:

    "load average" - {
        "below target" - {
            "when no jitter is present" in new Context:
                val regulator = new TestRegulator
                loadAvg = 0
                jitter = 0

                timer.advanceAndRun(regulateInterval * 2)
                assert(updates.isEmpty)

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 0)
                assert(updates.isEmpty)

            "with jitter below low threshold" in new Context:
                val regulator = new TestRegulator
                loadAvg = 0
                jitter = jitterLowerThreshold - 1

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 0)
                assert(updates.isEmpty)

            "with jitter between low and high thresholds" in new Context:
                val regulator = new TestRegulator
                loadAvg = 0
                jitter = (jitterLowerThreshold + jitterUpperThreshold) / 2

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 0)
                assert(updates.isEmpty)

            "with jitter above high threshold" in new Context:
                val regulator = new TestRegulator
                loadAvg = 0
                jitter = jitterUpperThreshold + 1

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 0)
                assert(updates.isEmpty)
        }
        "above target" - {
            "when no jitter is present" in new Context:
                val regulator = new TestRegulator
                loadAvg = 0.9
                jitter = 0

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates == List(1, 2))

            "with jitter below low threshold" in new Context:
                val regulator = new TestRegulator
                loadAvg = 0.9
                jitter = jitterLowerThreshold - 1

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates == List(1, 2))

            "with jitter between low and high thresholds" in new Context:
                val regulator = new TestRegulator
                loadAvg = 0.9
                jitter = jitterUpperThreshold

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates.isEmpty)

            "with jitter above high threshold" in new Context:
                val regulator = new TestRegulator
                loadAvg = 0.9
                jitter = jitterUpperThreshold * 10

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates == List(-1, -2))
        }
    }

    "uses exponential steps" - {
        "up" in new Context:
            val regulator = new TestRegulator
            loadAvg = 0.9
            jitter = jitterLowerThreshold - 1

            timer.advanceAndRun(regulateInterval * 10)
            assert(probes == 100)
            assert(updates == (1 to 10).map(Math.pow(_, stepExp).intValue()))
        "down" in new Context:
            val regulator = new TestRegulator
            loadAvg = 0.9
            jitter = jitterUpperThreshold * 10

            timer.advanceAndRun(regulateInterval * 10)
            assert(probes == 100)
            assert(updates == (1 to 10).map(-Math.pow(_, stepExp).intValue()))
        "reset" in new Context:
            val regulator = new TestRegulator
            loadAvg = 0.9

            jitter = jitterLowerThreshold - 1
            timer.advanceAndRun(regulateInterval * 10)
            jitter = jitterUpperThreshold * 10
            timer.advanceAndRun(regulateInterval * 10)

            assert(probes == 200)
            val expected = (1 to 10).map(Math.pow(_, stepExp).intValue()).toList
            assert(updates == (expected ::: expected.map(-_)))
    }

    trait Context:

        val timer                 = TestTimer()
        var loadAvg: Double       = 0d
        var collectWindow         = 10
        var collectInterval       = 10.millis
        var regulateInterval      = 100.millis
        var jitterUpperThreshold  = 200
        var jitterLowerThreshold  = 100
        var loadAvgTarget         = 0.8
        var stepExp               = 1.3
        var probes                = 0
        var updates               = Seq.empty[Int]
        var onUpdate: Int => Unit = _ => {}
        var jitter                = 0.0d

        class TestRegulator extends Regulator(
                () => loadAvg,
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
            ):
            def probe(): Unit =
                probes += 1
                val measurement = if probes % 2 == 0 then
                    jitter + 1
                else
                    1
                measure(measurement.toLong)
            end probe

            def update(diff: Int) =
                updates :+= diff
                onUpdate(diff)

            override def measure(v: Long) =
                super.measure(v)
        end TestRegulator
    end Context

end RegulatorTest
