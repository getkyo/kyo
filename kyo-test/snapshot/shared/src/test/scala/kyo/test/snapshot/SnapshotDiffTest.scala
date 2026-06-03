package kyo.test.snapshot

import kyo.test.snapshot.internal.SnapshotDiff
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Tests for SnapshotDiff (pure logic, no file I/O). All assertions are synchronous; uses ScalaTest directly. */
class SnapshotDiffTest extends AnyFunSuite with NonImplicitAssertions:

    test("unified diff for differing snapshots") {
        val diff = SnapshotDiff.render("foo\nbar", "foo\nbaz")
        assert(diff.contains("- bar"), s"Expected '- bar' in diff:\n$diff")
        assert(diff.contains("+ baz"), s"Expected '+ baz' in diff:\n$diff")
    }

end SnapshotDiffTest
