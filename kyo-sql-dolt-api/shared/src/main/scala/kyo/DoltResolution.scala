package kyo

/** Which side of a conflict to keep when resolving a table wholesale.
  *
  * Wholesale is the only form offered. Keeping some rows from each side is an ordinary UPDATE against the conflict table followed by
  * resolving what remains.
  */
enum DoltResolution derives CanEqual:

    /** Keep the branch being merged INTO, discarding the incoming change for every conflicted row. */
    case Ours

    /** Keep the branch being merged FROM, discarding the local change for every conflicted row. */
    case Theirs

    /** The word `DOLT_CONFLICTS_RESOLVE` reads for this side. */
    def flag: String =
        this match
            case Ours   => "--ours"
            case Theirs => "--theirs"

end DoltResolution
