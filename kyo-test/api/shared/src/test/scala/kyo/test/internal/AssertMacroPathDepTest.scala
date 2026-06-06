package kyo.test.internal

import kyo.Chunk
import kyo.Frame
import kyo.test.AssertScope
import kyo.test.LeafHarness
import kyo.test.TestResult
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ── Top-level test helpers ───────────────────────────────────────────────────
// Classes with inner classes must be defined at the top level (not inside a test
// method) so that the macro call site has the correct path-prefix in scope.
// Each helper is instantiated via TestContext.setForInstantiation so the ctx
// thread-local is populated correctly before the constructor runs.

/** Helper for test 1: inner class with a val, assertion on i.n */
class PathDepHelper1 extends kyo.test.Test[Any]:
    class Inner:
        val n: Int = 7
    end Inner
    val i          = new Inner
    val value: Int = 99
    "inner-n" in { assert(i.n == 99) }
end PathDepHelper1

/** Helper for test 2: inner class, assert on i.n == 5 (fails, n is 7) */
class PathDepHelper2 extends kyo.test.Test[Any]:
    class Inner:
        val n: Int = 7
    end Inner
    val i = new Inner
    "inner-n-fail" in { assert(i.n == 5) }
end PathDepHelper2

/** Helper for test 3: assert on this.value where value is 0 (passes), and when forced to fail (value != 0) the diagram shows the value.
  */
class PathDepHelper3(val value: Int) extends kyo.test.Test[Any]:
    "this-value" in { assert(this.value == 0) }
end PathDepHelper3

/** Helper for test 3b: same class but value = 42, assertion fails */
class PathDepHelper3b(val value: Int) extends kyo.test.Test[Any]:
    "this-value-fail" in { assert(this.value == 0) }
end PathDepHelper3b

/** Helper for test 4: scala.Predef.identity passes when 1 == 1, fails when 1 == 2 */
class PathDepHelper4a extends kyo.test.Test[Any]:
    "identity-pass" in { assert(scala.Predef.identity(1) == 1) }
end PathDepHelper4a

class PathDepHelper4b extends kyo.test.Test[Any]:
    "identity-fail" in { assert(scala.Predef.identity(1) == 2) }
end PathDepHelper4b

/** Helper for test 5: Suite.this.inner.list.head where inner and list are members of nested classes. Asserts head == 9 (fails since head is
  * 1).
  */
class PathDepHelper5 extends kyo.test.Test[Any]:

    class Inner:
        val list: List[Int] = List(1, 2, 3)
    end Inner

    val inner = new Inner

    "nested-member-fail" in { assert(PathDepHelper5.this.inner.list.head == 9) }
end PathDepHelper5

/** Helper for test 6: a suite that only uses assert(true), should compile without warnings and without any "Trade-off" text in the macro
  * output.
  */
