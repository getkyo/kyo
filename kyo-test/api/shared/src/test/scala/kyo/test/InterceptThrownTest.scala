package kyo.test

import kyo.Chunk
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Tests for `interceptThrown[E]` and `interceptThrownMessage[E]`, the Unit-returning variants of `intercept[E]` and `interceptMessage[E]`.
  *
  * Verifies that:
  *   - `interceptThrown[E]` returns Unit (no discarded value / -Wnonunit-statement warning)
  *   - Success when the expected exception is thrown
  *   - Failure when no exception is raised (AssertionFailed)
  *   - Failure when the wrong exception type is raised (AssertionFailed with type names)
  *   - `interceptThrownMessage[E]` passes when message matches
  *   - `interceptThrownMessage[E]` fails when message mismatches
  *   - Existing `intercept[E]` still returns E (no regression)
  */
// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class InterceptThrownTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    private def runLeaf[S <: kyo.test.Test[Any]](make: => S): Future[(Chunk[String], TestResult)] =
        LeafHarness.runLeafWithPath(make)

    // Test 6: interceptThrown[E] passes when the expected exception is thrown; returns Unit
    "interceptThrown[E]: passes when expected exception thrown" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "t" in {
                    interceptThrown[IllegalArgumentException] { throw new IllegalArgumentException("x") }
                }
        }.map { case (_, result) =>
            result match
                case TestResult.Passed(_, _) => succeed
                case other                   => fail(s"Expected Passed, got $other")
        }
    }

    // Test 7: interceptThrown[E] fails when no exception is raised
    "interceptThrown[E]: fails when no exception raised" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "t" in {
                    interceptThrown[Exception] { () }
                }
        }.map { case (_, result) =>
            result match
                case TestResult.Failed(msg, _, _, _) =>
                    assert(msg.contains("no exception was raised"), s"unexpected message: $msg")
                case other => fail(s"Expected Failed (no exception raised), got $other")
        }
    }

    // Test 8: interceptThrown[E] fails when the wrong exception type is raised
    "interceptThrown[E]: fails when wrong exception type thrown" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "t" in {
                    interceptThrown[IllegalArgumentException] { throw new NullPointerException("oops") }
                }
        }.map { case (_, result) =>
            result match
                case TestResult.Failed(msg, _, _, _) =>
                    assert(msg.contains("IllegalArgumentException"), s"unexpected message: $msg")
                    assert(msg.contains("NullPointerException"), s"unexpected message: $msg")
                case other => fail(s"Expected Failed (wrong type), got $other")
        }
    }

    // Test 9: interceptThrownMessage[E] passes when exception type and message match
    "interceptThrownMessage[E]: passes when type and message match" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "t" in {
                    interceptThrownMessage[IllegalStateException]("bad") {
                        throw new IllegalStateException("bad")
                    }
                }
        }.map { case (_, result) =>
            result match
                case TestResult.Passed(_, _) => succeed
                case other                   => fail(s"Expected Passed, got $other")
        }
    }

    // Test 10: interceptThrownMessage[E] fails when message mismatches
    "interceptThrownMessage[E]: fails when message mismatches" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "t" in {
                    interceptThrownMessage[IllegalStateException]("expected") {
                        throw new IllegalStateException("actual")
                    }
                }
        }.map { case (_, result) =>
            result match
                case TestResult.Failed(msg, _, _, _) =>
                    assert(msg.contains("expected"), s"unexpected message: $msg")
                    assert(msg.contains("actual"), s"unexpected message: $msg")
                case other => fail(s"Expected Failed (message mismatch), got $other")
        }
    }

    // Test 11: interceptThrown[E] as sole statement compiles without -Wnonunit-statement warning
    // (structural: the return type is Unit, so it does not produce a discarded value)
    "interceptThrown[E] return type is Unit: no discard warning at call site" in {
        // Verified structurally: interceptThrown returns Unit. The body of a test leaf
        // expects Unit | F[Unit]. Returning Unit from a sole-statement body is compliant.
        // This test documents the contract; the compile step for the test is the enforcement.
        runLeaf {
            new kyo.test.Test[Any]:
                "t" in {
                    // interceptThrown as the sole statement in the leaf body: must not trigger
                    // -Wnonunit-statement (since the return type is Unit, not E)
                    interceptThrown[RuntimeException] { throw new RuntimeException("ok") }
                }
        }.map { case (_, result) =>
            result match
                case TestResult.Passed(_, _) => succeed
                case other                   => fail(s"Expected Passed, got $other")
        }
    }

    // Test 12: existing intercept[E] still returns E (no regression)
    "intercept[E] still returns E: getMessage accessible" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "t" in {
                    val ex = intercept[IllegalArgumentException] { throw new IllegalArgumentException("hello") }
                    assert(ex.getMessage == "hello")
                }
        }.map { case (_, result) =>
            result match
                case TestResult.Passed(_, _) => succeed
                case other                   => fail(s"Expected Passed, got $other")
        }
    }

end InterceptThrownTest
