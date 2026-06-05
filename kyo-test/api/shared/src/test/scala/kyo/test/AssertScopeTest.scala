package kyo.test

import kyo.*
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Self-tests for the `AssertScope` leak-capture feature.
  *
  * Covers three surfaces:
  *
  *   - the `AssertScope` value itself (record while open queues, record after close logs to stderr and drops);
  *   - the assert family driven through a real leaf via [[LeafHarness]] (assert / assert+msg / fail / intercept / cancel / assume, the
  *     helper-tax mechanic, and a detached fiber whose failure flips a passing leaf to Failed via the sink);
  *   - the compile-time guarantee that the whole assert family is only well-typed inside an `in` leaf (an `assert` in a `-` group body or in
  *     the suite class body does not compile, proven via the framework's own `typeCheckFailure`).
  */
class AssertScopeTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    private def runLeaf[S <: kyo.test.Test[Any]](make: => S): Future[TestResult] =
        LeafHarness.runLeaf(make)

    // ────────────────────────────────────────────────────────────────────────
    // AssertScope unit behavior
    // ────────────────────────────────────────────────────────────────────────

    "AssertScope unit" - {

        "record while open queues each failure; drain returns them" in {
            val scope = new AssertScope(Chunk("group", "leaf"))
            val frame = summon[Frame]
            scope.record(new AssertionFailed("boom-1", frame, Maybe.empty[String], Maybe.empty[Throwable]))
            scope.record(new AssertionFailed("boom-2", frame, Maybe.empty[String], Maybe.empty[Throwable]))
            val drained = scope.drain()
            assert(drained.size == 2, s"expected exactly 2 captured failures while open, got ${drained.size}")
            assert(
                drained.map(_.diagram).toList == List("boom-1", "boom-2"),
                s"expected the two recorded diagrams in order, got ${drained.map(_.diagram).toList}"
            )
            Future.successful(succeed)
        }

        "record after close drops the failure, emits a stderr warning naming the leaf path" in {
            val captured = new StringBuilder
            val scope = new AssertScope(
                Chunk("outer", "inner-leaf"),
                s =>
                    captured.append(s).append('\n'); ()
            )
            val frame = summon[Frame]
            scope.close()
            scope.record(new AssertionFailed("late-boom", frame, Maybe.empty[String], Maybe.empty[Throwable]))
            val warning = captured.toString
            assert(scope.drain().isEmpty, "expected nothing queued after close")
            assert(warning.nonEmpty, "expected a diagnostics warning when recording after close")
            assert(
                warning.contains("a fiber outlived its test"),
                s"expected the outlived-fiber warning text, got:\n$warning"
            )
            assert(
                warning.contains("outer") && warning.contains("inner-leaf"),
                s"expected the leaf path 'outer > inner-leaf' in the warning, got:\n$warning"
            )
            Future.successful(succeed)
        }

        "record after close ALSO enqueues into the global after-close collector (GOAL B)" in {
            // GOAL B part 1: the CLOSED branch keeps the stderr warning AND enqueues (path, failure) into the process-global
            // collector so the runner can turn it into a synthetic failed leaf. This collector is process-global, so drain it
            // first to avoid pollution and leave it empty at the end.
            val _ = AssertScope.drainLeakedAfterClose()
            // Capture the (expected) warning into a per-instance sink so it does not clutter test output and the assertion is
            // deterministic; we only assert the enqueue and the warning here.
            val sink = new StringBuilder
            val scope = new AssertScope(
                Chunk("p", "leaf-after"),
                s =>
                    sink.append(s).append('\n'); ()
            )
            val frame = summon[Frame]
            scope.close()
            scope.record(new AssertionFailed("after-close-boom", frame, Maybe.empty[String], Maybe.empty[Throwable]))
            assert(sink.toString.contains("a fiber outlived its test"), "expected the diagnostics warning to still fire")
            val drained = AssertScope.drainLeakedAfterClose()
            assert(drained.size == 1, s"expected exactly 1 entry enqueued into the global collector, got ${drained.size}")
            val (path, failure) = drained.head
            assert(path == Chunk("p", "leaf-after"), s"expected the leaf path enqueued, got $path")
            assert(failure.diagram == "after-close-boom", s"expected the failure enqueued, got ${failure.diagram}")
            // The instance sink stays empty: an after-close record goes only to stderr + the global collector, never the sink.
            assert(scope.drain().isEmpty, "expected nothing queued into the per-leaf sink after close")
            // Leave the global collector empty for the next test.
            assert(AssertScope.drainLeakedAfterClose().isEmpty, "expected the global collector empty after draining")
            Future.successful(succeed)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Assert family driven through a real leaf
    // ────────────────────────────────────────────────────────────────────────

    "leaf behavior" - {

        "assert(true) scores Passed" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "ok" in { assert(true) }
            }.map {
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
            }
        }

        "assert(false) scores Failed" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "bad" in { assert(false) }
            }.map {
                case _: TestResult.Failed => succeed
                case other                => fail(s"Expected Failed, got $other")
            }
        }

        // Regression guard for #228: the `|` markers in a failure diagram must sit under the
        // sub-expressions they point at, NOT shifted right by the leaf's source indentation.
        // Before the relative-column fix, each marker was placed at the ABSOLUTE source-file
        // column while the header was de-indented to column 0, so every marker landed ~N columns
        // too far right (N = the leaf body's indentation). The `assert` below is deliberately
        // nested deep inside the suite so its source indentation is large; the assertion checks
        // that the pipe line's first `|` lands exactly under the left operand token in the header
        // and that no marker runs past the header width.
        "diagram pipe markers align under the operands, not shifted by indentation (#228)" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "deeply-indented-leaf" in {
                        val leftOperand  = 138
                        val rightOperand = 157
                        assert(leftOperand == rightOperand)
                    }
            }.map {
                case f: TestResult.Failed =>
                    val lines = f.diagram.split("\n", -1)
                    assert(lines.length >= 2, s"expected at least a header and a pipe line, got:\n${f.diagram}")
                    val header   = lines(0)
                    val pipeLine = lines(1)
                    // The header is the de-indented cond text, so the left operand token starts at column 0.
                    val leftIdx = header.indexOf("leftOperand")
                    assert(leftIdx == 0, s"expected the header to start with 'leftOperand', got header:\n'$header'")
                    val rightIdx = header.indexOf("rightOperand")
                    assert(rightIdx > 0, s"expected 'rightOperand' in the header, got header:\n'$header'")
                    val firstPipe = pipeLine.indexOf('|')
                    assert(firstPipe >= 0, s"expected at least one '|' in the pipe line, got:\n${f.diagram}")
                    // The first marker must sit exactly under the left operand token, i.e. at column 0,
                    // not shifted right by the leaf's indentation.
                    assert(
                        firstPipe == leftIdx,
                        s"first '|' at column $firstPipe but 'leftOperand' starts at column $leftIdx; markers shifted by indentation\n${f.diagram}"
                    )
                    // No marker may run past the header width: a marker beyond the header means it points
                    // at empty space to the right of the de-indented expression (the indentation-shift bug).
                    val lastPipe = pipeLine.lastIndexOf('|')
                    assert(
                        lastPipe < header.length,
                        s"last '|' at column $lastPipe is past the header width ${header.length}; markers shifted by indentation\n${f.diagram}"
                    )
                    // And a marker must sit exactly under the right operand token too.
                    assert(
                        pipeLine.length > rightIdx && pipeLine(rightIdx) == '|',
                        s"expected a '|' under 'rightOperand' at column $rightIdx, got pipe line:\n'$pipeLine'\n${f.diagram}"
                    )
                    succeed
                case other => fail(s"Expected Failed, got $other")
            }
        }

        "assert(false, \"boom\") scores Failed with the message in the diagram" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "bad-msg" in { assert(false, "boom") }
            }.map {
                case f: TestResult.Failed =>
                    assert(f.diagram.contains("boom"), s"expected 'boom' in diagram:\n${f.diagram}")
                case other => fail(s"Expected Failed, got $other")
            }
        }

        "a helper `def h(using AssertScope)` that asserts false scores Failed (helper-tax mechanic)" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    def h(using AssertScope): Unit = assert(false)
                    "helper" in { h }
            }.map {
                case _: TestResult.Failed => succeed
                case other                => fail(s"Expected Failed, got $other")
            }
        }

        "a detached unjoined fiber asserting false during the leaf flips Passed -> Failed via the sink" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "detached" in {
                        for
                            recorded <- Latch.init(1)
                            // Fork an UNJOINED fiber; its `assert(false)` records into the captured AssertScope, then releases.
                            _ <- Fiber.initUnscoped {
                                Sync.defer {
                                    try assert(false)
                                    catch case _: Throwable => ()
                                }.andThen(recorded.release)
                            }
                            // The body waits for the detached assert to have recorded, then passes. Awaiting the latch makes
                            // the record land before the harness drains, so the flip is deterministic.
                            _ <- recorded.await
                        yield ()
                    }
            }.map {
                case _: TestResult.Failed => succeed
                case other                => fail(s"Expected Failed (detached fiber's recorded failure flips it), got $other")
            }
        }

        "fail(msg) scores Failed with the message" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "fail" in { fail("deliberate") }
            }.map {
                case f: TestResult.Failed =>
                    assert(f.diagram.contains("deliberate"), s"expected 'deliberate' in diagram:\n${f.diagram}")
                case other => fail(s"Expected Failed, got $other")
            }
        }

        "fail(cause) scores Failed and carries the cause" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "fail-cause" in { fail(new RuntimeException("root-cause")) }
            }.map {
                case f: TestResult.Failed =>
                    assert(f.diagram.contains("root-cause"), s"expected the cause text in diagram:\n${f.diagram}")
                case other => fail(s"Expected Failed, got $other")
            }
        }

        "intercept[E] scores Passed when the body throws E" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "intercept-ok" in {
                        val _ = intercept[IllegalArgumentException](throw new IllegalArgumentException("x"))
                        ()
                    }
            }.map {
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
            }
        }

        "intercept[E] scores Failed when the body does not throw" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "intercept-bad" in {
                        val _ = intercept[IllegalArgumentException](())
                        ()
                    }
            }.map {
                case _: TestResult.Failed => succeed
                case other                => fail(s"Expected Failed, got $other")
            }
        }

        "cancel(msg) scores Cancelled" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "cancelled" in { cancel("env not ready") }
            }.map {
                case c: TestResult.Cancelled =>
                    assert(c.reason.contains("env not ready"), s"expected the reason, got: ${c.reason}")
                case other => fail(s"Expected Cancelled, got $other")
            }
        }

        "assume(false) scores Cancelled (not Failed)" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "assumed" in { assume(false, "precondition unmet") }
            }.map {
                case _: TestResult.Cancelled => succeed
                case other                   => fail(s"Expected Cancelled, got $other")
            }
        }

        "assume(true) lets the leaf run and Pass" in {
            runLeaf {
                new kyo.test.Test[Any]:
                    "assume-pass" in {
                        assume(true)
                        assert(1 == 1)
                    }
            }.map {
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Compile-time guarantee: the whole assert family is only well-typed inside an `in` leaf.
    //
    // The check uses the stdlib `typeCheckErrors` from this scope-free ScalaTest method body
    // and asserts the rejection names the missing `AssertScope` for the kyo `assert` member.
    // The kyo `assert` member shadows `scala.Predef.assert`, so the missing-scope error is
    // genuine and is not masked by a Predef fallback (the diagnostic shows the only error is
    // "No given instance of type kyo.test.AssertScope ... for parameter as of method assert").
    //
    // Two notes on the mechanism:
    //   - The framework's own `typeCheckFailure` cannot self-prove this: it now requires an
    //     `AssertScope`, so it can only be called from inside a leaf, and that leaf's ambient
    //     `AssertScope` leaks into the implicit search of the snippet it compiles, making the
    //     snippet's `assert(true)` resolve the scope and compile (defeating the check).
    //   - The positive direction (an `assert` inside a leaf DOES compile and run) is proven at
    //     runtime by the "assert(true) scores Passed" leaf test above; it cannot be expressed
    //     as a passing `typeCheckErrors` snippet because Frame derivation for the in-leaf
    //     `assert` trips the kyo-package Frame guard in that synthetic compile context.
    // ────────────────────────────────────────────────────────────────────────

    "compile-time guarantee" - {

        "assert in a `-` group body does not compile (no AssertScope)" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                new kyo.test.Test[Any]:
                    "g" - { assert(true) }
                """
            )
            assert(errors.nonEmpty, "expected `assert(true)` in a group body to be rejected, but it compiled")
            assert(
                errors.exists(e =>
                    e.message.contains("AssertScope") && e.message.contains("assert") && e.message.contains("per-leaf evidence")
                ),
                s"expected the missing-AssertScope error for the kyo assert member, got:\n${errors.map(_.message).mkString("\n")}"
            )
            Future.successful(succeed)
        }

        "assert in the suite class body does not compile (no AssertScope)" in {
            val errors = scala.compiletime.testing.typeCheckErrors(
                """
                new kyo.test.Test[Any]:
                    assert(true)
                """
            )
            assert(errors.nonEmpty, "expected `assert(true)` in the suite class body to be rejected, but it compiled")
            assert(
                errors.exists(e =>
                    e.message.contains("AssertScope") && e.message.contains("assert") && e.message.contains("per-leaf evidence")
                ),
                s"expected the missing-AssertScope error for the kyo assert member, got:\n${errors.map(_.message).mkString("\n")}"
            )
            Future.successful(succeed)
        }
    }

end AssertScopeTest
