package kyo.test

import kyo.Frame
import kyo.Maybe
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Tests for [[AssertionFailed.make]] and `getCause` semantics: 3 leaves.
  */
// ScalaTest bootstrap: this file tests AssertionFailed internals; cannot self-host using the framework-under-test.
class AssertionFailedMakeTest extends AnyFunSuite with NonImplicitAssertions:

    given frame: Frame = summon[Frame]

    // ── Test 15: getCause == null when cause is Absent ────────────────────────────────────────

    test("test-15: new AssertionFailed with Absent cause: getCause returns null (Java semantic preserved)") {
        val af = new AssertionFailed("d", frame, Maybe.empty, Maybe.empty)
        assert(af.getCause == null, s"expected null getCause, got ${af.getCause}")
    }

    // ── Test 16: getCause eq t when cause is Present ──────────────────────────────────────────

    test("test-16: new AssertionFailed with Present cause: getCause eq the provided throwable") {
        val t  = new RuntimeException("root")
        val af = new AssertionFailed("d", frame, Maybe.empty, Maybe(t))
        assert(af.getCause eq t, s"expected getCause eq t, got ${af.getCause}")
    }

    // ── Test 17: AssertionFailed.make produces correct getCause ───────────────────────────────

    test("test-17: AssertionFailed.make(d, frame, Absent, Maybe(t)).getCause eq t") {
        val t  = new RuntimeException("cause")
        val af = AssertionFailed.make("d", frame, Maybe.empty, Maybe(t))
        assert(af.getCause eq t, s"expected getCause eq t, got ${af.getCause}")
    }

end AssertionFailedMakeTest
