package kyo

/** How far a reset reaches, in git's own three-way vocabulary.
  *
  * The distinction is which of the two places a change can sit gets rewound: the staged set that a commit would capture, and the working
  * set the session reads and writes. [[Hard]] is how uncommitted work is lost, so the mode is named at every call rather than defaulted to
  * the destructive one.
  */
enum DoltResetMode derives CanEqual:

    /** Moves the branch pointer and unstages everything, leaving the working set alone. Nothing a caller wrote is lost. */
    case Mixed

    /** Moves the branch pointer and nothing else, so staged and working changes both survive. */
    case Soft

    /** Moves the branch pointer and DISCARDS both the staged set and the working set. Uncommitted work is gone. */
    case Hard

    /** The flag `DOLT_RESET` reads for this mode, absent for the one it spells by taking no flag at all. `--mixed` is not a word this
      * server knows, measured: it answers ``unknown option `mixed'``.
      */
    def flag: Maybe[String] =
        this match
            case Mixed => Absent
            case Soft  => Present("--soft")
            case Hard  => Present("--hard")

end DoltResetMode
