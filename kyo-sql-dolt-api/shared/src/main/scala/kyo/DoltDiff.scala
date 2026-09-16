package kyo

/** One changed row, with the commits it changed between.
  *
  * The table's own columns arrive twice, prefixed `from_` and `to_`, and stay in [[row]] for the caller to decode against their own type,
  * because their shape is the shape of the table being diffed. [[fromCommit]] and [[toCommit]] echo the refs the diff was ASKED for rather
  * than resolving them, so a diff taken between `HEAD~1` and `HEAD` reports those spellings; the dates pin the actual commits.
  */
final case class DoltDiff(
    kind: DoltDiff.Kind,
    fromCommit: String,
    fromCommitDate: Maybe[Instant],
    toCommit: String,
    toCommitDate: Maybe[Instant],
    row: SqlRow
)

object DoltDiff:

    /** What happened to one row between the two commits. */
    enum Kind derives CanEqual:
        case Added, Modified, Removed

        /** The server's own word for this kind. */
        def label: String =
            this match
                case Added    => "added"
                case Modified => "modified"
                case Removed  => "removed"
    end Kind

    object Kind:
        /** Reads the server's `diff_type` column, which has exactly these three values. */
        def parse(value: String): Maybe[Kind] =
            value match
                case "added"    => Present(Kind.Added)
                case "modified" => Present(Kind.Modified)
                case "removed"  => Present(Kind.Removed)
                case _          => Absent
    end Kind

end DoltDiff
