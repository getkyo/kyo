package kyo.test

import kyo.Chunk
import kyo.Frame
import kyo.test.AssertScope
import kyo.test.internal.AssertMacro
import kyo.test.internal.Intercept
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.reflect.ClassTag

// ── Test-scope helpers ──────────────────────────────────────────────────────
// Inline defs that splice macros must live in top-level objects (separately compiled);
// they cannot be defined inside a class body at the call site.

// Helpers exercising intercept (now the Intercept runtime, not a macro) plus the retained fail / cancel macros. Covers
// throw / diagram behavior. intercept/fail thread an AssertScope (record-on-fail); cancel is scope-free (TestCancelled is
// not a failure).
object H:

    def intercept_[E <: Throwable](body: => Any)(using ct: ClassTag[E], f: Frame, as: AssertScope): E =
        Intercept.intercept[E](body)

    def interceptMessage_[E <: Throwable](msg: String)(body: => Any)(
        using
        ct: ClassTag[E],
        f: Frame,
        as: AssertScope
    ): E =
        Intercept.interceptMessage[E](msg)(body)

    inline def fail_(inline msg: String)(using inline f: Frame, inline as: AssertScope): Nothing =
        ${ AssertMacro.failImpl('msg, 'f, 'as) }

    inline def cancel_(inline msg: String)(using inline f: Frame): Nothing =
        ${ AssertMacro.cancelImpl('msg, 'f) }

end H

// ── Test class ─────────────────────────────────────────────────────────────

// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class AssertionMacrosTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // A throwaway leaf scope so the intercept/fail probes' `scope.record` splice resolves. Constructed in
    // non-inline class-body code via the private[kyo] ctor (this test is under package kyo).
    private given AssertScope = new AssertScope(Chunk.empty)

    // ── Intercept ─────────────────────────────────────────────────────────

    "InterceptTest" - {

        "captures expected throwable type" in {
            val e = H.intercept_[IllegalArgumentException] {
                throw new IllegalArgumentException("nope")
            }
            assert(e.getMessage == "nope", s"Expected 'nope', got: ${e.getMessage}")
            Future.successful(succeed)
        }

        "throws AssertionFailed when no throwable raised" in {
            try
                H.intercept_[IllegalArgumentException] { () }
                fail("Expected AssertionFailed to be thrown")
            catch
                case af: AssertionFailed =>
                    assert(
                        af.diagram.contains("no exception was raised"),
                        s"Expected 'no exception was raised' in diagram:\n${af.diagram}"
                    )
            end try
            Future.successful(succeed)
        }

        "throws AssertionFailed for wrong throwable type" in {
            try
                H.intercept_[IllegalArgumentException] {
                    throw new RuntimeException("wrong type")
                }
                fail("Expected AssertionFailed to be thrown")
            catch
                case af: AssertionFailed =>
                    assert(
                        af.diagram.contains("IllegalArgumentException") || af.diagram.contains("RuntimeException"),
                        s"Expected type names in diagram:\n${af.diagram}"
                    )
            end try
            Future.successful(succeed)
        }

    }

    // ── InterceptMessage ──────────────────────────────────────────────────

    "InterceptMessageTest" - {

        "passes when exception message matches" in {
            val e = H.interceptMessage_[IllegalArgumentException]("nope") {
                throw new IllegalArgumentException("nope")
            }
            assert(e.getMessage == "nope", s"Expected 'nope', got: ${e.getMessage}")
            Future.successful(succeed)
        }

        "throws when message does not match" in {
            try
                H.interceptMessage_[IllegalArgumentException]("expected error") {
                    throw new IllegalArgumentException("actual error")
                }
                fail("Expected AssertionFailed to be thrown")
            catch
                case af: AssertionFailed =>
                    assert(
                        af.diagram.contains("expected error") || af.diagram.contains("actual error"),
                        s"Expected both messages in diagram:\n${af.diagram}"
                    )
            end try
            Future.successful(succeed)
        }

    }

    // ── Fail ──────────────────────────────────────────────────────────────

    "FailTest" - {

        "fail throws AssertionFailed with msg" in {
            try
                H.fail_("oops")
                fail("Expected AssertionFailed to be thrown")
            catch
                case af: AssertionFailed =>
                    assert(
                        af.diagram == "oops",
                        s"Expected diagram == 'oops', got: ${af.diagram}"
                    )
            end try
            Future.successful(succeed)
        }

    }

    // ── Cancel ────────────────────────────────────────────────────────────

    "CancelTest" - {

        "cancel throws TestCancelled with reason" in {
            try
                H.cancel_("env not ready")
                fail("Expected TestCancelled to be thrown")
            catch
                case tc: TestCancelled =>
                    assert(
                        tc.reason == "env not ready",
                        s"Expected reason 'env not ready', got: ${tc.reason}"
                    )
            end try
            Future.successful(succeed)
        }

    }
end AssertionMacrosTest
