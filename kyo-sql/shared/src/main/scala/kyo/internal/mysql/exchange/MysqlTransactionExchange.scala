package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlException
import kyo.SqlIsolationLevel
import kyo.internal.mysql.*

/** Simple-query (COM_QUERY) round-trips for MySQL transaction control commands.
  *
  * MySQL accepts standard SQL transaction syntax: `START TRANSACTION`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `RELEASE SAVEPOINT`, and
  * `ROLLBACK TO SAVEPOINT`. All are sent via [[SimpleQueryExchange.run]] and their responses (OK or ERR) are decoded there.
  *
  * ==MySQL InnoDB DDL implicit-commit caveat==
  *
  * InnoDB performs an implicit `COMMIT` before and after DDL statements (`CREATE TABLE`, `ALTER TABLE`, `DROP TABLE`, etc.). A DDL
  * statement inside a `transaction { ... }` block will commit any pending data modifications and leave the transaction effectively ended.
  * Subsequent DML and the explicit `COMMIT` / `ROLLBACK` from the framework will still be sent, but they will operate on a new implicit
  * transaction.
  *
  * This is a MySQL/InnoDB limitation. kyo-sql does NOT attempt to detect implicit commits, it is the caller's responsibility to avoid DDL
  * inside transactions if true atomicity is required.
  *
  * Reference: dev.mysql.com/doc/refman/8.0/en/implicit-commit.html
  */
private[kyo] object MysqlTransactionExchange:

    /** Maps [[SqlIsolationLevel]] to the MySQL SQL keyword phrase. */
    private def isolationClause(level: SqlIsolationLevel): String = level match
        case SqlIsolationLevel.ReadUncommitted => "READ UNCOMMITTED"
        case SqlIsolationLevel.ReadCommitted   => "READ COMMITTED"
        case SqlIsolationLevel.RepeatableRead  => "REPEATABLE READ"
        case SqlIsolationLevel.Serializable    => "SERIALIZABLE"

    /** Sends `START TRANSACTION` (optionally with isolation level and access mode).
      *
      * If `isolation` is [[Present]], the isolation level is set for this transaction using `SET TRANSACTION ISOLATION LEVEL ...` before
      * `START TRANSACTION` (MySQL syntax requires this before the START TRANSACTION, not inline). If `readOnly` is `true`, `READ ONLY` is
      * appended to `START TRANSACTION`.
      *
      * @param channel
      *   the active MySQL channel
      * @param isolation
      *   optional isolation level; [[Absent]] uses the server default (InnoDB default: `REPEATABLE READ`)
      * @param readOnly
      *   if `true`, opens a `READ ONLY` transaction (INSERT/UPDATE/DELETE will fail with ER_CANT_DO_THIS)
      */
    def begin(
        channel: MysqlChannel,
        deprecateEof: Boolean,
        isolation: Maybe[SqlIsolationLevel],
        readOnly: Boolean
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        // MySQL requires SET TRANSACTION ... before START TRANSACTION to affect only the next transaction.
        val setIsolation: Unit < (Async & Abort[SqlException]) =
            isolation match
                case Absent =>
                    ()
                case Present(level) =>
                    simpleCommand(channel, deprecateEof, s"SET TRANSACTION ISOLATION LEVEL ${isolationClause(level)}")

        val startSql = if readOnly then "START TRANSACTION READ ONLY" else "START TRANSACTION"

        setIsolation.andThen(simpleCommand(channel, deprecateEof, startSql))
    end begin

    /** Sends `COMMIT` and waits for the OK response. */
    def commit(channel: MysqlChannel, deprecateEof: Boolean)(using Frame): Unit < (Async & Abort[SqlException]) =
        simpleCommand(channel, deprecateEof, "COMMIT")

    /** Sends `ROLLBACK` and waits for the OK response. */
    def rollback(channel: MysqlChannel, deprecateEof: Boolean)(using Frame): Unit < (Async & Abort[SqlException]) =
        simpleCommand(channel, deprecateEof, "ROLLBACK")

    /** Sends `SAVEPOINT <name>` and waits for the OK response. */
    def savepoint(channel: MysqlChannel, deprecateEof: Boolean, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        simpleCommand(channel, deprecateEof, s"SAVEPOINT $name")

    /** Sends `RELEASE SAVEPOINT <name>` and waits for the OK response. */
    def releaseSavepoint(channel: MysqlChannel, deprecateEof: Boolean, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        simpleCommand(channel, deprecateEof, s"RELEASE SAVEPOINT $name")

    /** Sends `ROLLBACK TO SAVEPOINT <name>` and waits for the OK response. */
    def rollbackToSavepoint(channel: MysqlChannel, deprecateEof: Boolean, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        simpleCommand(channel, deprecateEof, s"ROLLBACK TO SAVEPOINT $name")

    /** Sends a single SQL command via COM_QUERY and discards the result (expects OK or errors). */
    private def simpleCommand(channel: MysqlChannel, deprecateEof: Boolean, sql: String)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        SimpleQueryExchange.run(channel, sql, deprecateEof, Maybe.Absent).unit

end MysqlTransactionExchange
