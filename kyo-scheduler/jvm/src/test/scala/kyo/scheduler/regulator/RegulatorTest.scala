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

    // "Doesn't allow more than collectSamples to be pending" - {
    //     "in one interval" in new Context:
    //         val regulator = new TestRegulator
    //         onProbe = () =>
    //             for _ <- 1 to collectSamples + 1 do
    //                 regulator.measure(collectInterval.toMillis)

    //         timer.advance(collectInterval)
    //         assert(probes == collectSamples)

    //     "in multiple intervals" in new Context:
    //         val regulator = new TestRegulator
    //         onProbe = () =>
    //             for _ <- 1 to collectSamples + 1 do
    //                 regulator.measure(collectInterval.toMillis)

    //         timer.advance(collectInterval * 3)
    //         assert(probes == collectSamples * 3)
    // }

    // "Adjustment uses exponential steps when changes aren't having the desired effect" - {
    //     "step increases exponentially upwards on every step if the jitter continues below low threshold" in {
    //         val regulator = new TestRegulator
    //         onProbe = () => regulator.measure(jitterLowerThreshold - 1)

    //         timer.advance(regulateInterval * 4)
    //         assert(updates == (1 to 4).map(Math.pow(_, stepExp)).sum.toInt)
    //     }

    //     "step increases exponentially downwards on every step if the jitter continues above max" in {
    //         val regulator = new TestRegulator
    //         onProbe = () => regulator.measure(jitterUpperThreshold + 1)

    //         timer.advance(regulateInterval * 4)
    //         assert(updates == (1 to 4).map(i => -Math.pow(i, stepExp)).sum.toInt)
    //     }

    //     "step is reset when the different thresholds are hit" - {
    //         "when jitter moves from below low threshold to within acceptable range" in {
    //             val regulator = new TestRegulator
    //             onProbe = () =>
    //                 if probes < 2 then
    //                     regulator.measure(jitterLowerThreshold - 1)
    //                 else
    //                     regulator.measure((jitterLowerThreshold + jitterUpperThreshold) / 2)

    //             timer.advance(regulateInterval * 4)
    //             assert(updates == Math.pow(1, stepExp).toInt)
    //         }

    //         "when jitter moves from above high threshold to within acceptable range" in {
    //             val regulator = new TestRegulator
    //             onProbe = () =>
    //                 if probes < 2 then
    //                     regulator.measure(jitterUpperThreshold + 1)
    //                 else
    //                     regulator.measure((jitterLowerThreshold + jitterUpperThreshold) / 2)

    //             timer.advance(regulateInterval * 4)
    //             assert(updates == -Math.pow(1, stepExp).toInt)
    //         }
    //     }
    // }
end RegulatorTest
