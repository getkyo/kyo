package kyo.test.snapshot

import kyo.Chunk
import kyo.Render
import kyo.test.AssertionFailed
import kyo.test.AssertScope
import kyo.test.internal.TestContext
import kyo.test.snapshot.SnapshotTest
import kyo.test.snapshot.internal.SnapshotStore
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Regression tests for the `snapshotUpdateMode` protected-def contract.
  *
  * All assertions are synchronous (file I/O + try/catch); uses ScalaTest directly.
  *
  * Covers:
  *   1. Default (no env override): `snapshotUpdateMode` returns false; missing snapshot raises SnapshotNotFound.
  *   2. Subclass override to true: `assertSnapshot` writes and passes without touching env vars.
  *   3. Property-based override: a subclass reading `java.lang.System.getProperty` reflects a set system property.
  *   4. Two concurrent SnapshotTest instances with different overrides do not interfere.
  */
class SnapshotUpdateModeTest extends AnyFunSuite with NonImplicitAssertions:

    // ── helpers ──────────────────────────────────────────────────────────────

    // These tests drive `assertSnapshot` outside the runner, so they supply a standalone scope to
    // satisfy the assert family's using-clause; the snapshot throws fire on the synchronous path.
    private given AssertScope = new AssertScope(Chunk.empty)

    private def tmpDir(): String =
        s"target/snap-update-mode-test-${java.lang.System.nanoTime()}"

    private def installContexts(): Unit =
        TestContext.setForInstantiation(new TestContext(Chunk.empty))

    /** Minimal SnapshotTest[Any] subclass; update mode controlled by `update` parameter. */
    private class FixedMode(dir: String, update: Boolean) extends SnapshotTest[Any]:
        override protected def snapshotDir: String         = dir
        override protected def snapshotUpdateMode: Boolean = update

        def updateModeValue: Boolean = snapshotUpdateMode

        def run[A](actual: A, name: String)(using Render[A], kyo.Frame, AssertScope): Unit =
            assertSnapshot(actual, name): Unit

    end FixedMode

    /** Subclass that reads a JVM system property so tests can simulate the env-var path portably. */
    private class PropMode(dir: String) extends SnapshotTest[Any]:
        override protected def snapshotDir: String = dir
        override protected def snapshotUpdateMode: Boolean =
            java.lang.System.getProperty("KYO_TEST_SNAPSHOT_PROP") == "update"

        def updateModeValue: Boolean = snapshotUpdateMode

        def run[A](actual: A, name: String)(using Render[A], kyo.Frame, AssertScope): Unit =
            assertSnapshot(actual, name): Unit

    end PropMode

    test("default snapshotUpdateMode is false and missing snapshot fails with SnapshotNotFound") {
        val dir = tmpDir()
        installContexts()
        val suite = new FixedMode(dir, update = false)
        assert(!suite.updateModeValue, "snapshotUpdateMode should be false by default override")
        var threw = false
        try suite.run(42, "absent-snap")
        catch
            case e: AssertionFailed =>
                threw = true
                assert(
                    e.getMessage.contains("SnapshotNotFound"),
                    s"Expected SnapshotNotFound in message: ${e.getMessage}"
                )
            case t: Throwable =>
                fail(s"Expected AssertionFailed, got ${t.getClass.getName}: ${t.getMessage}")
        end try
        assert(threw, "Expected AssertionFailed to be thrown for missing snapshot")
    }

    test("override snapshotUpdateMode=true writes the snapshot and passes") {
        val dir      = tmpDir()
        val snapPath = s"$dir/FixedMode/my-snap.snap"
        installContexts()
        val suite = new FixedMode(dir, update = true)
        assert(suite.updateModeValue, "snapshotUpdateMode should be true in FixedMode(update=true)")
        suite.run(99, "my-snap")
        val stored = SnapshotStore.read(snapPath)
        stored match
            case kyo.Maybe.Present(v) =>
                assert(v.stripTrailing() == "99", s"Expected '99', got '$v'")
            case kyo.Maybe.Absent =>
                fail(s"Snapshot was not written to: $snapPath")
        end match
    }

    test("property-based override reflects KYO_TEST_SNAPSHOT_PROP system property") {
        val dir     = tmpDir()
        val propKey = "KYO_TEST_SNAPSHOT_PROP"
        java.lang.System.clearProperty(propKey): Unit
        installContexts()
        val suiteOff = new PropMode(dir)
        assert(!suiteOff.updateModeValue, "should be false when property is absent")
        java.lang.System.setProperty(propKey, "update"): Unit
        try
            installContexts()
            val suiteOn = new PropMode(dir)
            assert(suiteOn.updateModeValue, "should be true when KYO_TEST_SNAPSHOT_PROP=update")
        finally
            java.lang.System.clearProperty(propKey): Unit
        end try
    }

    test("two SnapshotTest instances with different overrides do not interfere") {
        val dir1 = tmpDir()
        val dir2 = tmpDir() + "-b"

        installContexts()
        val on = new FixedMode(dir1, update = true)
        installContexts()
        val off = new FixedMode(dir2, update = false)

        assert(on.updateModeValue, "on.snapshotUpdateMode should be true")
        assert(!off.updateModeValue, "off.snapshotUpdateMode should be false")

        on.run(7, "shared-snap")
        val stored = SnapshotStore.read(s"$dir1/FixedMode/shared-snap.snap")
        stored match
            case kyo.Maybe.Present(v) =>
                assert(v.stripTrailing() == "7", s"Expected '7' in dir1, got '$v'")
            case kyo.Maybe.Absent =>
                fail(s"Snapshot not written to dir1")
        end match

        var threw = false
        try off.run(7, "shared-snap")
        catch
            case e: AssertionFailed =>
                threw = true
                assert(e.getMessage.contains("SnapshotNotFound"), s"Expected SnapshotNotFound: ${e.getMessage}")
            case t: Throwable =>
                fail(s"Expected AssertionFailed from off, got ${t.getClass.getName}: ${t.getMessage}")
        end try
        assert(threw, "off suite should have thrown AssertionFailed")
    }

end SnapshotUpdateModeTest
