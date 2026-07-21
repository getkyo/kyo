package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlException
import kyo.SqlIsolationLevel
import kyo.internal.postgres.*

/** Simple-query round-trips for transaction control commands.
  *
  * Every command here sends a [[Query]] message and waits for [[ReadyForQuery]] (via [[SimpleQueryExchange.run]]). No data rows are
  * expected; only [[CommandComplete]] and [[ReadyForQuery]] are normal.
  *
  * These are NOT parameterised, transaction SQL is never user-supplied, so the simple-query protocol is safe and correct here.
  */
object TransactionExchange:

    /** Maps an [[SqlIsolationLevel]] to its SQL clause fragment. */
    private def isolationClause(level: SqlIsolationLevel): String = level match
        case SqlIsolationLevel.ReadUncommitted => "READ UNCOMMITTED"
        case SqlIsolationLevel.ReadCommitted   => "READ COMMITTED"
        case SqlIsolationLevel.RepeatableRead  => "REPEATABLE READ"
        case SqlIsolationLevel.Serializable    => "SERIALIZABLE"

    /** Sends `BEGIN` (or `BEGIN ISOLATION LEVEL …` / `BEGIN … READ ONLY`).
      *
      * @param channel
      *   the already-acquired connection channel
      * @param isolation
      *   if [[Present]], appends `ISOLATION LEVEL <level>` to the BEGIN command
      * @param readOnly
      *   if true, appends `READ ONLY`
      */
    def begin(
        channel: PostgresChannel,
        pid: Long,
        isolation: Maybe[SqlIsolationLevel],
        readOnly: Boolean
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        val clauses = Chunk.from(
            isolation.toOption.map(l => s"ISOLATION LEVEL ${isolationClause(l)}").toSeq ++
                (if readOnly then Seq("READ ONLY") else Seq.empty)
        )
        val sql = if clauses.isEmpty then "BEGIN" else s"BEGIN ${clauses.iterator.mkString(" ")}"
        simpleCommand(channel, sql, pid)
    end begin

    /** Sends `COMMIT` and waits for [[ReadyForQuery]]. */
    def commit(channel: PostgresChannel, pid: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        simpleCommand(channel, "COMMIT", pid)

    /** Sends `ROLLBACK` and waits for [[ReadyForQuery]]. */
    def rollback(channel: PostgresChannel, pid: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        simpleCommand(channel, "ROLLBACK", pid)

    /** Sends `SAVEPOINT <name>` and waits for [[ReadyForQuery]]. */
    def savepoint(channel: PostgresChannel, name: String, pid: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        simpleCommand(channel, s"SAVEPOINT $name", pid)

    /** Sends `RELEASE SAVEPOINT <name>` and waits for [[ReadyForQuery]]. */
    def releaseSavepoint(channel: PostgresChannel, name: String, pid: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        simpleCommand(channel, s"RELEASE SAVEPOINT $name", pid)

    /** Sends `ROLLBACK TO SAVEPOINT <name>` and waits for [[ReadyForQuery]]. */
    def rollbackToSavepoint(channel: PostgresChannel, name: String, pid: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        simpleCommand(channel, s"ROLLBACK TO SAVEPOINT $name", pid)

    /** Sends a single-statement command via the simple-query protocol and discards the result. */
    private def simpleCommand(channel: PostgresChannel, sql: String, pid: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        // SimpleQueryExchange.run reads ReadyForQuery internally, no extra drain needed.
        SimpleQueryExchange.run(channel, sql, pid, (_, _) => (), _ => ()).unit

end TransactionExchange
