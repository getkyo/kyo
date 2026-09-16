package kyo

/** How many rows of one table are in conflict, as `dolt_conflicts` reports it.
  *
  * A count rather than the rows: they live in `dolt_conflicts_<table>`, whose columns are the conflicted table's own prefixed `base_`,
  * `our_` and `their_`, so their shape differs per table and cannot be one type here.
  */
final case class DoltConflictSummary(table: String, count: Long) derives CanEqual:

    /** The table holding this conflict's rows. */
    def conflictTable: String = s"dolt_conflicts_$table"

end DoltConflictSummary
