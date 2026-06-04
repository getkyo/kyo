package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.internal.Platform
import kyo.test.RunConfig
import kyo.test.TestFilter
import kyo.test.TestReport
import kyo.test.TestResult
import kyo.test.Verbosity
import kyo.test.internal.TestBase
import kyo.test.runner.ConsoleReporter
import kyo.test.runner.TestExecutionContext
import kyo.test.runner.TestRunner
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ── Fixture suites (defined in companion object so classOf[...] works via reflection). They extend
//    TestBase[Any] directly so sbt does NOT auto-discover them as real suites. ─────────────────

private object RunnerSelfFixtures:

    class SingleSyncSuite extends TestBase[Any]:
        "a" in succeed
    end SingleSyncSuite

    class MultiAsyncSuite extends TestBase[Any]:
        "x" in Async.sleep(1.millis).andThen(succeed)
        "y" in Async.sleep(1.millis).andThen(assert(2 + 2 == 4))
    end MultiAsyncSuite

    // Static counter for parallelism test: tracks concurrent active leaves.
    val parallelActive: AtomicInteger  = new AtomicInteger(0)
    val parallelMaxSeen: AtomicInteger = new AtomicInteger(0)

    class ParallelSuite extends TestBase[Any]:
        private def track(using kyo.test.AssertScope): Unit < (Async & Abort[Throwable] & Scope) =
            Sync.defer {
                val now = parallelActive.incrementAndGet()
                var cur = parallelMaxSeen.get()
                while now > cur && !parallelMaxSeen.compareAndSet(cur, now) do cur = parallelMaxSeen.get()
            }.andThen(Async.sleep(30.millis)).andThen(Sync.defer {
                parallelActive.decrementAndGet(): Unit
            }).andThen(succeed)
        for i <- 0 until 8 yield s"leaf$i" in track
    end ParallelSuite

    class FilterSuite extends TestBase[Any]:
        "a" in succeed
        "b" in succeed
    end FilterSuite

    class TimeoutSuite extends TestBase[Any]:
        "slow".timeout(10L.millis) in Async.sleep(500.millis).andThen(succeed)
    end TimeoutSuite

    // Static counters for the retry fixtures, must be reset before each test run.
    val retryCounter: AtomicInteger     = new AtomicInteger(0)
    val retryFailCounter: AtomicInteger = new AtomicInteger(0)

    class RetrySuite extends TestBase[Any]:
        // retry(2) yields 3 attempts; the first two fail by THROWING (assert(false), the normal assert-failure
        // path that surfaces as a Result.Panic), the third passes. This genuinely exercises retry-on-throw: if
        // the runner only retried Abort.fail this leaf would fail on the first thrown attempt.
        "flaky".retry(2) in Sync.defer(retryCounter.incrementAndGet()).map { n =>
            if n < 3 then assert(n >= 3, s"attempt $n failed (forced)")
            else succeed
        }
    end RetrySuite

    class RetryAlwaysFailsSuite extends TestBase[Any]:
        // retry(2) yields 3 attempts; every attempt THROWS. After retries are exhausted the leaf ends Failed,
        // and the counter records exactly 3 attempts (initial + 2 retries).
        "always-fails".retry(2) in Sync.defer(retryFailCounter.incrementAndGet()).map { n =>
            assert(false, s"attempt $n always fails")
        }
    end RetryAlwaysFailsSuite

    class ConsoleSuite extends TestBase[Any]:
        "greeting" in succeed
    end ConsoleSuite

    // ── Cross-suite global-bound fixtures ───────────────────────────────────────────────────────
    // XSuiteA and XSuiteB share ONE pair of process-global in-flight/peak counters, mirroring
    // RTConcurrencySuite's prior art (java.util.concurrent.atomic.AtomicInteger statics in a companion;
    // acceptable in test-fixture code). Run concurrently through the global pool, the COMBINED in-flight
    // count of both suites' leaves never exceeds globalK: each pool worker holds its leaf until the body
    // completes, so at most globalK leaves are in-flight across ALL suites at once. That is the bound the
    // cross-suite test asserts deterministically.
    object XSuiteCounters:
        val inFlight: AtomicInteger = new AtomicInteger(0)
        val peak: AtomicInteger     = new AtomicInteger(0)
        def updatePeak(n: Int): Unit =
            var cur = peak.get()
            while n > cur && !peak.compareAndSet(cur, n) do cur = peak.get()
        def reset(): Unit =
            inFlight.set(0)
            peak.set(0)
    end XSuiteCounters

    // Each leaf raises the shared in-flight count, records the peak, yields once across an async boundary
    // (fork-and-join a trivial fiber, NOT a sleep and NOT a cross-leaf barrier so the two suites flooding the
    // shared global pool cannot deadlock under cross-suite worker competition), then lowers the count.
    private def xWindow: Unit < (Async & Abort[Throwable] & Scope) =
        Sync.defer {
            val n = XSuiteCounters.inFlight.incrementAndGet()
            XSuiteCounters.updatePeak(n)
        }.andThen(Fiber.initUnscoped(()).map(_.get)).andThen(Sync.defer {
            XSuiteCounters.inFlight.decrementAndGet(): Unit
        })

    class XSuiteA extends TestBase[Any]:
        private def track(using kyo.test.AssertScope): Unit < (Async & Abort[Throwable] & Scope) =
            xWindow.andThen(succeed)
        "a-leaf-0" in track
        "a-leaf-1" in track
        "a-leaf-2" in track
        "a-leaf-3" in track
        "a-leaf-4" in track
        "a-leaf-5" in track
    end XSuiteA

    class XSuiteB extends TestBase[Any]:
        private def track(using kyo.test.AssertScope): Unit < (Async & Abort[Throwable] & Scope) =
            xWindow.andThen(succeed)
        "b-leaf-0" in track
        "b-leaf-1" in track
        "b-leaf-2" in track
        "b-leaf-3" in track
        "b-leaf-4" in track
        "b-leaf-5" in track
    end XSuiteB

