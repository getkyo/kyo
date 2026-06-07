package kyo.test.snapshot

import java.nio.file.Files
import java.nio.file.Paths
import kyo.Chunk
import kyo.Maybe
import kyo.Render
import kyo.test.AssertionFailed
import kyo.test.AssertScope
import kyo.test.internal.TestContext
import kyo.test.snapshot.SnapshotTest
import kyo.test.snapshot.internal.SnapshotStore
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ── helpers ─────────────────────────────────────────────────────────────

/** Concrete SnapshotTest[Any] subclass for testing, exposing assertSnapshot as package-accessible. */
private class TestSnapshotSuite(dir: String, update: Boolean = false) extends SnapshotTest[Any]:
    override protected def snapshotDir: String         = dir
    override protected def snapshotUpdateMode: Boolean = update

    def runSnapshot[A](actual: A, name: String)(using Render[A], kyo.Frame, AssertScope): Unit =
        assertSnapshot(actual, name): Unit

end TestSnapshotSuite

private def installContexts(): Unit =
    TestContext.setForInstantiation(new TestContext(Chunk.empty))

// ── SnapshotStoreTest ────────────────────────────────────────────────────

/** Tests for SnapshotStore file I/O. */
class SnapshotStoreTest extends AsyncFreeSpec with NonImplicitAssertions:

    private def tmpDir(): String =
        Files.createTempDirectory("kyo-snap-test").toAbsolutePath.toString

    "first write creates the snapshot file" in {
        val dir  = tmpDir()
        val path = s"$dir/MySuite/mytest.snap"
        SnapshotStore.write(path, "hello")
        assert(Files.exists(Paths.get(path)))
        assert(Files.readString(Paths.get(path)).stripTrailing() == "hello")
        Future.successful(succeed)
    }

    "read returns Maybe.empty for missing snapshot" in {
        val dir  = tmpDir()
        val path = s"$dir/nope.snap"
        assert(SnapshotStore.read(path) == Maybe.Absent)
        Future.successful(succeed)
    }

    "read returns Maybe.present with content for existing" in {
        val dir  = tmpDir()
        val path = s"$dir/existing.snap"
        SnapshotStore.write(path, "content")
        val result = SnapshotStore.read(path)
        assert(result.isDefined)
        result match
            case Maybe.Present(v) => assert(v.stripTrailing() == "content")
            case Maybe.Absent     => fail("Expected Present")
        end match
        Future.successful(succeed)
    }

    "update mode overwrites" in {
        val dir  = tmpDir()
        val path = s"$dir/overwrite.snap"
        SnapshotStore.write(path, "v1")
        SnapshotStore.write(path, "v2")
        val result = SnapshotStore.read(path)
        result match
            case Maybe.Present(v) => assert(v.stripTrailing() == "v2")
            case Maybe.Absent     => fail("Expected Present")
        end match
        Future.successful(succeed)
    }

end SnapshotStoreTest

// ── SnapshotSelfTest ──────────────────────────────────────────────────────

