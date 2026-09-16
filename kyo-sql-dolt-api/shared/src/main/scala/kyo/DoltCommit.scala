package kyo

/** One commit in a database's history.
  *
  * Shared by every versioned backend, so the fields an engine may not record are [[kyo.Maybe]]: the embedded engine keeps no author apart
  * from the committer, signs nothing, and keeps no total ordering. [[parents]] and [[refs]] are always answerable, so an empty `parents`
  * means the initial commit and nothing else.
  */
final case class DoltCommit(
    hash: DoltCommitHash,
    message: String,
    /** Who wrote the change, where the engine records that apart from who committed it. */
    author: Maybe[String],
    authorEmail: Maybe[String],
    authorDate: Maybe[Instant],
    committer: String,
    committerEmail: String,
    date: Instant,
    /** Empty only at the initial commit, and holding two entries at a merge. */
    parents: Chunk[DoltCommitHash],
    /** Branches and tags pointing at this commit, empty when nothing names it. */
    refs: Chunk[String],
    /** The GPG signature, absent on an unsigned commit and on an engine with no signing. */
    signature: Maybe[String],
    /** The engine's own total ordering over the commit graph, absent where it keeps none. */
    order: Maybe[Long]
) derives CanEqual:

    /** Whether this commit joined two histories. */
    def isMerge: Boolean = parents.size > 1

    /** Whether this is the start of history. */
    def isRoot: Boolean = parents.isEmpty

end DoltCommit
