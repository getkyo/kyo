package kyo

/** A statement string held more than one statement.
  *
  * Refused rather than half-executed: `sqlite3_prepare_v2` compiles the FIRST statement and leaves the rest, so ignoring the remainder
  * would silently run a prefix of what was given.
  */
final case class SqliteMultipleStatementsException(sql: String)(using Frame)
    extends SqlRequestBackendException(
        s"This statement string holds more than one statement, and only the first would run: ${sql.take(200)}"
    )
