package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.internal.Platform
import kyo.test.PlatformSet
import kyo.test.PlatformTestBuilder
import kyo.test.RunConfig
import kyo.test.TestBuilder
import kyo.test.TestFilter
import kyo.test.TestResult
import kyo.test.internal.TestBase
import kyo.test.runner.TestExecutionContext
import kyo.test.runner.TestRunner
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ── Fixture suites (private object so classOf[...] resolves via reflection; extend TestBase[Any]
//    directly so sbt does NOT auto-discover them as real suites). ────────────────────────────────

private object LiveCoverageFixtures:

    // ── 1. repeat ─────────────────────────────────────────────────────────────────────────────

    val repeatCounter: AtomicInteger = new AtomicInteger(0)

    class RepeatSuite extends TestBase[Any]:
        "r".times(3) in Sync.defer(repeatCounter.incrementAndGet()).andThen(succeed)
    end RepeatSuite

    val rfCounter: AtomicInteger = new AtomicInteger(0)

    class RepeatFailFastSuite extends TestBase[Any]:
        "rf".times(5) in Sync.defer(rfCounter.incrementAndGet()).map(n => assert(n != 3))
    end RepeatFailFastSuite

    // ── 2. focus ───────────────────────────────────────────────────────────────────────────────

    class FocusSuite extends TestBase[Any]:
        "f".focus in succeed
        "p1" in succeed
        "p2" in succeed
    end FocusSuite

    // ── 3. tag filtering ──────────────────────────────────────────────────────────────────────

    class TagSuite extends TestBase[Any]:
        "a".tagged("slow") in succeed
        "b" in succeed
    end TagSuite

    // ── 4. platform filter ────────────────────────────────────────────────────────────────────

    class PlatformSuite extends TestBase[Any]:
        "js-only".js in succeed
    end PlatformSuite

    // ── 4b. onlyX platform filter ─────────────────────────────────────────────────────────────

    class OnlyPlatformSuite extends TestBase[Any]:
        "only-js".onlyJs in succeed
    end OnlyPlatformSuite

    // ── 4c. platform filter carries decorators forward ────────────────────────────────────────
    // The DSL (`.onlyJvm`, `.tagged`) is in scope only inside a TestBase body, so the builder-metadata
    // assertion lives in this fixture leaf (where the chained decorator actually executes). The leaf
    // passes iff the PlatformTestBuilder preserves the underlying builder's tags and name; a regression
    // makes the leaf fail, surfacing as report.failed == 1 in the off-pool caller below.

    class ChainedDecoratorSuite extends TestBase[Any]:
        "chained-decorator-preserves-metadata" in Sync.defer {
            val taggedBuilder: PlatformTestBuilder[PlatformSet.OnlyJvm] = "x".onlyJvm.tagged("slow")
            (taggedBuilder.builder.tags, taggedBuilder.builder.name)
        }.map { case (tags, name) =>
            assert(tags == Set("slow"))
            assert(name == "x")
        }
    end ChainedDecoratorSuite

    // ── 5. flaky ──────────────────────────────────────────────────────────────────────────────

    val flakyCounter: AtomicInteger = new AtomicInteger(0)

    class FlakySuite extends TestBase[Any]:
        // .flaky = 3 retries with linear 100ms backoff = up to 4 total attempts
        "fl".flaky in Sync.defer(flakyCounter.incrementAndGet()).map { n =>
            if n < 4 then assert(false, s"attempt $n")
            else succeed
        }
    end FlakySuite

    // ── 5b. flaky builder metadata ────────────────────────────────────────────────────────────
    // As with the chained-decorator fixture, the `.flaky` String DSL is only in scope inside a TestBase
    // body, so the builder-metadata assertion lives in this fixture leaf.

    class FlakyMetadataSuite extends TestBase[Any]:
        "flaky-builder-metadata" in Sync.defer {
            val builder: TestBuilder = "x".flaky
            (builder.tags.contains("flaky"), builder.retrySchedule.isDefined)
        }.map { case (hasFlakyTag, hasRetrySchedule) =>
            assert(hasFlakyTag)
            assert(hasRetrySchedule)
        }
    end FlakyMetadataSuite

    // ── 6. handle ─────────────────────────────────────────────────────────────────────────────

    class HandleSuite extends TestBase[Any]:
        "uses-env".handle[Env[Int]](
            [A] => (b: A < (Env[Int] & Async & Abort[Any] & Scope)) => Env.run(42)(b)
        ) in Env.get[Int].map(v => assert(v == 42))
    end HandleSuite

    // ── 7. typeCheck suite macros ─────────────────────────────────────────────────────────────

    class TypeCheckSuite extends TestBase[Any]:
        "tc-fail" in typeCheckFailure("val x: Int = \"s\"")("""Required: Int""")
        "tc-ok" in typeCheck("val x: Int = 5")
    end TypeCheckSuite

    // ── 8. pendingUntilFixed ──────────────────────────────────────────────────────────────────

    class PufStillFailingSuite extends TestBase[Any]:
        "p".pendingUntilFixed("known") in assert(1 == 2)
    end PufStillFailingSuite

    class PufNowPassingSuite extends TestBase[Any]:
        "p".pendingUntilFixed("known") in assert(1 == 1)
    end PufNowPassingSuite

    // ── 9. assertEventually ───────────────────────────────────────────────────────────────────

    val evCounter: AtomicInteger = new AtomicInteger(0)

    class EventuallySuite extends TestBase[Any]:
        "ev" in assertEventually(Sync.defer(evCounter.incrementAndGet() >= 3))
    end EventuallySuite

    class EventuallyTimeoutSuite extends TestBase[Any]:
        "ev-to".timeout(200.millis) in assertEventually(Sync.defer(false))
    end EventuallyTimeoutSuite

    // ── 10. ignore ────────────────────────────────────────────────────────────────────────────

    val ignoreCounter: AtomicInteger = new AtomicInteger(0)

    class IgnoreSuite extends TestBase[Any]:
        "ig".ignore in Sync.defer(ignoreCounter.incrementAndGet()).andThen(succeed)
    end IgnoreSuite

    // ── 11. plain pending (non-xfail) ────────────────────────────────────────────────────────

    val pendingCounter: AtomicInteger = new AtomicInteger(0)

    class PendingSuite extends TestBase[Any]:
        "pd".pending("known reason") in Sync.defer(pendingCounter.incrementAndGet()).andThen(succeed)
    end PendingSuite

    // ── 12. only(false) ──────────────────────────────────────────────────────────────────────

    val onlyCounter: AtomicInteger = new AtomicInteger(0)

    class OnlyFalseSuite extends TestBase[Any]:
        "oc".only(false) in Sync.defer(onlyCounter.incrementAndGet()).andThen(succeed)
    end OnlyFalseSuite

    // ── 13. retry + repeat combined ──────────────────────────────────────────────────────────
    // retry(1).times(2): 2 outer repeat iterations; each iteration fails on the first attempt
    // then passes on the retry. Body called 2 * (1+1) = 4 times total.

    val retryRepeatCounter: AtomicInteger = new AtomicInteger(0)

    class RetryRepeatSuite extends TestBase[Any]:
        "rr".retry(1).times(2) in Sync.defer {
            val n = retryRepeatCounter.incrementAndGet()
            // fail on attempts 1 and 3 (first attempt of each outer iteration), pass on 2 and 4
            if n % 2 == 1 then assert(false, s"attempt $n: expected to fail on first try")
            else succeed
        }
    end RetryRepeatSuite

end LiveCoverageFixtures

/** Live-path execution tests for kyo-test-runner behaviors.
  *
  * Raw ScalaTest (`AsyncFreeSpec with NonImplicitAssertions`), mirroring `RunnerSelfTest`/`LeafPoolTest`: each test
  * body runs OFF the process-global LeafPool and discharges its `runReport` computation to a `Future` via the same
  * single sbt-edge conversion the runner uses (`Scope.run(...).handle(Fiber.initUnscoped).map(_.toFuture)`). Running
  * off-pool is required: a `kyo.test.Test` body would itself occupy a pool worker, so awaiting `runReport` (which
  * submits the sub-suite's leaves to the SAME global pool) from that worker re-enters the pool and deadlocks when
  * workers are exhausted (always on Native globalK=1; under load on JVM). Off-pool, the awaiting body holds no worker,
  * the sub-suite's leaves get all globalK workers, and there is no re-entrancy on any platform.
  *
  * Each leaf exercises a runner feature through `TestRunner.runReport` and asserts on the produced `TestReport`, NOT
  * on builder metadata. The two builder-metadata checks (chained-decorator preservation, flaky metadata) run inside
  * `TestBase` fixtures (where the String DSL is in scope) and are surfaced here as their report's pass/fail.
  */
class LiveCoverageTest extends AsyncFreeSpec with NonImplicitAssertions:

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

    // ── 1a. repeat: body runs N times ────────────────────────────────────────────────────────

    "repeat: body runs exactly N times and passes" in {
        LiveCoverageFixtures.repeatCounter.set(0)
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.RepeatSuite])).map { report =>
            assert(report.passed == 1)
            assert(
                LiveCoverageFixtures.repeatCounter.get() == 3,
                s"expected repeatCounter == 3 but got ${LiveCoverageFixtures.repeatCounter.get()}"
            )
        }
    }

    // ── 1b. repeat: fail-fast on failing iteration ────────────────────────────────────────────

    "repeat: fail-fast stops at the failing iteration" in {
        LiveCoverageFixtures.rfCounter.set(0)
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.RepeatFailFastSuite])).map { report =>
            assert(report.failed == 1)
            assert(
                LiveCoverageFixtures.rfCounter.get() == 3,
                s"expected rfCounter == 3 (fail on iteration 3) but got ${LiveCoverageFixtures.rfCounter.get()}"
            )
        }
    }

    // ── 2. focus ──────────────────────────────────────────────────────────────────────────────

    "focus: focused leaf passes; plain leaves are skipped" in {
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.FocusSuite])).map { report =>
            assert(report.passed == 1)
            assert(report.skipped == 2)
        }
    }

    // ── 3. tag filtering ─────────────────────────────────────────────────────────────────────
    // applyFilter removes leaves whose tags are in tagsExclude from the ordered list entirely
    // (they do not appear in the final report at all). So totalLeaves == 1, passed == 1.

    "tag filter: tagsExclude removes matching leaves from the report" in {
        val config = RunConfig.default.copy(filter = TestFilter(tagsExclude = Set("slow")))
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.TagSuite], config)).map { report =>
            assert(report.totalLeaves == 1)
            assert(report.passed == 1)
        }
    }

    // ── 4. platform filter ────────────────────────────────────────────────────────────────────
    // `.js` is a compile-time gate (PlatformSet.OnlyJs): on a disabled platform `gateOf[P]` is the
    // literal `false`, so the leaf body is NOT emitted and the leaf is ABSENT (not skipped). On JVM
    // and Native the suite therefore registers zero leaves; on JS the leaf is present and passes.
    // This is the new compile-time-exclusion behavior that replaced the old runtime registerSkipped.

    "platform filter: js-only leaf is compile-excluded off JS" in {
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.PlatformSuite])).map { report =>
            if Platform.isJS then
                assert(report.totalLeaves == 1, s"JS: expected totalLeaves==1 but got $report")
                assert(report.passed == 1, s"JS: expected passed==1 but got $report")
            else
                // Off JS the leaf body is not emitted: the suite has no leaves at all.
                assert(report.totalLeaves == 0, s"non-JS: expected totalLeaves==0 (leaf absent) but got $report")
                assert(report.skipped == 0, s"non-JS: expected skipped==0 (leaf absent, not skipped) but got $report")
        }
    }

    // ── 4b. onlyX platform filter: onlyJs leaf is compile-excluded off JS ─────────────────────

    "onlyX platform filter: onlyJs leaf is compile-excluded off JS" in {
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.OnlyPlatformSuite])).map { report =>
            if Platform.isJS then
                assert(report.totalLeaves == 1, s"JS: expected totalLeaves==1 but got $report")
                assert(report.passed == 1, s"JS: expected passed==1 but got $report")
            else
                assert(report.totalLeaves == 0, s"non-JS: expected totalLeaves==0 (leaf absent) but got $report")
                assert(report.skipped == 0, s"non-JS: expected skipped==0 (leaf absent, not skipped) but got $report")
        }
    }

    // ── 4c. platform filter carries decorators forward ────────────────────────────────────────
    // A decorator chained after the filter (`.onlyJvm.tagged(...)`) produces a PlatformTestBuilder
    // whose phantom P is preserved, so the underlying TestBuilder metadata still flows to the gate.
    // The metadata assertion runs inside ChainedDecoratorSuite (the DSL is only in scope in a TestBase
    // body); a regression there makes that leaf fail, which surfaces here as report.failed == 1.

    "platform filter: chained decorator preserves builder metadata" in {
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.ChainedDecoratorSuite])).map { report =>
            assert(report.passed == 1, s"expected the chained-decorator metadata leaf to pass but got $report")
            assert(report.failed == 0, s"expected no failed leaves but got $report")
        }
    }

    // ── 5a. flaky: retries until passing ─────────────────────────────────────────────────────

    "flaky: retries up to 4 attempts then passes" in {
        LiveCoverageFixtures.flakyCounter.set(0)
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.FlakySuite])).map { report =>
            assert(report.passed == 1)
            assert(
                LiveCoverageFixtures.flakyCounter.get() == 4,
                s"expected flakyCounter == 4 (3 retries + 1 pass) but got ${LiveCoverageFixtures.flakyCounter.get()}"
            )
        }
    }

    // ── 5b. flaky builder metadata ────────────────────────────────────────────────────────────
    // The `.flaky` builder-metadata assertion runs inside FlakyMetadataSuite (the DSL is only in scope
    // in a TestBase body); a regression there makes that leaf fail, surfacing here as report.failed == 1.

    "flaky: builder has flaky tag and retrySchedule Present" in {
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.FlakyMetadataSuite])).map { report =>
            assert(report.passed == 1, s"expected the flaky metadata leaf to pass but got $report")
            assert(report.failed == 0, s"expected no failed leaves but got $report")
        }
    }

    // ── 6. handle ─────────────────────────────────────────────────────────────────────────────

    "handle: leaf discharging Env[Int] via .handle runs to Passed" in {
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.HandleSuite])).map { report =>
            assert(report.passed == 1)
        }
    }

    // ── 7. typeCheck suite macros ─────────────────────────────────────────────────────────────

    "typeCheck/typeCheckFailure suite macros execute and pass" in {
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.TypeCheckSuite])).map { report =>
            assert(report.passed == 2)
        }
    }

    // ── 8a. pendingUntilFixed: still-failing body reports Pending ─────────────────────────────

    "pendingUntilFixed: still-failing body reports Pending" in {
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.PufStillFailingSuite])).map { report =>
            assert(report.pending == 1)
        }
    }

    // ── 8b. pendingUntilFixed: now-passing body reports Failed with tripwire message ───────────

    "pendingUntilFixed: now-passing body reports Failed with remove-marker message" in {
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.PufNowPassingSuite])).map { report =>
            assert(report.failed == 1)
            val (_, result) = report.suiteReports.head.leafResults.head
            result match
                case TestResult.Failed(diagram, _, _, _) =>
                    assert(
                        diagram.contains("remove the pendingUntilFixed marker"),
                        s"expected diagram to contain 'remove the pendingUntilFixed marker' but got: $diagram"
                    )
                case other =>
                    assert(false, s"expected Failed but got $other")
            end match
        }
    }

    // ── 9a. assertEventually: polls until condition holds ─────────────────────────────────────

    "assertEventually: polls until condition holds and passes" in {
        LiveCoverageFixtures.evCounter.set(0)
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.EventuallySuite])).map { report =>
            assert(report.passed == 1)
        }
    }

    // ── 9b. assertEventually: times out when condition never holds ────────────────────────────

    "assertEventually: times out when condition never holds" in {
        // On JS/Native the timeout timer may not race the body in the same way as JVM, but the
        // assertEventually loop runs forever with a 10ms poll; even on JS the timeout fires and
        // the test framework surfaces a TimedOut result.
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.EventuallyTimeoutSuite])).map { report =>
            assert(report.timedOut == 1, s"expected timedOut==1 but got $report")
        }
    }

    // ── 10. ignore: body does NOT run; leaf reports Ignored ──────────────────────────────────

    "ignore: leaf reports Ignored and body does not run" in {
        LiveCoverageFixtures.ignoreCounter.set(0)
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.IgnoreSuite])).map { report =>
            assert(report.ignored == 1, s"expected ignored==1 but got $report")
            assert(
                LiveCoverageFixtures.ignoreCounter.get() == 0,
                s"expected ignoreCounter == 0 (body must NOT run) but got ${LiveCoverageFixtures.ignoreCounter.get()}"
            )
        }
    }

    // ── 11. plain pending: body does NOT run; leaf reports Pending ───────────────────────────

    "pending: leaf reports Pending and body does not run" in {
        LiveCoverageFixtures.pendingCounter.set(0)
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.PendingSuite])).map { report =>
            assert(report.pending == 1, s"expected pending==1 but got $report")
            assert(
                LiveCoverageFixtures.pendingCounter.get() == 0,
                s"expected pendingCounter == 0 (body must NOT run) but got ${LiveCoverageFixtures.pendingCounter.get()}"
            )
        }
    }

    // ── 12. only(false): body does NOT run; leaf reports Skipped ─────────────────────────────

    "only(false): leaf reports Skipped and body does not run" in {
        LiveCoverageFixtures.onlyCounter.set(0)
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.OnlyFalseSuite])).map { report =>
            assert(report.skipped == 1, s"expected skipped==1 but got $report")
            assert(
                LiveCoverageFixtures.onlyCounter.get() == 0,
                s"expected onlyCounter == 0 (body must NOT run) but got ${LiveCoverageFixtures.onlyCounter.get()}"
            )
        }
    }

    // ── 13. retry + repeat combined ──────────────────────────────────────────────────────────

    "retry+repeat: retry(1).times(2) with first-attempt-fails body runs 4 times total and passes" in {
        LiveCoverageFixtures.retryRepeatCounter.set(0)
        discharge(TestRunner.runReport(classOf[LiveCoverageFixtures.RetryRepeatSuite])).map { report =>
            assert(report.passed == 1, s"expected passed==1 but got $report")
            assert(
                LiveCoverageFixtures.retryRepeatCounter.get() == 4,
                s"expected retryRepeatCounter == 4 (2 outer * 2 per retry) but got ${LiveCoverageFixtures.retryRepeatCounter.get()}"
            )
        }
    }

end LiveCoverageTest
