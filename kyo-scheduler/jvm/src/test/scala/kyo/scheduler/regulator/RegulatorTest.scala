package kyo.scheduler.regulator

import kyo.scheduler.TestTimer
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.duration.*

class RegulatorTest extends AnyFreeSpec with NonImplicitAssertions:

    val timer                 = TestTimer()
    var loadAvg: Double       = 0d
    var collectWindowExp      = 11 // 2^11=2048 ~2 regulate intervals
    var collectInterval       = 100.millis
    var collectSamples        = 1
    var regulateInterval      = 1000.millis
    var jitterUpperThreshold  = 1000000
    var jitterLowerThreshold  = 800000
    var loadAvgTarget         = 0.8
    var stepExp               = 1.3
    var probes                = 0
    var updates               = Seq.empty[Int]
    var onProbe: () => Unit   = () => {}
    var onUpdate: Int => Unit = _ => {}

    class TestRegulator extends Regulator(
            () => loadAvg,
            timer,
            Config(
                collectWindowExp,
                collectInterval,
                collectSamples,
                regulateInterval,
                jitterUpperThreshold,
                jitterLowerThreshold,
                loadAvgTarget,
                stepExp
            )
        ):
        def probe(): Unit =
            probes += 1
            onProbe()

        def update(diff: Int) =
            updates +:= 1
            onUpdate(diff)

        override def measure(v: Long) =
            super.measure(v)
    end TestRegulator

    "Considers the load average to make adjustments" - {
        "when no jitter is present" in {
            val regulator = new TestRegulator
            onProbe = () => regulator.measure(1)

            timer.advance(regulateInterval * 2)
            assert(updates.isEmpty)

            loadAvg = 0.9

            timer.advance(regulateInterval * 2)
            assert(probes > 0)
            assert(updates == Seq.fill(2)(1))
        }

        "with jitter below low threshold" in {
            val regulator = new TestRegulator
            onProbe = () => regulator.measure(jitterLowerThreshold - 1)

            timer.advance(regulateInterval * 2)
            assert(updates.isEmpty)
        }

        "with jitter between low and high thresholds" in {
            val regulator = new TestRegulator
            onProbe = () => regulator.measure((jitterLowerThreshold + jitterUpperThreshold) / 2)

            timer.advance(regulateInterval * 2)
            assert(updates == Nil)
        }

        "with jitter above high threshold" in {
            val regulator = new TestRegulator
            onProbe = () => regulator.measure(jitterUpperThreshold + 1)

            timer.advance(regulateInterval * 2)
            assert(updates == Nil)
        }
    }

    "Doesn't allow more than collectSamples to be pending" - {
        "in one interval" in {
            val regulator = new TestRegulator
            onProbe = () =>
                for _ <- 1 to collectSamples + 1 do
                    regulator.measure(collectInterval.toMillis)

            timer.advance(collectInterval)
            assert(probes == collectSamples)
        }

        "in multiple intervals" in {
            val regulator = new TestRegulator
            onProbe = () =>
                for _ <- 1 to collectSamples + 1 do
                    regulator.measure(collectInterval.toMillis)

            timer.advance(collectInterval * 3)
            assert(probes == collectSamples * 3)
        }
    }

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
