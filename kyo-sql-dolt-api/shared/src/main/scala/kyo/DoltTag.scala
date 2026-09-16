package kyo

/** One tag, as `dolt_tags` reports it.
  *
  * A tag names a commit permanently where a branch name moves, though the two share one namespace on the server.
  */
final case class DoltTag(
    name: String,
    hash: DoltCommitHash,
    tagger: String,
    email: String,
    date: Instant,
    message: Maybe[String]
) derives CanEqual