end RunnerSelfFixtures

/** Self-tests for kyo-test-runner behaviors, exercised by driving the NEW runner's pure-Kyo `runReport` surface.
  *
  * Raw ScalaTest (`AsyncFreeSpec with NonImplicitAssertions`), mirroring `RunnerTest`: each test body runs OFF the
  * process-global LeafPool and discharges its `runReport` computation to a `Future` via the same single sbt-edge
  * conversion the runner uses (`Scope.run(...).handle(Fiber.initUnscoped).map(_.toFuture)`). Running off-pool is
  * required: a `kyo.test.Test` body would itself occupy a pool worker, so awaiting `runReport` (which submits the
  * sub-suite's leaves to the SAME global pool) from that worker re-enters the pool and deadlocks when workers are
  * exhausted (always on Native globalK=1; under load on JVM). Off-pool, the awaiting body holds no worker, the
  * sub-suite's leaves get all globalK workers, and there is no re-entrancy on any platform.
  *
  * Each test exercises a runner-level behavior (`TestRunner.runReport`, filters, decorators, the cross-suite global
  * pool bound) and asserts on the returned report as a plain value.
  */
class RunnerSelfTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = TestExecutionContext.executionContext

    /** Discharge a runner computation to a `Future`, off the global pool. The single sbt-edge conversion the runner
      * itself uses: handle `Scope`, fork the computation onto a fresh (non-pool) fiber, and convert to a `Future`.
      * The body of every test below runs through this, so no test body is ever a pool worker.
      */
    private def discharge[A](comp: A < (Async & Abort[Throwable] & Scope))(using Frame): Future[A] =
        val asFuture: Future[A] < Sync =
            Scope.run(comp).handle(Fiber.initUnscoped).map(_.toFuture)
        // Unsafe: the test-edge boundary, identical to TestRunner.runToFuture's sbt-edge bridge. Discharging the
        // terminal Sync to the produced Future is the single sanctioned conversion; everything upstream is pure Kyo.
        import kyo.AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(asFuture)
    end discharge

    "runs single sync test via TestRunner.runReport" in {
        discharge(TestRunner.runReport(classOf[RunnerSelfFixtures.SingleSyncSuite])).map { report =>
            assert(report.totalLeaves == 1)
            assert(report.passed == 1)
            assert(report.failed == 0)
        }
    }

    "runs multiple async tests via TestRunner.runReport" in {
        discharge(TestRunner.runReport(classOf[RunnerSelfFixtures.MultiAsyncSuite])).map { report =>
            assert(report.totalLeaves == 2)
            assert(report.passed == 2)
            assert(report.failed == 0)
        }
    }

    "parallelism enabled: concurrent execution" in {
        // Reset static counters before this run
        RunnerSelfFixtures.parallelActive.set(0)
        RunnerSelfFixtures.parallelMaxSeen.set(0)
        val config = RunConfig.default.copy(parallelism = 4)
        discharge(TestRunner.runReport(classOf[RunnerSelfFixtures.ParallelSuite], config)).map { report =>
            assert(report.totalLeaves == 8)
            assert(report.passed == 8)
            // On JVM: concurrency is real; assert max-concurrent > 1. The body runs off-pool, so runReport's 8
            // leaves run on the global pool's workers concurrently and the shared counter observes the overlap.
            // On JS/Native: shared static counter is unreliable when multiple test suites run concurrently;
            // just verify all leaves completed.
            // deliberate per-platform: assertion is structural-only on non-JVM targets
            if Platform.isJVM then
                assert(
                    RunnerSelfFixtures.parallelMaxSeen.get() > 1,
                    s"expected concurrent execution but max seen was ${RunnerSelfFixtures.parallelMaxSeen.get()}"
                )
            else succeed
            end if
        }
    }

    "filter pathInclude limits execution" in {
        val config = RunConfig.default.copy(filter = TestFilter(pathInclude = kyo.Chunk("a")))
        discharge(TestRunner.runReport(classOf[RunnerSelfFixtures.FilterSuite], config)).map { report =>
            assert(report.totalLeaves == 1)
            val path = report.suiteReports.head.leafResults.head._1
            assert(path == Chunk("a"))
        }
    }

    "timeout decorator works end-to-end" in {
        // Timeout tests need real OS-level thread scheduling to race body against timer.
        // On JS (single-threaded event loop) the body runs to completion before the timer fires.
        if Platform.isJVM then
            discharge(TestRunner.runReport(classOf[RunnerSelfFixtures.TimeoutSuite])).map { report =>
                assert(report.totalLeaves == 1)
                val result = report.suiteReports.head.leafResults.head._2
                assert(result.isInstanceOf[TestResult.TimedOut], s"expected TimedOut but got $result")
            }
        else
            // The JVM-only timeout race is exercised above. On JS/Native the dedicated leaf
            // "runner self-test JS/Native: timeout suite runs one leaf without racing the timer"
            // covers the structural case; this leaf has nothing JVM-specific to verify there.
            Future.successful(succeed)
    }

    // deliberate per-platform: on JS/Native the event loop is single-threaded so the timeout timer cannot
    // fire while the body is executing; the body completes and the suite reports a non-TimedOut result.
    // This structural difference from JVM is verified here to confirm the suite still runs exactly one leaf.
    "runner self-test JS/Native: timeout suite runs one leaf without racing the timer" in {
        discharge(TestRunner.runReport(classOf[RunnerSelfFixtures.TimeoutSuite])).map { report =>
            assert(report.totalLeaves == 1)
        }
    }

    "retry decorator retries a leaf that fails by THROWING and ends passed" in {
        // Reset the retry counter before running so earlier runs don't affect this one
        RunnerSelfFixtures.retryCounter.set(0)
        discharge(TestRunner.runReport(classOf[RunnerSelfFixtures.RetrySuite])).map { report =>
            assert(report.totalLeaves == 1)
            assert(report.passed == 1, s"expected 1 passed but got: ${report}")
            // retry(2) = 3 attempts; the first two throw, the third passes. Confirms retry-on-throw fired.
            assert(
                RunnerSelfFixtures.retryCounter.get() == 3,
                s"expected 3 attempts (throw, throw, pass) but counter was ${RunnerSelfFixtures.retryCounter.get()}"
            )
        }
    }

    "retry decorator exhausts retries when a leaf THROWS on every attempt and ends failed" in {
        RunnerSelfFixtures.retryFailCounter.set(0)
        discharge(TestRunner.runReport(classOf[RunnerSelfFixtures.RetryAlwaysFailsSuite])).map { report =>
            assert(report.totalLeaves == 1)
            assert(report.failed == 1, s"expected 1 failed but got: ${report}")
            // retry(2) = 3 attempts; all throw, so the leaf fails after exactly 3 attempts.
            assert(
                RunnerSelfFixtures.retryFailCounter.get() == 3,
                s"expected 3 attempts before failing but counter was ${RunnerSelfFixtures.retryFailCounter.get()}"
            )
        }
    }

    "cross-suite execution is bounded by the process-global pool" in {
        // The core INV-002 cross-suite guard. Two suites (6 leaves each, 12 total, comfortably above globalK on a
        // typical multi-core JVM) run CONCURRENTLY through the SAME global pool over ONE shared in-flight/peak
        // counter. The deterministic assertions are the global bound (combined peak <= globalK) and completeness
        // (both reports hold all 6 leaves, all Passed, in input order). Real concurrency cannot be observed
        // deterministically through the shared global pool without a sleep or a barrier through the pool (which
        // deadlocks under cross-suite competition), so peak > 1 is intentionally NOT asserted here; LeafPoolTest
        // carries the deterministic peak == k proof. globalK is computed in-test from the public formula, no reflection.
        val globalK = if Platform.isNative then 1 else math.max(1, Async.defaultConcurrency)
        RunnerSelfFixtures.XSuiteCounters.reset()
        val cfg = RunConfig.default.copy(parallelism = 4)
        discharge(
            Async.zip(
                TestRunner.runReport(classOf[RunnerSelfFixtures.XSuiteA], cfg),
                TestRunner.runReport(classOf[RunnerSelfFixtures.XSuiteB], cfg)
            )
        ).map { case (reportA, reportB) =>
            val pathsA = reportA.suiteReports.flatMap(_.leafResults.map(_._1))
            val pathsB = reportB.suiteReports.flatMap(_.leafResults.map(_._1))
            val passedA = reportA.suiteReports.forall(_.leafResults.forall {
                case (_, _: TestResult.Passed) => true
                case _                         => false
            })
            val passedB = reportB.suiteReports.forall(_.leafResults.forall {
                case (_, _: TestResult.Passed) => true
                case _                         => false
            })
            // Completeness: every leaf of both suites ran and passed.
            assert(reportA.totalLeaves == 6, s"expected 6 leaves in XSuiteA, got $reportA")
            assert(reportB.totalLeaves == 6, s"expected 6 leaves in XSuiteB, got $reportB")
            assert(passedA, s"expected all XSuiteA leaves Passed, got $reportA")
            assert(passedB, s"expected all XSuiteB leaves Passed, got $reportB")
            // Ordering: each suite's report sequence equals its input registration order.
            assert(
                pathsA == Chunk(
                    Chunk("a-leaf-0"),
                    Chunk("a-leaf-1"),
                    Chunk("a-leaf-2"),
                    Chunk("a-leaf-3"),
                    Chunk("a-leaf-4"),
                    Chunk("a-leaf-5")
                ),
                s"expected XSuiteA leaves in input order, got $pathsA"
            )
            assert(
                pathsB == Chunk(
                    Chunk("b-leaf-0"),
                    Chunk("b-leaf-1"),
                    Chunk("b-leaf-2"),
                    Chunk("b-leaf-3"),
                    Chunk("b-leaf-4"),
                    Chunk("b-leaf-5")
                ),
                s"expected XSuiteB leaves in input order, got $pathsB"
            )
            // Global bound (deterministic): the combined peak never exceeds globalK.
            assert(
                RunnerSelfFixtures.XSuiteCounters.peak.get() <= globalK,
                s"expected combined cross-suite peak <= globalK=$globalK, got ${RunnerSelfFixtures.XSuiteCounters.peak.get()}"
            )
        }
    }

    "console reporter output is readable" in {
        val bos    = new java.io.ByteArrayOutputStream
        val ps     = new java.io.PrintStream(bos)
        val rep    = new ConsoleReporter(Verbosity.Normal, useColors = false, out = ps)
        val config = RunConfig.default.copy(reporter = Maybe(rep))
        discharge(TestRunner.runReport(classOf[RunnerSelfFixtures.ConsoleSuite], config)).map { report =>
            assert(report.totalLeaves == 1)
            val output = bos.toString("UTF-8")
            assert(output.contains("[PASS]") || output.contains("passed"), s"reporter output was: $output")
        }
    }

end RunnerSelfTest
