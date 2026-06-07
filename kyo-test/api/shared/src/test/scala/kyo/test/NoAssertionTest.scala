package kyo.test

import kyo.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Tests for the no-assertion check (Feature B mechanism).
  *
  * A leaf that completes Passed having evaluated zero assertions is flipped to Failed by the runner (and by LeafHarness when
  * failOnNoAssertion is true). The opt-out is succeed (an alias for assert(true)): it flows through the assert runtime, increments the
  * counter, and always passes.
  * Leaves 1-14 use LeafHarness.runLeaf. Leaves 7 and 15 (suite-level override and pendingUntilFixed ordering) require the full runner and
  * live in SelfTestsRunnerTest.
  */
// ScalaTest bootstrap: tests the no-assertion check mechanism; cannot self-host with the framework under test.
class NoAssertionTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // ── Leaf 1: counter increments per evaluation ──────────────────────────────────────────────

    "leaf-1: evaluationCount increments once per assert call" in {
        // Create a scope directly (ctor is private[kyo] but we are in kyo.test).
        val as = new AssertScope(Chunk("leaf-1"))
        as.recordEvaluated()
        assert(as.evaluationCount == 1L, s"expected 1 evaluation, got ${as.evaluationCount}")
        as.recordEvaluated()
        as.recordEvaluated()
        assert(as.evaluationCount == 3L, s"expected 3 evaluations, got ${as.evaluationCount}")
        Future.successful(succeed)
    }

    // ── Leaf 2: counter is independent of the failure sink ────────────────────────────────────

    "leaf-2: evaluationCount is independent of the failure sink" in {
        val as = new AssertScope(Chunk("leaf-2"))
        as.recordEvaluated()
        as.recordEvaluated()
        val drainedSink = as.drain()
        assert(as.evaluationCount == 2L, s"expected 2 evaluations, got ${as.evaluationCount}")
        assert(drainedSink.isEmpty, s"expected empty sink, got $drainedSink")
        Future.successful(succeed)
    }

    // ── Leaf 3: a no-assertion Passed leaf flips to Failed ────────────────────────────────────

    "leaf-3: a leaf that asserts nothing flips to Failed" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "no-assert" in { Sync.defer(1 + 1).map(_ => ()) }
        }.map {
            case f: TestResult.Failed =>
                assert(
                    f.diagram.contains("leaf passed without evaluating any assertion"),
                    s"expected no-assertion diagram, got: ${f.diagram}"
                )
                succeed
            case other => fail(s"Expected Failed with no-assertion diagram, got $other")
        }
    }

    // ── Leaf 4: a leaf with one assert stays Passed ───────────────────────────────────────────

    "leaf-4: a leaf with one assert stays Passed" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "one-assert" in { assert(1 + 1 == 2) }
        }.map {
            case _: TestResult.Passed => succeed
            case other                => fail(s"Expected Passed, got $other")
        }
    }

    // ── Leaf 5: LeafHarness no-assert leaf is Failed (harness mirrors the runner) ─────────────

    "leaf-5: LeafHarness and runner agree: no-assert leaf is Failed, one-assert leaf is Passed" in {
        val noAssertF = LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "no-assert-harness" in { Sync.defer(42).map(_ => ()) }
        }
        val oneAssertF = LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "one-assert-harness" in { assert(2 + 2 == 4) }
        }
        for
            noAssertResult  <- noAssertF
            oneAssertResult <- oneAssertF
        yield
            noAssertResult match
                case f: TestResult.Failed =>
                    assert(
                        f.diagram.contains("leaf passed without evaluating any assertion"),
                        s"expected no-assertion diagram, got: ${f.diagram}"
                    )
                case other => fail(s"no-assert leaf: Expected Failed, got $other")
            end match
            oneAssertResult match
                case _: TestResult.Passed => succeed
                case other                => fail(s"one-assert leaf: Expected Passed, got $other")
        end for
    }

    // ── Leaf 6: failOnNoAssertion = false disables the check ──────────────────────────────────

    "leaf-6: failOnNoAssertion = false disables the flip" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = false) {
            new kyo.test.Test[Any]:
                "no-assert-disabled" in { Sync.defer(1 + 1).map(_ => ()) }
        }.map {
            case _: TestResult.Passed => succeed
            case other                => fail(s"Expected Passed (check disabled), got $other")
        }
    }

    // ── Leaf 7: suite-level override lives in SelfTestsRunnerTest ─────────────────────────────
    // (requires TestRunner; kyo-test-api cannot import kyo-test-runner)

    // ── Leaf 8: RunConfig field default and copy-helper ───────────────────────────────────────

    "leaf-8: RunConfig.failOnNoAssertion defaults to true and copy-helper changes only that field" in {
        val default = RunConfig.default
        assert(default.failOnNoAssertion == true, s"expected default true, got ${default.failOnNoAssertion}")
        val disabled = default.failOnNoAssertion(false)
        assert(disabled.failOnNoAssertion == false, s"expected false after copy, got ${disabled.failOnNoAssertion}")
        // copy-helper changes only the failOnNoAssertion field
        assert(disabled.parallelism == default.parallelism)
        assert(disabled.timeout == default.timeout)
        Future.successful(succeed)
    }

    // ── Leaf 9: cancel-only leaf is Cancelled, never reaches the check ────────────────────────

    "leaf-9: cancel-only leaf is Cancelled (not subject to the no-assertion check)" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "cancel-only" in { cancel("skip") }
        }.map {
            case c: TestResult.Cancelled =>
                assert(c.reason.contains("skip"), s"expected 'skip' reason, got: ${c.reason}")
                succeed
            case other => fail(s"Expected Cancelled, got $other")
        }
    }

    // ── Leaf 10: assume(true)-only leaf FAILS (zero evaluations) ─────────────────────────────

    "leaf-10: assume(true)-only leaf fails (zero evaluations; satisfied precondition is not an assertion)" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "assume-only" in { assume(true) }
        }.map {
            case f: TestResult.Failed =>
                assert(
                    f.diagram.contains("leaf passed without evaluating any assertion"),
                    s"expected no-assertion diagram, got: ${f.diagram}"
                )
                succeed
            case other => fail(s"Expected Failed (assume(true) is not an assertion), got $other")
        }
    }

    // ── Leaf 11: succeed opts out ─────────────────────────────────────────────────────────────

    "leaf-11: succeed records one evaluation and opts out of the no-assertion check" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "succeed-leaf" in { Sync.defer(42).map(_ => ()).andThen(succeed) }
        }.map {
            case _: TestResult.Passed => succeed
            case other                => fail(s"Expected Passed (succeed opts out), got $other")
        }
    }

    // ── Leaf 12: counter never reset by drain (simulates flaky/retry accumulation) ─────────────

    "leaf-12: the evaluation counter accumulates and is never reset by the sink drain" in {
        // The counter is independent of the sink. Draining the sink (which the runner does on
        // each retry attempt to clear stale failure records) must NOT reset the counter.
        val as = new AssertScope(Chunk("leaf-12"))
        // Simulate attempt 1: record an evaluation, then drain the sink.
        as.recordEvaluated()
        val _ = as.drain()
        assert(as.evaluationCount == 1L, s"evaluationCount after drain should still be 1, got ${as.evaluationCount}")
        // Simulate attempt 2: record another evaluation, drain again.
        as.recordEvaluated()
        val _ = as.drain()
        assert(as.evaluationCount == 2L, s"evaluationCount after second drain should be 2, got ${as.evaluationCount}")
        // The counter only ever increases; draining has no effect on it.
        Future.successful(succeed)
    }

    // ── Leaf 13: conditional-path semantics (untaken branch) ─────────────────────────────────

    "leaf-13: untaken conditional branch means zero evaluations, flips to Failed" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "conditional" in {
                    if false then assert(1 == 1)
                    else ()
                }
        }.map {
            case f: TestResult.Failed =>
                assert(
                    f.diagram.contains("leaf passed without evaluating any assertion"),
                    s"expected no-assertion diagram, got: ${f.diagram}"
                )
                succeed
            case other => fail(s"Expected Failed (untaken conditional branch), got $other")
        }
    }

    // ── Leaf 14: detached-fiber assertion counts ──────────────────────────────────────────────

    "leaf-14: detached-fiber assert (joined) increments the counter; leaf stays Passed" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "detached-assert" in {
                    for
                        f <- Fiber.init(assert(1 + 1 == 2))
                        _ <- f.get
                    yield ()
                }
        }.map {
            case _: TestResult.Passed => succeed
            case other                => fail(s"Expected Passed (detached-fiber assert counted), got $other")
        }
    }

    // ── Leaf 15: pendingUntilFixed + no-assertion ordering lives in SelfTestsRunnerTest ────────
    // (requires TestRunner; kyo-test-api cannot import kyo-test-runner)

    // ── Leaf 16: a leaf whose only body is succeed stays Passed ───────────────────────────────

    "leaf-16: a leaf whose only body is succeed stays Passed (the per-leaf opt-out works)" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "succeed-only" in { succeed }
        }.map {
            case _: TestResult.Passed => succeed
            case other                => fail(s"Expected Passed (succeed opts out), got $other")
        }
    }

    // ── Leaf 17: a leaf whose only body is succeed("note") stays Passed ───────────────────────

    "leaf-17: a leaf whose only body is succeed(\"note\") stays Passed (noted opt-out works)" in {
        LeafHarness.runLeaf(Chunk(0), failOnNoAssertion = true) {
            new kyo.test.Test[Any]:
                "succeed-note-only" in { succeed("verified by construction; no runtime value to check") }
        }.map {
            case _: TestResult.Passed => succeed
            case other                => fail(s"Expected Passed (succeed(\"note\") opts out), got $other")
        }
    }

end NoAssertionTest
