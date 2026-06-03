package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.internal.Platform
import kyo.test.RunConfig
import kyo.test.TestFilter
import kyo.test.TestResult
import kyo.test.Verbosity
import kyo.test.internal.TestBase
import kyo.test.runner.ConsoleReporter
import kyo.test.runner.TestRunner

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

end RunnerSelfFixtures

/** Self-tests for kyo-test-runner behaviors, exercised through the kyo-test framework itself.
  *
  * Each test case exercises a runner-level behavior (TestRunner.runReport, filters, decorators) via the NEW runner's pure-Kyo `runReport`
  * surface, returning the report as a Kyo value the leaf body asserts on directly.
  */
class RunnerSelfTest extends kyo.test.Test[Any]:

    "runs single sync test via TestRunner.runReport" in {
        TestRunner.runReport(classOf[RunnerSelfFixtures.SingleSyncSuite]).map { report =>
            assert(report.totalLeaves == 1)
            assert(report.passed == 1)
            assert(report.failed == 0)
        }
    }

    "runs multiple async tests via TestRunner.runReport" in {
        TestRunner.runReport(classOf[RunnerSelfFixtures.MultiAsyncSuite]).map { report =>
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
        TestRunner.runReport(classOf[RunnerSelfFixtures.ParallelSuite], config).map { report =>
            assert(report.totalLeaves == 8)
            assert(report.passed == 8)
            // On JVM: concurrency is real; assert max-concurrent > 1.
            // On JS/Native: shared static counter is unreliable when multiple test suites run concurrently;
            // just verify all leaves completed.
            // deliberate per-platform: assertion is structural-only on non-JVM targets
            if Platform.isJVM then
                assert(
                    RunnerSelfFixtures.parallelMaxSeen.get() > 1,
                    s"expected concurrent execution but max seen was ${RunnerSelfFixtures.parallelMaxSeen.get()}"
                )
            end if
        }
    }

    "filter pathInclude limits execution" in {
        val config = RunConfig.default.copy(filter = TestFilter(pathInclude = kyo.Chunk("a")))
        TestRunner.runReport(classOf[RunnerSelfFixtures.FilterSuite], config).map { report =>
            assert(report.totalLeaves == 1)
            val path = report.suiteReports.head.leafResults.head._1
            assert(path == Chunk("a"))
        }
    }

    "timeout decorator works end-to-end" in {
        // Timeout tests need real OS-level thread scheduling to race body against timer.
        // On JS (single-threaded event loop) the body runs to completion before the timer fires.
        if Platform.isJVM then
            TestRunner.runReport(classOf[RunnerSelfFixtures.TimeoutSuite]).map { report =>
                assert(report.totalLeaves == 1)
                val result = report.suiteReports.head.leafResults.head._2
                assert(result.isInstanceOf[TestResult.TimedOut], s"expected TimedOut but got $result")
            }
        else
            // The JVM-only timeout race is exercised above. On JS/Native the dedicated leaf
            // "runner self-test JS/Native: timeout suite runs one leaf without racing the timer"
            // covers the structural case; this leaf has nothing JVM-specific to verify there.
            succeed
    }

    // deliberate per-platform: on JS/Native the event loop is single-threaded so the timeout timer cannot
    // fire while the body is executing; the body completes and the suite reports a non-TimedOut result.
    // This structural difference from JVM is verified here to confirm the suite still runs exactly one leaf.
    "runner self-test JS/Native: timeout suite runs one leaf without racing the timer" in {
        TestRunner.runReport(classOf[RunnerSelfFixtures.TimeoutSuite]).map { report =>
            assert(report.totalLeaves == 1)
        }
    }

    "retry decorator retries a leaf that fails by THROWING and ends passed" in {
        // Reset the retry counter before running so earlier runs don't affect this one
        RunnerSelfFixtures.retryCounter.set(0)
        TestRunner.runReport(classOf[RunnerSelfFixtures.RetrySuite]).map { report =>
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
        TestRunner.runReport(classOf[RunnerSelfFixtures.RetryAlwaysFailsSuite]).map { report =>
            assert(report.totalLeaves == 1)
            assert(report.failed == 1, s"expected 1 failed but got: ${report}")
            // retry(2) = 3 attempts; all throw, so the leaf fails after exactly 3 attempts.
            assert(
                RunnerSelfFixtures.retryFailCounter.get() == 3,
                s"expected 3 attempts before failing but counter was ${RunnerSelfFixtures.retryFailCounter.get()}"
            )
        }
    }

    "console reporter output is readable" in {
        val bos    = new java.io.ByteArrayOutputStream
        val ps     = new java.io.PrintStream(bos)
        val rep    = new ConsoleReporter(Verbosity.Normal, useColors = false, out = ps)
        val config = RunConfig.default.copy(reporter = Maybe(rep))
        TestRunner.runReport(classOf[RunnerSelfFixtures.ConsoleSuite], config).map { report =>
            assert(report.totalLeaves == 1)
            val output = bos.toString("UTF-8")
            assert(output.contains("[PASS]") || output.contains("passed"), s"reporter output was: $output")
        }
    }

end RunnerSelfTest
