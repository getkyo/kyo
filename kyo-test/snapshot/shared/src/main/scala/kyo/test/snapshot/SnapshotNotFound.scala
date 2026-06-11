package kyo.test.snapshot

import kyo.Frame
import kyo.Maybe
import kyo.test.AssertionFailed

/** Thrown when assertSnapshot is called for the first time and no stored snapshot exists.
  *
  * The proposed snapshot is written to disk before this exception is thrown. The user should review the proposed file and re-run the tests
  * to confirm the snapshot is correct. On subsequent runs the stored snapshot will be compared against the newly rendered value.
  *
  * Wraps an `AssertionFailed` with the SnapshotNotFound message as its diagram so the kyo-test runner records it as a `TestResult.Failed`
  * with the full message visible in output.
  *
  * @param path
  *   the path to the proposed snapshot file that was written
  * @param frame
  *   the source location of the failing assertSnapshot call
  * @see
  *   [[kyo.test.snapshot.SnapshotTest]] the suite base class whose assertSnapshot method produces this exception
  * @see
  *   [[kyo.test.AssertionFailed]] the exception type returned by SnapshotNotFound.apply
  * @see
  *   [[kyo.test.TestResult]] the leaf outcome enum; catching this exception produces the `Failed` case
  */
object SnapshotNotFound:
    /** Constructs the `AssertionFailed` that represents a first-run snapshot miss. */
    def apply(path: String)(using frame: Frame): AssertionFailed =
        new AssertionFailed(
            s"SnapshotNotFound: wrote proposed snapshot to $path; review and re-run",
            frame,
            Maybe.Absent,
            Maybe.Absent
        )
end SnapshotNotFound
