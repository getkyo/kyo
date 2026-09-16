package kyo.internal.sqlite

import kyo.*
import kyo.db.Idiom

/** A NaN reached a bind, and SQLite has no representation for it.
  *
  * Refused rather than bound, because binding it is worse than failing: `sqlite3_bind_double` accepts a NaN, stores NULL, and reports
  * success. A value would become an absent one with nothing red, which is the substitution the value-domain battery exists to catch.
  *
  * The infinities are NOT refused. SQLite holds them, and they read back as `Inf` and `-Inf`; NaN is the one non-finite it cannot carry.
  *
  * Carries [[SqlValueOutOfRange]] so one handler catches this and the codec-side range failures together, the way a caller writing against
  * the neutral surface expects.
  */
final case class SqliteNaNNotStorableException()(using Frame)
    extends SqlRequestBackendException(
        "SQLite cannot store NaN: binding it would succeed and store NULL, turning the value into an absent one. " +
            "The infinities are storable and are not refused here."
    ) with SqlValueOutOfRange

/** A transaction asked for an isolation level SQLite cannot run at.
  *
  * Refused rather than accepted and ignored. SQLite has no isolation vocabulary and no knob that produces READ UNCOMMITTED or READ
  * COMMITTED: measured across every journal mode and both BEGIN forms, a reader inside a transaction repeats its read, so the engine is at
  * least REPEATABLE READ. Accepting the weaker level would be a caller asking for one thing and silently getting another, which is exactly
  * how a lost update happens with nothing red.
  *
  * @param requested
  *   the level the caller named
  * @param actual
  *   the level this engine runs at
  */
final case class SqliteIsolationLevelUnsupportedException(
    requested: SqlClient.IsolationLevel,
    actual: SqlClient.IsolationLevel
)(using Frame)
    extends SqlUnsupportedBackendException(
        s"SQLite cannot run at $requested. It has no isolation vocabulary and always reads at $actual or stronger, so accepting " +
            s"$requested would run the transaction at a different level than was asked for. Name $actual, or name no level at all."
    )

/** An advisory lock was asked for, and SQLite has none.
  *
  * Its concurrency is one writer over the whole database rather than per row or per key, so there is nothing for a named lock to mean. A
  * caller needing mutual exclusion here has it already, by construction.
  */
final case class SqliteAdvisoryLockUnsupportedException(key: Long)(using Frame)
    extends SqlUnsupportedBackendException(
        s"SQLite has no advisory locks, so the lock named $key cannot be taken. Its concurrency is one writer over the whole " +
            "database, which is the mutual exclusion an advisory lock would be asked for."
    )

/** A statement string held more than one statement.
  *
  * Refused rather than half-executed. `sqlite3_prepare_v2` compiles the FIRST statement and leaves the rest, so a driver that ignored the
  * remainder would silently run a prefix of what it was given. Both other engines refuse such a string at the wire, and this keeps SQLite
  * from being the one that quietly does something else.
  */
final case class SqliteMultipleStatementsException(sql: String)(using Frame)
    extends SqlRequestBackendException(
        s"This statement string holds more than one statement, and only the first would run: ${sql.take(200)}"
    )

/** Opening the database failed.
  *
  * Its own leaf because the neutral connect failure names a host and a port, which a path has neither of. What a caller needs here is the
  * path and SQLite's own message: the usual causes are a directory that does not exist, a permission, or a file that is not a database.
  */
final case class SqliteOpenFailedException(path: String, reason: String)(using Frame)
    extends SqlConnectionBackendException(
        s"Opening the SQLite database at '$path' failed: $reason"
    )

/** The dialect id this backend renders and decodes for. */
private[sqlite] object SqliteDialectId:
    val value: Idiom.Id = Idiom.Id("sqlite")
