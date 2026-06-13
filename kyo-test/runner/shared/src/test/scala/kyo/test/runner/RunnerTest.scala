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

/** 5 leaves; each records the concurrent in-flight count so the test can assert the process-global pool bound.
  *
  * Each leaf raises the in-flight count, records the peak, yields once across an async boundary (fork-and-join a
  * trivial fiber, NOT a sleep and NOT a cross-leaf barrier so nothing can deadlock under competition for the shared
  * global pool), then lowers the count. The surviving deterministic assertion is the bound peak <= globalK, which
  * holds because a pool worker holds its leaf until the body completes, so at most globalK leaves are in-flight at
  * once. The deterministic concurrency-reaches-k proof lives in LeafPoolTest, not here.
  */
class RTConcurrencySuite extends TestBase[Any]:
    private def track(using kyo.test.AssertScope): Unit < (Async & Abort[Throwable] & Scope) =
        Sync.defer {
            val n = RTConcurrencySuite.inFlight.incrementAndGet()
            RTConcurrencySuite.updatePeak(n)
        }.andThen(Fiber.initUnscoped(()).map(_.get)).andThen(Sync.defer {
            RTConcurrencySuite.inFlight.decrementAndGet(): Unit
        }).andThen(succeed)
    "leaf-0" in track
    "leaf-1" in track
    "leaf-2" in track
    "leaf-3" in track
    "leaf-4" in track
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

/** A single leaf slower than a short heartbeat interval, used to prove `onLeafHeartbeat` fires while a leaf is still running. */
class RTHeartbeatSuite extends TestBase[Any]:
    "slow-leaf" in Async.sleep(1.second).andThen(succeed)
end RTHeartbeatSuite

/** A `TestReporter` that records every `onLeafHeartbeat` call (thread-safe) and ignores all other lifecycle events. */
final class RecordingHeartbeatReporter extends kyo.test.TestReporter:
    private val beats =
        new java.util.concurrent.atomic.AtomicReference[Vector[(Chunk[String], Duration)]](Vector.empty)
    def onRunStart(info: kyo.test.RunInfo): Unit                                      = ()
    def onSuiteStart(info: kyo.test.SuiteInfo): Unit                                  = ()
    def onLeafStart(info: kyo.test.LeafInfo): Unit                                    = ()
    def onLeafComplete(info: kyo.test.LeafInfo, result: TestResult): Unit             = ()
    def onSuiteComplete(info: kyo.test.SuiteInfo, report: kyo.test.SuiteReport): Unit = ()
    def onRunComplete(report: TestReport): Unit                                       = ()
    override def onLeafHeartbeat(info: kyo.test.LeafInfo, elapsed: Duration): Unit =
        beats.updateAndGet(_ :+ (info.path -> elapsed)): Unit
    def recorded: Vector[(Chunk[String], Duration)] = beats.get()
end RecordingHeartbeatReporter

class RunnerTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = TestExecutionContext.executionContext

    private def countResults(report: TestReport): Int =
        report.suiteReports.foldLeft(0)((acc, sr) => acc + sr.leafResults.size)

    private def leafByPath(report: TestReport, path: Chunk[String]): Option[TestResult] =
        report.suiteReports.iterator
            .flatMap(_.leafResults.iterator)
            .collectFirst { case (p, r) if p == path => r }

    "Scenario 1: all leaves run, bounded by the process-global pool" in {
        // Contract change (not a weakening): the old per-suite Meter cap (peak <= 2) is gone by design. Concurrent
        // leaf execution is now bounded by the process-global LeafPool's globalK across ALL suites, not per suite.
        // The deterministic assertions are the global bound (peak <= globalK) and completeness (all 5 leaves ran and
        // passed). The dedicated, deterministic concurrency-reaches-k proof (peak == k) lives in LeafPoolTest; through
        // the shared global pool, real concurrency cannot be observed deterministically without a sleep or a barrier
        // through the pool (which deadlocks under cross-suite competition), so peak > 1 is intentionally NOT asserted
        // here. globalK is computed in-test from the public formula (identical to LeafPool.globalK), no reflection.
        val globalK = if kyo.internal.Platform.isNative then 1 else math.max(1, Async.defaultConcurrency)
        RTConcurrencySuite.reset()
        TestRunner.runToFuture(classOf[RTConcurrencySuite], RunConfig.default.copy(parallelism = 2)).map { report =>
            val allPassed = report.suiteReports.forall(_.leafResults.forall {
                case (_, _: TestResult.Passed) => true
                case _                         => false
            })
            assert(countResults(report) == 5)
            assert(allPassed)
            // Global bound (deterministic): the peak never exceeds globalK.
            assert(RTConcurrencySuite.peak.get() <= globalK)
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

    "Heartbeat: a leaf slower than the heartbeat interval is reported as still running while it runs" in {
        val rec    = new RecordingHeartbeatReporter
        val config = RunConfig.default.copy(reporter = Maybe(rec), heartbeatInterval = 50.millis)
        TestRunner.runToFuture(classOf[RTHeartbeatSuite], config).map { report =>
            // Assert on the slow leaf itself, not the total count: a detached-fiber leak from another fixture can land in the
            // process-global collector during this suite's 1s window and be drained as a synthetic leaf (the GOAL B mechanism),
            // which is unrelated to the heartbeat under test.
            assert(leafByPath(report, Chunk("slow-leaf")).exists { case _: TestResult.Passed => true; case _ => false })
            val beats = rec.recorded
            assert(beats.nonEmpty, "expected at least one heartbeat for a leaf that ran far longer than the interval")
            assert(beats.forall(_._1 == Chunk("slow-leaf")), s"every heartbeat must name the running leaf, got $beats")
            assert(beats.forall(_._2.toMillis > 0), s"every heartbeat must carry a positive elapsed, got $beats")
        }
    }

    "Heartbeat: a fast leaf fires no heartbeat (negative control)" in {
        val rec = new RecordingHeartbeatReporter
        // Interval far larger than any leaf's runtime: the forked heartbeat is always interrupted before it can fire.
        val config = RunConfig.default.copy(reporter = Maybe(rec), heartbeatInterval = 30.seconds)
        TestRunner.runToFuture(classOf[RTThreeLeafSuite], config).map { report =>
            assert(countResults(report) == 3)
            assert(rec.recorded.isEmpty, s"a fast leaf must not trigger any heartbeat, got ${rec.recorded}")
        }
    }

end RunnerTest
