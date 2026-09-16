package kyo

/** One branch, carrying every column `dolt_branches` reports.
  *
  * `dirty` says the branch's working set holds changes that are in no commit yet. A working set belongs to the branch rather than to a
  * connection, so those changes are visible to every session on that branch. The `latest*` fields describe the commit the branch points
  * at, and are absent for a branch created at an empty database.
  */
final case class DoltBranch(
    name: String,
    hash: DoltCommitHash,
    dirty: Boolean,
    latestMessage: Maybe[String],
    latestCommitter: Maybe[String],
    latestCommitterEmail: Maybe[String],
    latestCommitDate: Maybe[Instant],
    latestAuthor: Maybe[String],
    latestAuthorEmail: Maybe[String],
    latestAuthorDate: Maybe[Instant],
    remote: Maybe[String],
    remoteBranch: Maybe[String]
) derives CanEqual:

    /** Whether this branch tracks a branch on a remote. */
    def isTracking: Boolean = remote.isDefined

end DoltBranch
