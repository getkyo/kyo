package kyo.scheduler.regulator

import kyo.scheduler.TestTimer
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.concurrent.duration.*

class RegulatorTest extends AnyFreeSpec with NonImplicitAssertions {

    "load average" - {
        "below target" - {
            "when no jitter is present" in new Context {
                val regulator = new TestRegulator
                loadAvg = 0
                jitter = 0

                timer.advanceAndRun(regulateInterval * 2)
                assert(updates.isEmpty)

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 40)
                assert(updates.isEmpty)
            }

            "with jitter below low threshold" in new Context {
                val regulator = new TestRegulator
                loadAvg = 0
                jitter = jitterLowerThreshold - 1

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates.isEmpty)
            }

            "with jitter between low and high thresholds" in new Context {
                val regulator = new TestRegulator
                loadAvg = 0
                jitter = (jitterLowerThreshold + jitterUpperThreshold) / 2

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates.isEmpty)
            }

            "with jitter above high threshold" in new Context {
                val regulator = new TestRegulator
                loadAvg = 0
                jitter = jitterUpperThreshold + 1

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates.isEmpty)
            }
        }
        "above target" - {
            "when no jitter is present" in new Context {
                val regulator = new TestRegulator
                loadAvg = 0.9
                jitter = 0

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates == List(1, 2))
            }

            "with jitter below low threshold" in new Context {
                val regulator = new TestRegulator
                loadAvg = 0.9
                jitter = jitterLowerThreshold - 1

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates == List(1, 2))
            }

            "with jitter between low and high thresholds" in new Context {
                val regulator = new TestRegulator
                loadAvg = 0.9
                jitter = jitterUpperThreshold

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates.isEmpty)
            }

            "with jitter above high threshold" in new Context {
                val regulator = new TestRegulator
                loadAvg = 0.9
                jitter = jitterUpperThreshold * 10

                timer.advanceAndRun(regulateInterval * 2)
                assert(probes == 20)
                assert(updates == List(-1, -2))
            }
        }
    }

    "uses exponential steps" - {
        "up" in new Context {
            val regulator = new TestRegulator
            loadAvg = 0.9
            jitter = jitterLowerThreshold - 1

            timer.advanceAndRun(regulateInterval * 10)
            assert(probes == 100)
            assert(updates == (1 to 10).map(Math.pow(_, stepExp).intValue()))
        }

        "down" in new Context {
            val regulator = new TestRegulator
            loadAvg = 0.9
            jitter = jitterUpperThreshold * 10

            timer.advanceAndRun(regulateInterval * 10)
            assert(probes == 100)
            assert(updates == (1 to 10).map(-Math.pow(_, stepExp).intValue()))
        }

        "reset" in new Context {
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
    }

    "resilience" - {
        // Each step runs as a periodic task on a ScheduledExecutorService, which suppresses every later run once one throws: a single
        // failure would stop the regulator for the life of the scheduler. A probe schedules a task, which can reach the scheduler's
        // drains, and the drain recursion overflowed the stack on CI. These run on a real executor because TestTimer does not
        // reproduce the suppression.

        def withRealTimer[A](f: kyo.scheduler.InternalTimer => A): A = {
            val exec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
            try f(kyo.scheduler.InternalTimer(exec))
            finally exec.shutdownNow(): Unit
        }

        // Polls a count until it reaches `target`; the deadline only bails out a step that stopped running.
        def awaitCount(count: () => Int, target: Int, what: String): Unit = {
            val deadline = System.nanoTime() + 30L * 1000 * 1000 * 1000
            while (count() < target && System.nanoTime() < deadline) Thread.`yield`()
            val _ = assert(count() >= target, s"$what stopped after the error (${count()} runs)")
        }

        val cfg = Config(10, 1.millis, 1.millis, 200, 100, 0.8, 1.3)

        "a fatal error in one probe does not stop the probes after it" in withRealTimer { timer =>
            val thrown = new java.util.concurrent.atomic.AtomicBoolean(false)
            val probes = new java.util.concurrent.atomic.AtomicInteger(0)
            val _      = new Regulator(() => 0d, timer, cfg) {
                def probe(): Unit =
                    if (thrown.compareAndSet(false, true)) throw new StackOverflowError("injected")
                    else { val _ = probes.incrementAndGet() }
                def update(diff: Int): Unit = ()
            }
            awaitCount(() => probes.get(), 20, "probing")
        }

        "a fatal error in one adjustment does not stop the adjustments after it" in withRealTimer { timer =>
            // The adjustment reads the load average on every run, and nothing else here does.
            val thrown                                     = new java.util.concurrent.atomic.AtomicBoolean(false)
            val reads                                      = new java.util.concurrent.atomic.AtomicInteger(0)
            val loadAvg: java.util.function.DoubleSupplier = () =>
                if (thrown.compareAndSet(false, true)) throw new StackOverflowError("injected")
                else { val _ = reads.incrementAndGet(); 0d }
            val _ = new Regulator(loadAvg, timer, cfg) {
                def probe(): Unit           = ()
                def update(diff: Int): Unit = ()
            }
            awaitCount(() => reads.get(), 20, "adjusting")
        }
    }

    trait Context {

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
        var onUpdate: Int => Unit = (_ => {})
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
            ) {
            def probe(): Unit = {
                probes += 1
                val measurement =
                    if (probes % 2 == 0)
                        jitter + 1
                    else
                        1
                measure(measurement.toLong)
            }

            def update(diff: Int) = {
                updates :+= diff
                onUpdate(diff)
            }

            override def measure(v: Long) = {
                super.measure(v)
            }
        }
    }

}
