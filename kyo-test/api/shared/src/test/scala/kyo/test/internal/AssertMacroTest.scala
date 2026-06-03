package kyo.test.internal

import kyo.Chunk
import kyo.Frame
import kyo.test.AssertionFailed
import kyo.test.AssertScope
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// A standalone object (separate from the test class) is required for inline macros
// in Scala 3: inline defs that splice macros cannot be defined inside a class body
// at the call site. The V3 assert macros thread an AssertScope: on failure they record
// into it and throw AssertionFailed. These probes supply a throwaway scope (constructed
// in non-inline test code via the private[kyo] ctor, accessible under package kyo) so the
// macro's `scope.record` splice resolves; the tests cover throw / diagram / short-circuit.
object TestAssertHelper:

    inline def doAssert(inline cond: Boolean)(using inline frame: Frame, inline as: AssertScope): Unit =
        ${ AssertMacro.assertImpl('cond, 'frame, 'as) }

    inline def doAssertMsg(inline cond: Boolean, inline msg: String)(using inline frame: Frame, inline as: AssertScope): Unit =
        ${ AssertMacro.assertWithMsgImpl('cond, 'msg, 'frame, 'as) }
end TestAssertHelper

// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class AssertMacroTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // A throwaway leaf scope so the probe's `scope.record` splice resolves. Constructed in non-inline
    // class-body code via the private[kyo] ctor (this test is under package kyo).
    private given AssertScope = new AssertScope(Chunk.empty)

    // Tracks whether a by-name argument was evaluated
    private def expensive(): Boolean =
        throw new RuntimeException("expensive() should not have been called: short-circuit broken")

    "AssertMacro" - {

        "assert passes when cond is true" in {
            // Must not throw
            TestAssertHelper.doAssert(1 == 1)
            Future.successful(succeed)
        }

        "assert throws AssertionFailed when cond is false" in {
            try
                TestAssertHelper.doAssert(1 == 2)
                fail("Expected AssertionFailed to be thrown")
            catch
                case _: AssertionFailed => ()
            end try
            Future.successful(succeed)
        }

        "assert diagram includes subexpression values" in {
            val x = 5
            try
                TestAssertHelper.doAssert(x > 10)
                fail("Expected AssertionFailed to be thrown")
            catch
                case af: AssertionFailed =>
                    assert(
                        af.diagram.contains("5"),
                        s"Expected '5' (value of x) in diagram:\n${af.diagram}"
                    )
            end try
            Future.successful(succeed)
        }

        "assert with message includes the message" in {
            try
                TestAssertHelper.doAssertMsg(false, "expected to fail")
                fail("Expected AssertionFailed to be thrown")
            catch
                case af: AssertionFailed =>
                    assert(
                        af.msg.isDefined,
                        s"Expected msg to be Present"
                    )
                    assert(
                        af.msg.getOrElse("") == "expected to fail",
                        s"Expected message 'expected to fail', got: ${af.msg}"
                    )
                    assert(
                        af.diagram.contains("expected to fail"),
                        s"Expected diagram to contain message text 'expected to fail', got:\n${af.diagram}"
                    )
            end try
            Future.successful(succeed)
        }

        "assert handles && short-circuit correctly" in {
            // If LHS is false, RHS (expensive()) must not be evaluated
            try
                TestAssertHelper.doAssert(false && expensive())
                fail("Expected AssertionFailed to be thrown")
            catch
                case _: AssertionFailed =>
                    // Reaching here means expensive() was not called: short-circuit worked
                    ()
                case e: RuntimeException if e.getMessage.contains("expensive()") =>
                    fail("Short-circuit broken: expensive() was called when LHS is false")
            end try
            Future.successful(succeed)
        }

        "assert handles || short-circuit correctly" in {
            // If LHS is true, RHS (expensive()) must not be evaluated
            TestAssertHelper.doAssert(true || expensive())
            // If we get here, expensive() was not called
            Future.successful(succeed)
        }

        "assert handles method chains" in {
            try
                TestAssertHelper.doAssert(List(1, 2, 3).filter(_ > 1).size == 2)
            catch
                case af: AssertionFailed =>
                    fail(s"Expected assertion to pass, got:\n${af.diagram}")
            end try
            Future.successful(succeed)
        }

        "assert handles negation" in {
            val list = List(1, 2, 3)
            try
                TestAssertHelper.doAssert(!list.isEmpty)
            catch
                case af: AssertionFailed =>
                    fail(s"Expected assertion to pass, got:\n${af.diagram}")
            end try
            Future.successful(succeed)
        }

        "assert handles string equality with diff" in {
            try
                TestAssertHelper.doAssert("foo" == "bar")
                fail("Expected AssertionFailed to be thrown")
            catch
                case af: AssertionFailed =>
                    // Diagram must reference the two string values
                    assert(
                        af.diagram.contains("foo") || af.diagram.contains("bar"),
                        s"Expected string values in diagram:\n${af.diagram}"
                    )
            end try
            Future.successful(succeed)
        }

        // DEFECT 1: Java-static / module / package qualifiers must not be recorded.
        // Recording them re-emits a Java class as a nonexistent `Class$` companion,
        // producing a runtime NoClassDefFoundError.

        "assert handles Java static method qualifier (passing)" in {
            // Previously: recording the `java.lang.Double` prefix threw NoClassDefFoundError: Double$
            TestAssertHelper.doAssert(java.lang.Double.parseDouble("1.5") == 1.5)
            Future.successful(succeed)
        }

        "assert handles Java static method qualifier (failing)" in {
            try
                TestAssertHelper.doAssert(java.lang.Double.parseDouble("1.5") == 2.0)
                fail("Expected AssertionFailed to be thrown")
            catch
                case _: AssertionFailed => ()
            end try
            Future.successful(succeed)
        }

        "assert handles Java static field qualifier (passing)" in {
            // Previously: recording the `java.lang.Integer` prefix threw NoClassDefFoundError: Integer$
            TestAssertHelper.doAssert(java.lang.Integer.TYPE != null)
            Future.successful(succeed)
        }

        "assert handles Java static field qualifier (failing)" in {
            try
                TestAssertHelper.doAssert(java.lang.Integer.TYPE == null)
                fail("Expected AssertionFailed to be thrown")
            catch
                case _: AssertionFailed => ()
            end try
            Future.successful(succeed)
        }

        // DEFECT 2: `new` / constructor terms must not be wrapped with a record call;
        // doing so crashes the GenBCode backend ("Unexpected New ... reached GenBCode").

        "assert handles new expression (passing)" in {
            TestAssertHelper.doAssert((new Object).hashCode() != 0 || true)
            Future.successful(succeed)
        }

        "assert handles new expression (failing)" in {
            try
                TestAssertHelper.doAssert((new Object).hashCode() != 0 && false)
                fail("Expected AssertionFailed to be thrown")
            catch
                case _: AssertionFailed => ()
            end try
            Future.successful(succeed)
        }

        "assert handles anonymous-class new expression (passing)" in {
            trait T:
                def x = 1
            TestAssertHelper.doAssert((new T {}).x == 1)
            Future.successful(succeed)
        }

        "assert handles anonymous-class new expression (failing)" in {
            trait T:
                def x = 1
            try
                TestAssertHelper.doAssert((new T {}).x == 2)
                fail("Expected AssertionFailed to be thrown")
            catch
                case _: AssertionFailed => ()
            end try
            Future.successful(succeed)
        }

        // A genuine value qualifier must STILL be instrumented (recorded) as before.

        "assert still instruments a normal value qualifier (passing)" in {
            val s = "abc"
            TestAssertHelper.doAssert(s.length == 3)
            Future.successful(succeed)
        }

        "assert still records a normal value qualifier in the diagram (failing)" in {
            val s = "abc"
            try
                TestAssertHelper.doAssert(s.length == 4)
                fail("Expected AssertionFailed to be thrown")
            catch
                case af: AssertionFailed =>
                    // The recorded subexpression value (length 3) must appear in the diagram.
                    assert(
                        af.diagram.contains("3"),
                        s"Expected recorded value '3' (s.length) in diagram:\n${af.diagram}"
                    )
            end try
            Future.successful(succeed)
        }

    }

end AssertMacroTest
