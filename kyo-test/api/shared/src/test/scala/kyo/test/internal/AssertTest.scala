package kyo.test.internal

import kyo.Chunk
import kyo.Frame
import kyo.test.AssertionFailed
import kyo.test.AssertScope
import kyo.test.TestCancelled
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ── Probe helpers ──────────────────────────────────────────────────────────────
// Top-level object required for inline macro splices in Scala 3: inline defs
// with macro quotes cannot be defined inside a test-class body.

object AssertTestProbe:

    /** Invoke the scope-threaded `assert(cond)` (mirrors the new base's assert wiring). */
    inline def runAssert(inline cond: Boolean)(using inline f: Frame, inline as: AssertScope): Unit =
        ${ AssertMacro.assertImpl('cond, 'f, 'as) }

    /** Invoke the scope-threaded `assert(cond, msg)`. */
    inline def runAssertMsg(inline cond: Boolean, inline msg: String)(using inline f: Frame, inline as: AssertScope): Unit =
        ${ AssertMacro.assertWithMsgImpl('cond, 'msg, 'f, 'as) }

    /** Invoke `fail(msg)` via AssertMacro.failImpl. */
    inline def runFail(inline msg: String)(using inline f: Frame, inline as: AssertScope): Nothing =
        ${ AssertMacro.failImpl('msg, 'f, 'as) }

    /** Invoke `cancel(msg)` via AssertMacro.cancelImpl (scope-free: TestCancelled is not a failure). */
    inline def runCancel(inline msg: String)(using inline f: Frame): Nothing =
        ${ AssertMacro.cancelImpl('msg, 'f) }

    /** Invoke `intercept[E]` via the Intercept runtime. */
    def runIntercept[E <: Throwable](body: => Any)(using
        ct: scala.reflect.ClassTag[E],
        f: Frame,
        as: AssertScope
    ): E =
        Intercept.intercept[E](body)

end AssertTestProbe

// ── Test class ─────────────────────────────────────────────────────────────────

// ScalaTest bootstrap: tests the assert/fail/cancel/intercept surface of
// kyo.test.internal.TestBase (single power-assert; structured asserts and softly removed;
// intercept/fail/cancel unchanged).
class AssertTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // A throwaway leaf scope so the probe macros' `scope.record` splice resolves. Constructed in non-inline
    // class-body code via the private[kyo] ctor (this test is under package kyo).
    private given AssertScope = new AssertScope(Chunk.empty)

    // ── Leaf 1 scenario A: assert(a == b) produces a power diagram on failure ──

    "single-assert-power-diagram: assert(a == b) on unequal values throws with diagram" in {
        val a = 42
        val b = 99
        try
            AssertTestProbe.runAssert(a == b)
            fail("Expected AssertionFailed to be thrown")
        catch
            case af: AssertionFailed =>
                val msg = af.getMessage
                assert(
                    msg.contains("42"),
                    s"Expected '42' (value of a) in diagram:\n$msg"
                )
                assert(
                    msg.contains("99"),
                    s"Expected '99' (value of b) in diagram:\n$msg"
                )
                assert(
                    msg.contains("a == b"),
                    s"Expected source text 'a == b' in diagram:\n$msg"
                )
        end try
        Future.successful(succeed)
    }

    // ── Leaf 1 scenario B: assert(cond, msg) footer order: "// at" before "// message:" ──

    "single-assert-power-diagram: assert(cond, msg) has // at before // message:" in {
        try
            AssertTestProbe.runAssertMsg(1 == 2, "custom explanation")
            fail("Expected AssertionFailed to be thrown")
        catch
            case af: AssertionFailed =>
                val msg    = af.getMessage
                val atIdx  = msg.indexOf("// at ")
                val msgIdx = msg.indexOf("// message: custom explanation")
                assert(atIdx >= 0, s"Expected '// at ' in diagram:\n$msg")
                assert(msgIdx >= 0, s"Expected '// message: custom explanation' in diagram:\n$msg")
                assert(atIdx < msgIdx, s"Expected '// at' before '// message:' in diagram:\n$msg")
        end try
        Future.successful(succeed)
    }

    // ── Leaf 2 scenario A: fail(msg) throws AssertionFailed with the message ──

    "fail-cancel-throw: fail(msg) throws AssertionFailed containing the message" in {
        try
            AssertTestProbe.runFail("deliberate failure")
            fail("Expected AssertionFailed to be thrown")
        catch
            case af: AssertionFailed =>
                assert(
                    af.getMessage.contains("deliberate failure"),
                    s"Expected 'deliberate failure' in message: ${af.getMessage}"
                )
        end try
        Future.successful(succeed)
    }

    // ── Leaf 2 scenario B: cancel(msg) throws TestCancelled with the message ──

    "fail-cancel-throw: cancel(msg) throws TestCancelled containing the message" in {
        try
            AssertTestProbe.runCancel("deliberate cancel")
            fail("Expected TestCancelled to be thrown")
        catch
            case tc: TestCancelled =>
                assert(
                    tc.getMessage.contains("deliberate cancel"),
                    s"Expected 'deliberate cancel' in message: ${tc.getMessage}"
                )
        end try
        Future.successful(succeed)
    }

    // ── Leaf 2 scenario C: intercept[E] catches the thrown E ──

    "fail-cancel-throw: intercept[E] returns the caught exception" in {
        val ex = AssertTestProbe.runIntercept[IllegalArgumentException] {
            throw new IllegalArgumentException("oops")
        }
        assert(ex.getMessage == "oops", s"Expected message 'oops', got: ${ex.getMessage}")
        Future.successful(succeed)
    }

    // ── Leaf 2 scenario D: intercept[E] on a body that does not throw ──

    "fail-cancel-throw: intercept[E] throws AssertionFailed when body does not throw" in {
        try
            AssertTestProbe.runIntercept[IllegalArgumentException] { () }
            fail("Expected AssertionFailed to be thrown")
        catch
            case _: AssertionFailed => ()
        end try
        Future.successful(succeed)
    }

    // ── Leaf 3 scenario A: softly is NOT a member of the new base ──
    // Note: Scala 3's typeCheckErrors reports "Not found: softly" (capital N,
    // no "value" prefix). The identifier must not resolve at all.

    "no-softly-symbol: softly is not a member of kyo.test.Test[Any]" in {
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            object s extends kyo.test.Test[Any]:
                softly { () }
            """
        )
        assert(
            errors.nonEmpty,
            "Expected a compile error for 'softly' but code compiled successfully"
        )
        assert(
            errors.exists(_.message.contains("softly")),
            s"Expected error mentioning 'softly':\n${errors.map(_.message).mkString("\n")}"
        )
        Future.successful(succeed)
    }

    // ── Leaf 3 scenario B: assertEquals is NOT a member of the new base ──
    // Note: Scala 3's typeCheckErrors reports "Not found: assertEquals" (capital N).

    "no-softly-symbol: assertEquals is not a member of kyo.test.Test[Any]" in {
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            object s extends kyo.test.Test[Any]:
                assertEquals(1, 1)
            """
        )
        assert(
            errors.nonEmpty,
            "Expected a compile error for 'assertEquals' but code compiled successfully"
        )
        assert(
            errors.exists(_.message.contains("assertEquals")),
            s"Expected error mentioning 'assertEquals':\n${errors.map(_.message).mkString("\n")}"
        )
        Future.successful(succeed)
    }

    // ── Confirm assert identifier resolves on the new base ──
    // typeCheckErrors inside kyo.test.internal package triggers a Frame-derivation
    // guard error (Frame cannot be derived within kyo.*). The key check is that
    // the errors do NOT mention "Not found: assert" - i.e. the assert symbol IS
    // found on the new base (the error is about Frame, not about assert).

    "no-softly-symbol: assert resolves as a member of kyo.test.Test[Any]" in {
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            object s extends kyo.test.Test[Any]:
                assert(1 == 1)
            """
        )
        assert(
            !errors.exists(e => e.message.contains("Not found: assert") || e.message.contains("not found: value assert")),
            s"assert should resolve on the new base but got 'not found' error:\n${errors.map(_.message).mkString("\n")}"
        )
        Future.successful(succeed)
    }

end AssertTest