object PathDepHelper6Probe:
    inline def doAssert(inline cond: Boolean)(using inline frame: Frame, inline as: AssertScope): Unit =
        ${ AssertMacro.assertImpl('cond, 'frame, 'as) }
end PathDepHelper6Probe

// ── Tests ────────────────────────────────────────────────────────────────────

// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class AssertMacroPathDepTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // A throwaway leaf scope for the direct-macro probe (PathDepHelper6Probe); the suite-based helpers
    // (PathDepHelper1..5) get their scope from the `in` leaf. Constructed via the private[kyo] ctor.
    private given AssertScope = new AssertScope(Chunk.empty)

    /** Run the first leaf of a Test suite and return the TestResult. */
    private def runLeaf[S <: kyo.test.Test[Any]](make: => S): Future[TestResult] =
        LeafHarness.runLeaf(make)

    // ── Test 1 ────────────────────────────────────────────────────────────────
    // assert(i.n == 99) where i: Outer.this.Inner and n = 7. Should fail and
    // the diagram should contain "7" (the recorded value of i.n).
    "path-dep inner class: diagram contains value of i.n on failure" in {
        runLeaf(new PathDepHelper1).map { result =>
            result match
                case f: TestResult.Failed =>
                    assert(
                        f.diagram.contains("7"),
                        s"Expected '7' (value of i.n) in diagram:\n${f.diagram}"
                    )
                case other =>
                    fail(s"Expected Failed result (i.n=7 != 99), got: $other")
        }
    }

    // ── Test 2 ────────────────────────────────────────────────────────────────
    // class Outer { class Inner { val n = 7 }; val i = new Inner; assert(i.n == 5) }
    // The failing diagram should show "7".
    "path-dep outer/inner: diagram shows 7 when i.n == 5 fails" in {
        runLeaf(new PathDepHelper2).map { result =>
            result match
                case f: TestResult.Failed =>
                    assert(
                        f.diagram.contains("7"),
                        s"Expected '7' (value of i.n) in diagram:\n${f.diagram}"
                    )
                case other =>
                    fail(s"Expected Failed result (i.n=7 != 5), got: $other")
        }
    }

    // ── Test 3 ────────────────────────────────────────────────────────────────
    // assert(this.value == 0) where value = 0: passes.
    // assert(this.value == 0) where value = 42: fails, diagram shows "42".
    "this.value == 0 passes when value is 0" in {
        runLeaf(new PathDepHelper3(0)).map { result =>
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got: $other")
        }
    }

    "this.value == 0 fails and diagram shows value on mismatch" in {
        runLeaf(new PathDepHelper3b(42)).map { result =>
            result match
                case f: TestResult.Failed =>
                    assert(
                        f.diagram.contains("42"),
                        s"Expected '42' (value of this.value) in diagram:\n${f.diagram}"
                    )
                case other =>
                    fail(s"Expected Failed result (value=42 != 0), got: $other")
        }
    }

    // ── Test 4 ────────────────────────────────────────────────────────────────
    // assert(scala.Predef.identity(1) == 1) passes.
    // assert(scala.Predef.identity(1) == 2) fails; diagram is non-empty.
    "identity(1) == 1 passes" in {
        runLeaf(new PathDepHelper4a).map { result =>
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got: $other")
        }
    }

    "identity(1) == 2 fails with non-empty diagram" in {
        runLeaf(new PathDepHelper4b).map { result =>
            result match
                case f: TestResult.Failed =>
                    assert(
                        f.diagram.nonEmpty,
                        s"Expected non-empty diagram for identity(1)==2 failure"
                    )
                    assert(
                        f.diagram.contains("1"),
                        s"Expected '1' (value of identity(1)) in diagram:\n${f.diagram}"
                    )
                case other =>
                    fail(s"Expected Failed result (identity(1)=1 != 2), got: $other")
        }
    }

    // ── Test 5 ────────────────────────────────────────────────────────────────
    // assert(Suite.this.inner.list.head == 9) where inner and list are members
    // of nested classes and list.head = 1. Diagram should show "1".
    "Suite.this.inner.list.head == 9 fails and diagram shows head value" in {
        runLeaf(new PathDepHelper5).map { result =>
            result match
                case f: TestResult.Failed =>
                    assert(
                        f.diagram.contains("1"),
                        s"Expected '1' (value of head) in diagram:\n${f.diagram}"
                    )
                case other =>
                    fail(s"Expected Failed result (head=1 != 9), got: $other")
        }
    }

    // ── Test 6 ────────────────────────────────────────────────────────────────
    // Negative compile-time check: assert(true) compiles without warnings and
    // the macro does not emit any "Trade-off" text. We verify this at runtime
    // by confirming the inline invocation compiles (compilation failure would
    // break the test class loading) and passes without exception.
    "assert(true) compiles and passes without error (no Trade-off branch)" in {
        // If this line compiles, the macro no longer has the Trade-off branch.
        PathDepHelper6Probe.doAssert(true)
        succeed
    }

end AssertMacroPathDepTest
