package kyo

import kyo.test.RunConfig
import kyo.test.TestResult
import kyo.test.runner.TestRunner
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext

// ── Fixture suites for no-assertion runner tests ──────────────────────────────────────────────

private object NoAssertionRunnerFixtures:

    // Suite whose config disables the no-assertion check suite-wide. One leaf asserts nothing.
    class NoAssertConfigOffSuite extends kyo.test.internal.TestBase[Any]:
        override def config: RunConfig = super.config.failOnNoAssertion(false)
        "no-assert-disabled" in { kyo.Sync.defer(42).map(_ => ()) }
    end NoAssertConfigOffSuite

    // Suite whose config leaves the check at the default (ON). One leaf asserts nothing.
    class NoAssertConfigOnSuite extends kyo.test.internal.TestBase[Any]:
        "no-assert-on" in { kyo.Sync.defer(42).map(_ => ()) }
    end NoAssertConfigOnSuite

    // Suite with a pendingUntilFixed leaf whose body asserts nothing. The flip order must be:
    // Passed -> Failed (no-assertion) -> Pending (pendingUntilFixed inversion).
    class PendingUntilFixedNoAssertSuite extends kyo.test.internal.TestBase[Any]:
        "pending-no-assert".pendingUntilFixed("reason") in { kyo.Sync.defer(1 + 1).map(_ => ()) }
    end PendingUntilFixedNoAssertSuite

end NoAssertionRunnerFixtures

/** Scalatest orchestrator that exercises the kyo-test framework end-to-end.
  *
  * Each test here instantiates a kyo-test suite class (defined in companion objects for reflection compatibility), runs it via the new
  * runner's `runToFuture`, and asserts the resulting `TestReport` has zero failures. This validates that the kyo-test DSL, assertion
  * macros, decorators, and runner all function correctly through the full execution pipeline.
  */
// ScalaTest bootstrap: this file orchestrates kyo-test suite execution via reflection; the runner-under-test cannot be its own harness.
class SelfTestsRunner extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = kyo.test.runner.TestExecutionContext.executionContext

    "TestApiSelfTest runs through kyo-test with 0 failures" in {
        TestRunner.runToFuture(classOf[kyo.TestApiSelfTest]).map { report =>
            assert(report.failed == 0, s"TestApiSelfTest had ${report.failed} failures; passed=${report.passed}")
            assert(report.passed > 0, s"TestApiSelfTest had no passing tests")
            succeed
        }
    }

    "RunnerSelfTest runs through kyo-test with 0 failures" in {
        TestRunner.runToFuture(classOf[kyo.RunnerSelfTest]).map { report =>
            assert(report.failed == 0, s"RunnerSelfTest had ${report.failed} failures; passed=${report.passed}")
            assert(report.passed > 0, s"RunnerSelfTest had no passing tests")
            succeed
        }
    }

    // ── Leaf-7: suite-level config override disables no-assertion check suite-wide ───────────

    "leaf-7: suite-level failOnNoAssertion(false) disables the check: a no-assert leaf stays Passed" in {
        TestRunner.runToFuture(classOf[NoAssertionRunnerFixtures.NoAssertConfigOffSuite]).map { report =>
            assert(report.totalLeaves == 1, s"expected 1 leaf, got ${report.totalLeaves}")
            assert(report.passed == 1, s"expected 1 passed (check disabled suite-wide), got failed=${report.failed}, report=$report")
            assert(report.failed == 0, s"expected 0 failures, got ${report.failed}")
            succeed
        }
    }

    "leaf-7b: default config leaves the check ON: a no-assert leaf in a default suite flips to Failed" in {
        TestRunner.runToFuture(classOf[NoAssertionRunnerFixtures.NoAssertConfigOnSuite]).map { report =>
            assert(report.totalLeaves == 1, s"expected 1 leaf, got ${report.totalLeaves}")
            assert(report.failed == 1, s"expected 1 failure (no-assertion check on), got failed=${report.failed}, report=$report")
            succeed
        }
    }

    // ── Leaf-15: pendingUntilFixed + no-assertion flip ordering ───────────────────────────────

    "leaf-15: pendingUntilFixed + no-assertion: Passed -> Failed -> Pending (correct ordering)" in {
        TestRunner.runToFuture(classOf[NoAssertionRunnerFixtures.PendingUntilFixedNoAssertSuite]).map { report =>
            assert(report.totalLeaves == 1, s"expected 1 leaf, got ${report.totalLeaves}")
            val leafResult = report.suiteReports.head.leafResults.head._2
            leafResult match
                case TestResult.Pending(reason) =>
                    assert(reason == "reason", s"expected reason='reason', got: $reason")
                    succeed
                case other =>
                    fail(s"Expected Pending('reason') (no-assertion flip first, then pendingUntilFixed inversion), got $other")
            end match
        }
    }

end SelfTestsRunner