/** Self-tests for the SnapshotTest[S] DSL, verifying assertSnapshot behaviors end-to-end. */
// ScalaTest bootstrap: this file tests SnapshotTest DSL itself; cannot self-host using the framework-under-test.
class SnapshotSelfTest extends AsyncFreeSpec with NonImplicitAssertions:

    // These tests drive `assertSnapshot` outside the runner, so they supply a standalone scope to
    // satisfy the assert family's using-clause; the snapshot throws fire on the synchronous path.
    private given AssertScope = new AssertScope(Chunk.empty)

    private def tmpDir(): String =
        Files.createTempDirectory("kyo-snap-self-test").toAbsolutePath.toString

    "first run with no snapshot fails with SnapshotNotFound and writes the proposed snapshot" in {
        val dir = tmpDir()
        installContexts()
        val suite = new TestSnapshotSuite(dir)
        try
            suite.runSnapshot(42, "mysnap")
            fail("Expected AssertionFailed on first run")
        catch
            case e: AssertionFailed =>
                assert(e.getMessage.contains("SnapshotNotFound"), s"Expected SnapshotNotFound in: ${e.getMessage}")
                val snapPath = s"$dir/TestSnapshotSuite/mysnap.snap"
                assert(
                    Files.exists(Paths.get(snapPath)),
                    s"Proposed snapshot not written to: $snapPath"
                )
            case t: Throwable =>
                fail(s"Expected AssertionFailed, got ${t.getClass.getName}: ${t.getMessage}")
        end try
        Future.successful(succeed)
    }

    "subsequent run with matching snapshot passes" in {
        val dir      = tmpDir()
        val snapPath = s"$dir/TestSnapshotSuite/mysnap.snap"
        SnapshotStore.write(snapPath, "42")
        installContexts()
        val suite = new TestSnapshotSuite(dir)
        suite.runSnapshot(42, "mysnap")
        Future.successful(succeed)
    }

    "subsequent run with mismatch fails with diff" in {
        val dir      = tmpDir()
        val snapPath = s"$dir/TestSnapshotSuite/mysnap.snap"
        SnapshotStore.write(snapPath, "100")
        installContexts()
        val suite = new TestSnapshotSuite(dir)
        try
            suite.runSnapshot(42, "mysnap")
            fail("Expected AssertionFailed on mismatch")
        catch
            case e: AssertionFailed =>
                val diag = e.getMessage
                assert(
                    diag.contains("- 100") || diag.contains("100"),
                    s"Expected '100' in diff:\n$diag"
                )
                assert(
                    diag.contains("+ 42") || diag.contains("42"),
                    s"Expected '42' in diff:\n$diag"
                )
            case t: Throwable =>
                fail(s"Expected AssertionFailed, got ${t.getClass.getName}: ${t.getMessage}")
        end try
        Future.successful(succeed)
    }

    "update mode rewrites mismatched snapshot, env KYO_TEST_SNAPSHOT=update" in {
        val dir      = tmpDir()
        val snapPath = s"$dir/TestSnapshotSuite/mysnap.snap"
        SnapshotStore.write(snapPath, "100")
        installContexts()
        val suite = new TestSnapshotSuite(dir, update = true)
        suite.runSnapshot(42, "mysnap")
        val stored = SnapshotStore.read(snapPath)
        stored match
            case Maybe.Present(v) => assert(v.stripTrailing() == "42", s"Expected '42', got '$v'")
            case Maybe.Absent     => fail("Snapshot file missing after update")
        end match
        Future.successful(succeed)
    }

    "snapshotDir override changes the storage location" in {
        val dir1 = tmpDir()
        val dir2 = tmpDir()

        installContexts()
        val suite1 = new TestSnapshotSuite(dir1)
        // First run writes proposed snapshot to dir1 for 'snap1'
        try suite1.runSnapshot(1, "snap1")
        catch case _: AssertionFailed => ()

        installContexts()
        val suite2 = new TestSnapshotSuite(dir2)
        // First run writes proposed snapshot to dir2 for 'snap2'
        try suite2.runSnapshot(2, "snap2")
        catch case _: AssertionFailed => ()

        // Verify dir1 has snap1 but not snap2
        assert(
            Files.exists(Paths.get(s"$dir1/TestSnapshotSuite/snap1.snap")),
            s"snap1 not in dir1"
        )
        assert(
            !Files.exists(Paths.get(s"$dir1/TestSnapshotSuite/snap2.snap")),
            s"snap2 incorrectly written to dir1"
        )
        // Verify dir2 has snap2 but not snap1
        assert(
            Files.exists(Paths.get(s"$dir2/TestSnapshotSuite/snap2.snap")),
            s"snap2 not in dir2"
        )
        assert(
            !Files.exists(Paths.get(s"$dir2/TestSnapshotSuite/snap1.snap")),
            s"snap1 incorrectly written to dir2"
        )
        Future.successful(succeed)
    }

end SnapshotSelfTest
