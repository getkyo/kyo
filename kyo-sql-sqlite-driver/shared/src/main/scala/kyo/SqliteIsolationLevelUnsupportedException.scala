package kyo

/** A transaction asked for an isolation level SQLite cannot run at.
  *
  * Refused rather than accepted and ignored: SQLite has no knob that produces READ UNCOMMITTED or READ COMMITTED, and a reader inside a
  * transaction repeats its read under every journal mode and both BEGIN forms, so accepting the weaker level would run the transaction at a
  * different level than the caller named.
  */
final case class SqliteIsolationLevelUnsupportedException(
    requested: SqlClient.IsolationLevel,
    actual: SqlClient.IsolationLevel
)(using Frame)
    extends SqlUnsupportedBackendException(
        s"SQLite cannot run at $requested. It has no isolation vocabulary and always reads at $actual or stronger, so accepting " +
            s"$requested would run the transaction at a different level than was asked for. Name $actual, or name no level at all."
    )
