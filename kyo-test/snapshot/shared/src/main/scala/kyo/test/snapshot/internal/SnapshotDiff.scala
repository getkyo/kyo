package kyo.test.snapshot.internal

/** Unified-diff renderer for snapshot mismatches.
  *
  * Delegates to `kyo.test.internal.Diff.stringDiff` from `kyo-test-api`. This wrapper provides a stable public name and establishes the
  * direction convention: `stored` is the baseline (what is on disk), `actual` is the newly rendered value.
  */
object SnapshotDiff:

    /** Produces a unified-diff string between two multi-line texts.
      *
      * @param stored
      *   the content read from the existing snapshot file (the baseline)
      * @param actual
      *   the newly rendered value that did not match
      * @return
      *   a human-readable unified diff string
      */
    def render(stored: String, actual: String): String =
        kyo.test.internal.Diff.stringDiff(stored, actual)

end SnapshotDiff
