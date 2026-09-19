package kyo

/** Opening the database failed, carrying the path and SQLite's own message.
  *
  * Its own leaf because the neutral connect failure names a host and a port, which a path has neither of.
  */
final case class SqliteOpenFailedException(path: String, reason: String)(using Frame)
    extends SqlConnectionBackendException(
        s"Opening the SQLite database at '$path' failed: $reason"
    )
