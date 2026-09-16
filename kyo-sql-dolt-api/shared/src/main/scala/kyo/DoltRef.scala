package kyo

/** A point in a database's history, in the vocabulary Dolt itself accepts.
  *
  * Every version-control operation takes one of these rather than a string, because a branch name and a tag name occupy one namespace on
  * the server and an ancestor is an expression over another ref rather than a name at all. [[render]] is the only place a ref becomes text.
  */
enum DoltRef derives CanEqual:

    /** Wherever the session is pointed now, which is the branch in scope rather than a fixed commit. */
    case Head

    case Branch(name: String)
    case Tag(name: String)
    case Commit(hash: DoltCommitHash)

    /** `generations` commits back along the FIRST parent, which is what Dolt spells with a tilde. At a merge commit that follows the branch
      * that was merged INTO, so `Ancestor(Branch("main"), 1)` is main before the merge.
      */
    case Ancestor(of: DoltRef, generations: Int)

    /** The text Dolt reads for this ref. `Head` renders as `HEAD`, which resolves against the session's current branch, so a ref built once
      * and used from two branches names two different commits; an operation that must be stable takes a [[Commit]].
      */
    def render: String =
        this match
            case Head              => "HEAD"
            case Branch(name)      => name
            case Tag(name)         => name
            case Commit(hash)      => hash
            case Ancestor(of, gen) => if gen <= 0 then of.render else s"${of.render}~$gen"

end DoltRef
