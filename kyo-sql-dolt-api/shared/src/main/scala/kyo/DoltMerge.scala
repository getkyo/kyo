package kyo

/** What a merge did, with the server's own account of it.
  *
  * A sum type rather than a failure, because a conflict is an ordinary outcome of merging. The engine forces that as much as taste does:
  * measured against a 2.3.4 server, a conflicting merge under autocommit raises an error and rolls back, while the same merge inside an
  * explicit transaction returns a row saying `conflicts found`. So [[kyo.DoltClient.merge]] runs in a transaction and hands this back.
  */
enum DoltMerge derives CanEqual:

    /** The target already contained every commit being merged, so nothing moved and no commit was created. */
    case UpToDate(message: String)

    /** The target had no commits of its own since the fork, so its pointer moved forward and no merge commit was created. */
    case FastForward(commit: DoltCommit, message: String)

    /** Both sides had commits and they combined cleanly, producing a merge commit with two parents. */
    case Merged(commit: DoltCommit, message: String)

    /** Both sides changed the same rows or the same schema. Nothing is committed and the conflicts sit in the working set.
      *
      * `data` carries the per-table row counts and `schema` the tables whose SHAPE conflicts, both read inside the same transaction. A
      * schema conflict cannot be resolved by choosing a side row by row, which is why it is a separate field rather than another count.
      */
    case Conflicted(data: Chunk[DoltConflictSummary], schema: Chunk[DoltSchemaConflict], message: String)

    /** The server's own summary of what happened, whichever case this is. Named apart from the `message` each case carries because an enum
      * cannot expose one accessor over case fields of that same name.
      */
    def summary: String =
        this match
            case UpToDate(m)         => m
            case FastForward(_, m)   => m
            case Merged(_, m)        => m
            case Conflicted(_, _, m) => m

    /** Whether this merge left work for the caller to resolve. */
    def isConflicted: Boolean =
        this match
            case _: Conflicted => true
            case _             => false

    /** The commit this merge produced, absent when it produced none. Named apart from the `commit` two cases carry, for the reason
      * [[summary]] is.
      */
    def resultingCommit: Maybe[DoltCommit] =
        this match
            case FastForward(c, _) => Present(c)
            case Merged(c, _)      => Present(c)
            case _                 => Absent

    /** How many rows are in conflict across every table, zero on a clean merge. */
    def conflictCount: Long =
        this match
            case Conflicted(data, _, _) => data.map(_.count).sum
            case _                      => 0L

end DoltMerge
