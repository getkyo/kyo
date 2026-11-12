package kyo

/** A merge or pull conflicted inside a savepoint, where DoltLite discards the conflicts instead of retaining them for
  * resolution. Run the merge in the outer transaction, or outside one, to receive [[Dolt.Merge.Conflicted]].
  *
  * @param report
  *   the engine's conflict report
  */
final case class DoltLiteNestedMergeConflictException(report: String)(using Frame)
    extends SqlRequestBackendException(
        s"$report DoltLite discards conflicts inside a nested transaction. Merge in the outermost transaction, or outside one, " +
            "to inspect the conflicts."
    )
