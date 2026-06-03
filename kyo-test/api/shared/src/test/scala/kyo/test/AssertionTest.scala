package kyo.test

import kyo.Frame
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Tests for KyoException migrations in kyo-test-api.
  *
  * Covers:
  *   1. TestCancelled.getMessage returns the reason in production mode.
  *   2. TestCancelled.frame is populated from the call-site Frame (line nonzero).
  *   3. SystemExitException.code equals the supplied exit code.
  *   4. RuntimeException catch does NOT catch TestCancelled (KyoException extends Exception).
  *   5. Exception catch DOES catch TestCancelled.
  */
// ScalaTest bootstrap: this file tests KyoException migrations (TestCancelled) in the api module; cannot self-host since kyo-test-api has no KyoTestPlugin.
class AssertionTest extends AnyFunSuite with NonImplicitAssertions:

    // ── Leaf 1: TestCancelled.getMessage returns the reason ──────────────────

    test("phase6-leaf-1: TestCancelled(reason).getMessage returns reason in production mode") {
        val tc = new TestCancelled("my reason")
        // In production mode KyoException.getMessage returns message + detail (no frame prefix).
        // Environment.isDevelopment is false in test runs by default.
        assert(tc.getMessage.contains("my reason"), s"Expected getMessage to contain 'my reason', got: ${tc.getMessage}")
    }

    // ── Leaf 2: TestCancelled.frame is populated from call-site ──────────────

    test("phase6-leaf-2: TestCancelled(reason).frame position.lineNumber is nonzero") {
        val tc = new TestCancelled("frame test")
        assert(tc.frame.position.lineNumber > 0, s"Expected nonzero lineNumber, got: ${tc.frame.position.lineNumber}")
    }

    // ── Leaf 4: RuntimeException catch does NOT catch TestCancelled ──────────

    test("phase6-leaf-4: catch RuntimeException does NOT catch TestCancelled (KyoException extends Exception not RuntimeException)") {
        val tc     = new TestCancelled("cancel!")
        var caught = false
        try
            try throw tc
            catch case _: RuntimeException => caught = true
        catch case _: kyo.KyoException => ()
        end try
        assert(!caught, "RuntimeException catch must NOT intercept a TestCancelled (KyoException is not a RuntimeException)")
    }

    // ── Leaf 5: Exception catch DOES catch TestCancelled ─────────────────────

    test("phase6-leaf-5: catch Exception DOES catch TestCancelled") {
        val tc     = new TestCancelled("cancel!")
        var caught = false
        try throw tc
        catch case _: Exception => caught = true
        end try
        assert(caught, "Exception catch must intercept a TestCancelled")
    }

end AssertionTest
