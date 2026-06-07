package kyo.test.snapshot

import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Maybe
import kyo.Scope
import kyo.kernel.<
import kyo.test.TestResult
import kyo.test.internal.TestContext
import kyo.test.runner.TestRunner
import kyo.test.snapshot.internal.SnapshotStore
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ── Fixture suites ─────────────────────────────────────────────────────────────────────────────

/** A SnapshotTestBase[Any] suite with a single assertSnapshot leaf writing to a temp dir.
  *
  * Extends SnapshotTestBase (marker-free) so it is not discovered as a standalone suite on Scala Native via reflective
  * instantiation. It is run internally by SnapshotReparamTest via TestRunner.runToFuture.
  */
class STDiscoverySuite extends SnapshotTestBase[Any]:
    override protected def snapshotDir: String = STDiscoverySuite.snapDir
    "my-snapshot" in assertSnapshot(42, "answer")
end STDiscoverySuite

object STDiscoverySuite:
    val snapDir: String =
        java.nio.file.Files.createTempDirectory("kyo-st-next-discovery").toAbsolutePath.toString

/** A SnapshotTestBase[Any] suite whose snapshot file contains "99" to trigger a mismatch failure for the value 42 (rendered "42"). Used for
  * Leaf 5 (mismatch failure).
  *
  * Extends SnapshotTestBase (marker-free) so it is not discovered as a standalone suite on Scala Native via reflective
  * instantiation. It is run internally by SnapshotReparamTest via TestRunner.runToFuture.
  */
class STMismatchSuite extends SnapshotTestBase[Any]:
    override protected def snapshotDir: String = STMismatchSuite.snapDir
    "mismatch-snap" in assertSnapshot(42, "answer")
end STMismatchSuite

object STMismatchSuite:
    val snapDir: String =
        val tmp      = java.nio.file.Files.createTempDirectory("kyo-st-next-mismatch").toAbsolutePath.toString
        val snapPath = s"$tmp/${classOf[STMismatchSuite].getSimpleName.stripSuffix("$")}/answer.snap"
        SnapshotStore.write(snapPath, "99")
        tmp
    end snapDir
end STMismatchSuite

// ── Helpers ────────────────────────────────────────────────────────────────────────────────────

private def installSnapshotContext(next: TestContext): Unit =
    TestContext.setForInstantiation(next)

// ── SnapshotReparamTest (pins the SnapshotTest[S] effect-row parameterization) ──────────────────

/** Tests for the SnapshotTest[S] reparameterization: assertSnapshot registers and discharges leaves under the parameterized effect row.
  *
  * ScalaTest AsyncFreeSpec: this file tests the kyo.test.snapshot.SnapshotTest[S] DSL surface; it cannot self-host.
  */
class SnapshotReparamTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // ── Leaf 4: assertsnapshot-registers-kyo-leaf (registration under discovery) ──────────────

    "assertsnapshot-registers-kyo-leaf: assertSnapshot registers a leaf under the new Kyo body row" in {
        val ctx = new TestContext(Chunk(0), discovery = true)
        installSnapshotContext(ctx)
        val _ = new STDiscoverySuite
        ctx.signalPastEnd()
        ctx.peekRegisteredLeaf match
            case Maybe.Present((path, result)) =>
                assert(path.nonEmpty, "path should be non-empty")
                assert(path.last == "my-snapshot", s"leaf name should be 'my-snapshot', got: ${path.last}")
                result match
                    case _: TestResult.Passed => succeed
                    case other                => fail(s"Expected Passed discovery marker, got $other")
            case Maybe.Absent =>
                fail("Expected a leaf result but got Absent")
        end match
        Future.successful(succeed)
    }

    "assertsnapshot-registers-kyo-leaf: peekWasGroup is false for an assertSnapshot leaf" in {
        val ctx = new TestContext(Chunk(0), discovery = true)
        installSnapshotContext(ctx)
        val _ = new STDiscoverySuite
        ctx.signalPastEnd()
        assert(!ctx.peekWasGroup, "assertSnapshot leaf should not be a group")
        Future.successful(succeed)
    }

    // ── Leaf 5: assertsnapshot-mismatch-failure (execution via runner) ────────────────────────

    "assertsnapshot-mismatch-failure: mismatch assertSnapshot records TestResult.Failed with diff diagram" in {
        TestRunner.runToFuture(classOf[STMismatchSuite]).map { report =>
            assert(report.suiteReports.nonEmpty, "Expected at least one suite report")
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected at least one leaf result")
            val (_, result) = allResults.head
            result match
                case f: TestResult.Failed =>
                    // The diagram holds the SnapshotDiff.render output (unified diff); "Snapshot mismatch"
                    // lives in AssertionFailed.msg which flows to the reporter hint, not the diagram.
                    // We verify the diff contains the stored value "99" and the actual value "42".
                    assert(f.diagram.contains("99"), s"Expected stored value '99' in diagram, got: ${f.diagram}")
                    assert(f.diagram.contains("42"), s"Expected actual value '42' in diagram, got: ${f.diagram}")
                    succeed
                case other => fail(s"Expected TestResult.Failed, got $other")
            end match
        }
    }

end SnapshotReparamTest
