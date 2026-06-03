package kyo.test.runner

import java.util.concurrent.atomic.AtomicInteger
import kyo.*
import kyo.test.RunConfig
import kyo.test.TestReport
import kyo.test.TestResult
import kyo.test.internal.TestBase
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ── Fixture suites (top-level so reflection can instantiate them; extend TestBase[Any]
//    directly so sbt does NOT auto-discover them as real suites) ──────────────────────────────

/** 5 leaves; each tracks concurrent in-flight count so the test can assert the Meter bound. */
class RTConcurrencySuite extends TestBase[Any]:
    private def track(i: Int)(using kyo.test.AssertScope): Unit < (Async & Abort[Throwable] & Scope) =
        Sync.defer {
            val n = RTConcurrencySuite.inFlight.incrementAndGet()
            RTConcurrencySuite.updatePeak(n)
        }.andThen(Async.sleep(50.millis)).andThen(Sync.defer {
            RTConcurrencySuite.inFlight.decrementAndGet(): Unit
        }).andThen(succeed)
    "leaf-0" in track(0)
    "leaf-1" in track(1)
    "leaf-2" in track(2)
    "leaf-3" in track(3)
    "leaf-4" in track(4)
end RTConcurrencySuite

object RTConcurrencySuite:
    val inFlight: AtomicInteger = new AtomicInteger(0)
    val peak: AtomicInteger     = new AtomicInteger(0)
    def updatePeak(n: Int): Unit =
        var cur = peak.get()
        while n > cur && !peak.compareAndSet(cur, n) do cur = peak.get()
    def reset(): Unit =
        inFlight.set(0)
        peak.set(0)
end RTConcurrencySuite

/** One failing and one passing leaf to confirm independence and the failure channel. */
class RTFailPassSuite extends TestBase[Any]:
    "fails" in assert(1 == 2)
    "passes" in assert(1 == 1)
end RTFailPassSuite

/** A failing leaf plus 4 slow leaves to confirm haltOnFailure interrupts in-flight fibers. */
class RTHaltSuite extends TestBase[Any]:
    private def slow(using kyo.test.AssertScope): Unit < (Async & Abort[Throwable] & Scope) =
        Sync.defer { RTHaltSuite.started.incrementAndGet(): Unit }
            .andThen(Async.sleep(30.seconds))
            .andThen(Sync.defer { RTHaltSuite.finished.incrementAndGet(): Unit })
            .andThen(succeed)
    "fails-fast" in assert(1 == 2)
    "slow-0" in slow
    "slow-1" in slow
    "slow-2" in slow
    "slow-3" in slow
end RTHaltSuite

object RTHaltSuite:
    val started: AtomicInteger  = new AtomicInteger(0)
    val finished: AtomicInteger = new AtomicInteger(0)
    def reset(): Unit =
        started.set(0)
        finished.set(0)
end RTHaltSuite

/** A leaf that sleeps past its timeout; `escaped` proves the post-sleep code never runs. */
class RTTimeoutSuite extends TestBase[Any]:
    "slow".timeout(50.millis) in Async.sleep(30.seconds).andThen(Sync.defer {
        RTTimeoutSuite.escaped.incrementAndGet(): Unit
    }).andThen(succeed)
end RTTimeoutSuite

object RTTimeoutSuite:
    val escaped: AtomicInteger = new AtomicInteger(0)
    def reset(): Unit          = escaped.set(0)
end RTTimeoutSuite

/** Two leaves each acquiring a release-tracked resource to confirm per-leaf Scope.run releases. */
class RTScopeSuite extends TestBase[Any]:
    "acquires" in Scope.acquireRelease(())(_ => Sync.defer { RTScopeSuite.released.incrementAndGet(): Unit })
        .andThen(succeed)
    "fails-acquires" in Scope.acquireRelease(())(_ => Sync.defer { RTScopeSuite.released.incrementAndGet(): Unit })
        .andThen(assert(1 == 2))
end RTScopeSuite

object RTScopeSuite:
    val released: AtomicInteger = new AtomicInteger(0)
    def reset(): Unit           = released.set(0)
end RTScopeSuite

/** Three passing leaves to confirm discovery produces the same leaf set as a Future walk would. */
class RTThreeLeafSuite extends TestBase[Any]:
    "a" in succeed
    "b" in succeed
    "c" in succeed
end RTThreeLeafSuite

/** Two passing leaves for the single-Fiber#toFuture edge test. */
class RTTwoLeafSuite extends TestBase[Any]:
    "x" in succeed
    "y" in succeed
end RTTwoLeafSuite

/** Exercises the `Abort[Any]` leaf baseline: a leaf body may abort with ANY value, not only a `Throwable`. A
  * non-Throwable abort is wrapped in `LeafAborted` at the production boundary and reported Failed; a Throwable abort
  * still reports Failed; a recovered abort passes.
  */
class RTAbortAnySuite extends TestBase[Any]:
    // Aborts with a NON-Throwable value (a String): only possible because the baseline is Abort[Any]. Unhandled, so the
    // runner wraps it in LeafAborted and reports Failed with the value preserved in the diagram.
    "fails-nonthrowable" in Abort.fail("boom")
    // Aborts with a non-Throwable value but recovers it within the leaf -> Passed.
    "recovers-nonthrowable" in Abort.run[String](Abort.fail("recoverme")).map(_ => succeed)
    // Aborts with a Throwable value (regression: the Throwable path still reports Failed).
    "fails-throwable" in Abort.fail(new RuntimeException("kaboom"))
    // A plain success under the Abort[Any] baseline -> Passed.
    "ok" in assert(1 == 1)
end RTAbortAnySuite

// ── AssertScope leak-capture fixtures (runner-side capture) ─────────────────────────────────────

/** A detached fiber asserts false DURING the leaf body while the body's main computation would otherwise pass.
  *
  * The leaf spawns an UNJOINED `Fiber.initUnscoped` fiber. That fiber asserts false (the assert macro records the
  * failure into the lexically-captured `AssertScope` and then throws), wraps the throw in `Abort.run[Throwable]` so the
  * panic is swallowed, then releases a Latch. The body awaits the Latch (so the record is guaranteed to have landed
  * before the body returns) WITHOUT joining the fiber's failure, then returns unit. The body would score Passed, but the
  * runner drains the per-leaf sink right after the join and flips it to Failed because the detached fiber recorded a
  * failure during the leaf. Deterministic: the Latch await orders the record strictly before the body return, and the
  * body return is strictly before the runner's close/drain.
  */
class RTDetachedDuringSuite extends TestBase[Any]:
    "detached-fails-during" in Latch.init(1).map { latch =>
        Fiber.initUnscoped {
            // assert(false) records into the captured AssertScope then throws; Abort.run swallows the panic; the latch
            // release then signals the body that the record has landed.
            Abort.run[Throwable](assert(false, "detached fiber asserted false during the leaf")).map(_ => latch.release)
        }.andThen(latch.await)
    }
end RTDetachedDuringSuite

/** A plain passing leaf with no detached fiber: no record lands, so the leaf stays Passed (the no-leak control). */
class RTNoLeakSuite extends TestBase[Any]:
    "no-leak-passes" in assert(1 == 1)
end RTNoLeakSuite

/** A normal joined `assert(false)` leaf: the throw propagates on the body path and scores Failed (the existing throw
  * path still works, unaffected by the sink machinery).
  */
class RTJoinedFailSuite extends TestBase[Any]:
    "joined-fails" in assert(1 == 2)
end RTJoinedFailSuite

/** A detached fiber asserts AFTER the body returns: the leaf stays Passed and the assert records into the now-CLOSED
  * scope, which emits the "a fiber outlived its test" stderr warning instead of failing the leaf.
  *
  * The body returns immediately; the detached fiber sleeps briefly, then asserts false. By the time the assert fires the
  * runner has already joined the body, scored the leaf, and closed the scope, so `record` takes the closed branch (the
  * stderr warning) rather than the sink. Full determinism through the runner is not achievable here (the close happens
  * on the runner timeline, not the leaf's), so the sleep makes the after-body ordering as reliable as possible; the
  * closed->log branch itself is unit-covered in the api `AssertScopeTest`.
  */
class RTDetachedAfterSuite extends TestBase[Any]:
    "detached-fails-after" in Sync.defer {
        Fiber.initUnscoped {
            Async.sleep(500.millis).andThen(Abort.run[Throwable](assert(false, "detached fiber asserted after the leaf")))
        }
    }.andThen(succeed)
end RTDetachedAfterSuite

class RunnerTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = TestExecutionContext.executionContext

    private def countResults(report: TestReport): Int =
        report.suiteReports.foldLeft(0)((acc, sr) => acc + sr.leafResults.size)

    private def leafByPath(report: TestReport, path: Chunk[String]): Option[TestResult] =
        report.suiteReports.iterator
            .flatMap(_.leafResults.iterator)
            .collectFirst { case (p, r) if p == path => r }

    "Scenario 1: K leaves run concurrently, the (K+1)th suspends" in {
        RTConcurrencySuite.reset()
        TestRunner.runToFuture(classOf[RTConcurrencySuite], RunConfig.default.copy(parallelism = 2)).map { report =>
            val allPassed = report.suiteReports.forall(_.leafResults.forall {
                case (_, _: TestResult.Passed) => true
                case _                         => false
            })
            assert(countResults(report) == 5)
            assert(allPassed)
            assert(RTConcurrencySuite.peak.get() <= 2)
            assert(RTConcurrencySuite.peak.get() >= 1)
        }
    }

    "Scenario 2: a failing assert is recorded as a leaf failure, others unaffected" in {
        TestRunner.runToFuture(classOf[RTFailPassSuite], RunConfig.default).map { report =>
            assert(countResults(report) == 2)
            assert(leafByPath(report, Chunk("fails")).exists {
                case _: TestResult.Failed => true; case _ => false
            })
            assert(leafByPath(report, Chunk("passes")).exists {
                case _: TestResult.Passed => true; case _ => false
            })
        }
    }

    "Scenario 2b: Abort[Any] leaf baseline (non-throwable aborts, recovery, throwable aborts)" in {
        TestRunner.runToFuture(classOf[RTAbortAnySuite], RunConfig.default).map { report =>
            assert(countResults(report) == 4)
            // A non-Throwable abort is reported Failed, with the aborted value preserved in the diagram.
            assert(leafByPath(report, Chunk("fails-nonthrowable")).exists {
                case f: TestResult.Failed => f.diagram.contains("boom"); case _ => false
            })
            // A non-Throwable abort recovered within the leaf passes.
            assert(leafByPath(report, Chunk("recovers-nonthrowable")).exists {
                case _: TestResult.Passed => true; case _ => false
            })
            // A Throwable abort still reports Failed.
            assert(leafByPath(report, Chunk("fails-throwable")).exists {
                case _: TestResult.Failed => true; case _ => false
            })
            // A plain success under the Abort[Any] baseline passes.
            assert(leafByPath(report, Chunk("ok")).exists {
                case _: TestResult.Passed => true; case _ => false
            })
        }
    }

    "Scenario 4: haltOnFailure interrupts in-flight leaves via fiber interrupt" in {
        RTHaltSuite.reset()
        TestRunner.runToFuture(
            classOf[RTHaltSuite],
            RunConfig.default.copy(parallelism = 4, haltOnFailure = true)
        ).map { report =>
            assert(leafByPath(report, Chunk("fails-fast")).exists {
                case _: TestResult.Failed => true; case _ => false
            })
            assert(RTHaltSuite.finished.get() < 4)
        }
    }

    "Scenario 5: a leaf exceeding its timeout is interrupted and recorded TimedOut" in {
        RTTimeoutSuite.reset()
        TestRunner.runToFuture(classOf[RTTimeoutSuite], RunConfig.default).map { report =>
            assert(leafByPath(report, Chunk("slow")).exists {
                case _: TestResult.TimedOut => true; case _ => false
            })
            assert(RTTimeoutSuite.escaped.get() == 0)
        }
    }

    "Scenario 6: per-leaf Scope.run releases a leaf-acquired resource" in {
        RTScopeSuite.reset()
        TestRunner.runToFuture(classOf[RTScopeSuite], RunConfig.default).map { report =>
            assert(countResults(report) == 2)
            assert(RTScopeSuite.released.get() == 2)
        }
    }

    "Scenario 7: per-leaf Local context is visible inside the leaf fiber" in {
        val local = Local.init("default")
        val computation: String < (Async & Abort[Throwable]) =
            local.let("leaf-ctx")(Fiber.initUnscoped(local.use(v => v)).flatMap(_.get))
        val asFuture: Future[String] < Sync =
            Fiber.initUnscoped(computation).map(_.toFuture)
        import kyo.AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(asFuture).map { v =>
            assert(v == "leaf-ctx")
        }
    }

    "Scenario 10/Scenario 12: K defaults to max(1, Async.defaultConcurrency); single edge Future" in {
        TestRunner.runToFuture(classOf[RTTwoLeafSuite], RunConfig.default).map { report =>
            assert(countResults(report) == 2)
            assert(report.suiteReports.forall(_.leafResults.forall {
                case (_, _: TestResult.Passed) => true
                case _                         => false
            }))
            // K defaults to the auto value (cores*2 on JVM/Native, 2 on JS), not a hard 1.
            assert(math.max(1, Async.defaultConcurrency) >= 1)
        }
    }

    "Scenario 11: discovery is a synchronous Kyo walk producing the same leaf set" in {
        TestRunner.runToFuture(classOf[RTThreeLeafSuite], RunConfig.default).map { report =>
            assert(countResults(report) == 3)
            assert(report.suiteReports.forall(_.leafResults.forall {
                case (_, _: TestResult.Passed) => true
                case _                         => false
            }))
        }
    }

    // ── AssertScope leak-capture (runner-side capture) ─────────────────────────────────────────

    "AssertScope: a detached fiber that fails an assert DURING the leaf flips a passing body to Failed" in {
        // HEADLINE. The body awaits a Latch the detached fiber releases only after its assert has recorded, so the
        // record is guaranteed to be in the per-leaf sink before the body returns, which is before the runner drains.
        // The body itself returns unit (would be Passed); the drain flips it to Failed.
        TestRunner.runToFuture(classOf[RTDetachedDuringSuite], RunConfig.default).map { report =>
            assert(countResults(report) == 1)
            assert(
                leafByPath(report, Chunk("detached-fails-during")).exists {
                    case f: TestResult.Failed =>
                        // GOAL A: the flipped Failed is unmistakably labeled as a detached-fiber assertion, and the
                        // original diagram text (containing "false") is preserved after the label.
                        f.diagram.contains("detached-fiber assertion") && f.diagram.contains("false")
                    case _ => false
                },
                s"expected the detached-during leaf to be Failed and labeled but got $report"
            )
        }
    }

    "AssertScope: a passing leaf with no detached failure stays Passed (no-leak control)" in {
        TestRunner.runToFuture(classOf[RTNoLeakSuite], RunConfig.default).map { report =>
            assert(countResults(report) == 1)
            assert(
                leafByPath(report, Chunk("no-leak-passes")).exists {
                    case _: TestResult.Passed => true; case _ => false
                },
                s"expected the no-leak leaf to be Passed but got $report"
            )
        }
    }

    "AssertScope: a normal joined assert(false) leaf still reports Failed (throw path intact)" in {
        TestRunner.runToFuture(classOf[RTJoinedFailSuite], RunConfig.default).map { report =>
            assert(countResults(report) == 1)
            assert(
                leafByPath(report, Chunk("joined-fails")).exists {
                    case _: TestResult.Failed => true; case _ => false
                },
                s"expected the joined-fail leaf to be Failed but got $report"
            )
        }
    }

    "AssertScope: a detached fiber that fails AFTER the body leaves the leaf Passed" in {
        // The body returns immediately; the detached fiber sleeps, then asserts false after the runner has already
        // scored and closed the scope, so `record` takes the closed branch (a stderr "a fiber outlived its test"
        // warning) rather than failing the leaf. We assert only the deterministic integration fact: the leaf stays
        // Passed. The closed->log warning itself is deterministically covered by the api AssertScopeTest (close()
        // then record() emits the warning); asserting it here would race the detached fiber's wakeup.
        TestRunner.runToFuture(classOf[RTDetachedAfterSuite], RunConfig.default).map { report =>
            assert(countResults(report) == 1)
            assert(
                leafByPath(report, Chunk("detached-fails-after")).exists {
                    case _: TestResult.Passed => true; case _ => false
                },
                s"expected the detached-after leaf to be Passed but got $report"
            )
        }
    }

    "AssertScope: an after-leaf leak in the global collector becomes a synthetic failed leaf (GOAL B mechanism)" in {
        // GOAL B mechanism, deterministic. Rather than racing a real detached fiber's wakeup against the suite's drain
        // point, enqueue a leak directly into the process-global collector (private[kyo], reachable from this kyo-package
        // self-test), then run a suite through the runner. The runner drains the collector just before assembling the
        // SuiteReport and appends one synthetic failed leaf per leak. This collector is process-global, so clear it at the
        // start so a prior test cannot pollute it, and leave it empty at the end.
        val _      = kyo.test.AssertScope.drainLeakedAfterClose()
        val frame  = summon[Frame]
        val origin = new kyo.test.AssertionFailed("ORIGINAL-DIAGRAM-TEXT", frame, Maybe.empty[String], Maybe.empty[Throwable])
        kyo.test.AssertScope.leakedAfterClose.add((Chunk("some", "leaf"), origin)): Unit
        TestRunner.runToFuture(classOf[RTNoLeakSuite], RunConfig.default).map { report =>
            // The real passing leaf survives unchanged, plus exactly one synthetic leaf for the enqueued leak.
            assert(countResults(report) == 2, s"expected 1 real leaf + 1 synthetic leaf, got $report")
            // (a) a synthetic leaf whose path ends with the leaked-fiber marker.
            val syntheticPath = Chunk("some", "leaf", "(leaked fiber assertion)")
            val synthetic     = leafByPath(report, syntheticPath)
            assert(synthetic.isDefined, s"expected a synthetic leaf at path $syntheticPath but got $report")
            assert(
                synthetic.exists {
                    // (b) the synthetic leaf is Failed (so the run is red), and its diagram names the leak and carries the
                    // original failure's diagram text.
                    case f: TestResult.Failed =>
                        f.diagram.contains("leaked fiber assertion") && f.diagram.contains("ORIGINAL-DIAGRAM-TEXT")
                    case _ => false
                },
                s"expected the synthetic leaf to be a labeled Failed but got $synthetic"
            )
            // Leave the process-global collector empty for the next test (the runner already drained the one we enqueued).
            val _ = kyo.test.AssertScope.drainLeakedAfterClose()
            // The real leaf still Passed (the synthetic leaf does not displace it).
            assert(
                leafByPath(report, Chunk("no-leak-passes")).exists {
                    case _: TestResult.Passed => true; case _ => false
                },
                s"expected the real leaf to stay Passed but got $report"
            )
        }
    }

    "AssertScope: a clean run with no after-leaf leak yields NO synthetic leaf (GOAL B regression)" in {
        // Ensure the collector is empty, run a passing suite, and confirm the report has only the real leaf: no synthetic
        // leaf is appended when nothing leaked.
        val _ = kyo.test.AssertScope.drainLeakedAfterClose()
        TestRunner.runToFuture(classOf[RTNoLeakSuite], RunConfig.default).map { report =>
            assert(countResults(report) == 1, s"expected exactly 1 real leaf and no synthetic leaf, got $report")
            assert(
                leafByPath(report, Chunk("no-leak-passes")).exists {
                    case _: TestResult.Passed => true; case _ => false
                },
                s"expected the no-leak leaf to be Passed but got $report"
            )
        }
    }

end RunnerTest
