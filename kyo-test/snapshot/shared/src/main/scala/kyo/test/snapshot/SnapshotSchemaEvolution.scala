package kyo.test.snapshot

import kyo.Frame
import kyo.Maybe
import kyo.test.AssertionFailed

/** Distinct failure for schema-evolution drift: a stored snapshot whose bytes no longer decode via the current `Schema[A]`.
  *
  * Constructed (never thrown) by [[SnapshotTestBase.assertSchemaSnapshot]] on the decode-failure branch, so schema drift is
  * reported with its own message and never conflated with an ordinary value mismatch. Mirrors the sibling [[SnapshotNotFound]]
  * factory: a named single-purpose factory whose diagram is self-describing, with `msg` and `cause` both absent.
  *
  * @param path
  *   the path to the stored snapshot file that failed to decode
  * @param detail
  *   the decode-failure explanation (for Text codecs the caller prepends a unified diff)
  * @param frame
  *   the source location of the failing assertSchemaSnapshot call
  * @see
  *   [[kyo.test.snapshot.SnapshotNotFound]] the first-run sibling failure factory
  * @see
  *   [[kyo.test.AssertionFailed]] the failure type this factory constructs
  */
object SnapshotSchemaEvolution:
    /** Constructs the `AssertionFailed` for a stored snapshot that failed to decode. */
    def apply(path: String, detail: String)(using frame: Frame): AssertionFailed =
        new AssertionFailed(
            s"SnapshotSchemaEvolution: stored snapshot at $path could not be decoded: $detail",
            frame,
            Maybe.Absent,
            Maybe.Absent
        )
end SnapshotSchemaEvolution
