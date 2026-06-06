package kyo.test.snapshot

import kyo.Chunk
import kyo.Render
import kyo.test.AssertScope
import kyo.test.internal.TestContext
import kyo.test.snapshot.SnapshotTest
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ScalaTest bootstrap: kyo-test-snapshot has no KyoTestPlugin; only ScalaTest is available here.
// Tests the path-validation checks in SnapshotTest.assertSnapshot that fire before any file I/O.
class SnapshotTestTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = ExecutionContext.global

    // These tests drive `assertSnapshot` outside the runner (the path-validation throws fire before any
    // AssertScope record), so they supply a standalone scope to satisfy the assert family's using-clause.
    private given AssertScope = new AssertScope(Chunk.empty)

    /** Minimal SnapshotTest subclass for validation testing. The snapshotDir is irrelevant because all tested paths throw before reaching
      * file I/O. The path validation in `assertSnapshot` runs eagerly at the inline call site, throwing before the Kyo computation is
      * produced, so discarding the returned value is sufficient.
      */
    private class ValidationSuite extends SnapshotTest[Any]:
        override protected def snapshotDir: String = "target/snap-validation-test"

        def runSnapshot[A](actual: A, name: String)(using Render[A], kyo.Frame, kyo.test.AssertScope): Unit =
            val _ = assertSnapshot(actual, name)
            ()

    end ValidationSuite

    private def makeSuite(): ValidationSuite =
        val ctx = new TestContext(Chunk(0))
        TestContext.setForInstantiation(ctx)
        new ValidationSuite
    end makeSuite

    // ── Name validation: empty name ────────────────────────────────────────────

    "phase7-leaf-5: assertSnapshot with empty name throws IllegalArgumentException containing 'empty'" in {
        val suite = makeSuite()
        var threw = false
        try suite.runSnapshot(42, "")
        catch
            case e: IllegalArgumentException =>
                threw = true
                assert(
                    e.getMessage.contains("empty"),
                    s"Expected message containing 'empty', got: ${e.getMessage}"
                )
        end try
        assert(threw, "Expected IllegalArgumentException for empty snapshot name")
        Future.successful(succeed)
    }

    // ── Name validation: dot name ─────────────────────────────────────────────

    "phase7-leaf-6: assertSnapshot with name \".\" throws IllegalArgumentException containing \"'.'\"" in {
        val suite = makeSuite()
        var threw = false
        try suite.runSnapshot(42, ".")
        catch
            case e: IllegalArgumentException =>
                threw = true
                assert(
                    e.getMessage.contains("'.'"),
                    s"Expected message containing \"'.'\" , got: ${e.getMessage}"
                )
        end try
        assert(threw, "Expected IllegalArgumentException for snapshot name '.'")
        Future.successful(succeed)
    }

    // ── Name validation: double-dot name ──────────────────────────────────────

    "phase7-leaf-7: assertSnapshot with name \"..\" throws IllegalArgumentException containing \"'..'\"" in {
        val suite = makeSuite()
        var threw = false
        try suite.runSnapshot(42, "..")
        catch
            case e: IllegalArgumentException =>
                threw = true
                assert(
                    e.getMessage.contains("'..'"),
                    s"Expected message containing \"'..'\" , got: ${e.getMessage}"
                )
        end try
        assert(threw, "Expected IllegalArgumentException for snapshot name '..'")
        Future.successful(succeed)
    }

    // ── Name validation: space in name ─────────────────────────────────────

    "phase7-leaf-8: assertSnapshot with name containing a space throws IllegalArgumentException containing 'space'" in {
        val suite         = makeSuite()
        val nameWithSpace = "snap bad"
        var threw         = false
        try suite.runSnapshot(42, nameWithSpace)
        catch
            case e: IllegalArgumentException =>
                threw = true
                assert(
                    e.getMessage.contains("space"),
                    s"Expected message containing 'space', got: ${e.getMessage}"
                )
        end try
        assert(threw, "Expected IllegalArgumentException for snapshot name containing a space")
        Future.successful(succeed)
    }

end SnapshotTestTest
