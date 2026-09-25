package kyo

/** A merge or pull conflicted inside a nested transaction, where DoltLite restores the savepoint and discards the conflicts
  * instead of keeping them to resolve. DoltLite keeps a merge's conflicts only in the outermost transaction, so the merge has to
  * run there to answer [[Dolt.Merge.Conflicted]].
  *
  * @param report
  *   the engine's own report, which names how many rows conflicted
  */
final case class DoltLiteNestedMergeConflictException(report: String)(using Frame)
    extends SqlRequestBackendException(
        s"$report DoltLite discards a merge's conflicts inside a nested transaction, so they cannot be inspected or resolved " +
            "there. Merge in the outermost transaction, or outside any, to have the conflicts answered."
    )
