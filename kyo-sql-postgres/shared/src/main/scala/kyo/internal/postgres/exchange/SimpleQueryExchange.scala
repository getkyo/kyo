package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlException
import kyo.SqlRow
import kyo.internal.postgres.*

/** Executes the Postgres simple-query protocol cycle.
  *
  * Wire sequence:
  *   1. Send [[Query]] message containing the SQL text.
  *   2. Read one result set per statement (multi-statement SQL produces one result set per statement).
  *   3. Accumulate all rows across all result sets.
  *   4. Read [[ReadyForQuery]], which ends the cycle.
  *
  * The simple-query protocol is used exclusively for `executeRaw` (multi-statement scripts) and for the initial health-check query. Normal
  * `query`/`execute` calls use the Extended Protocol.
  */
object SimpleQueryExchange:

    /** Sends `sql` as a simple query and returns all rows from all result sets.
      *
      * The returned [[Chunk[SqlRow]]] contains rows from every statement in order. DML statements contribute no rows but their command tags
      * are counted. This is its own barrier: every exit path, the error path included, has read the cycle's [[ReadyForQuery]], so a caller
      * neither needs to drain afterwards nor may read one itself.
      */
    def run(
        channel: PostgresChannel,
        sql: String,
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): (Chunk[SqlRow], Long) < (Async & Abort[SqlException]) =
        val m = channel.marshallers.query
        channel.send(Query(sql))(using m).andThen {
            collectAllResults(channel, sql, pid, Chunk.empty, 0L, onParameterStatus, onNotification)
        }
    end run

    /** Accumulates result sets until [[ReadyForQuery]], which is consumed here. */
    private def collectAllResults(
        channel: PostgresChannel,
        sql: String,
        pid: Long,
        rows: Chunk[SqlRow],
        affected: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): (Chunk[SqlRow], Long) < (Async & Abort[SqlException]) =
        channel.receive.flatMap {
            case _: ReadyForQuery =>
                // End of simple-query cycle.
                (rows, affected)

            case RowDescription(fields) =>
                // Start of a SELECT-like result set, collect rows then continue.
                collectDataRows(channel, sql, pid, fields, rows, affected, onParameterStatus, onNotification)

            case CommandComplete(tag) =>
                // DML statement completed; parse affected row count from tag (e.g. "INSERT 0 3", "UPDATE 5", "DELETE 2").
                val count = CommandTag.parseAffectedCount(tag)
                collectAllResults(channel, sql, pid, rows, affected + count, onParameterStatus, onNotification)

            case EmptyQueryResponse =>
                collectAllResults(channel, sql, pid, rows, affected, onParameterStatus, onNotification)

            case msg =>
                ReadLoopSideband.handle(
                    msg,
                    channel,
                    Present(sql),
                    0,
                    pid,
                    "simple-query cycle",
                    "ReadyForQuery / RowDescription / CommandComplete / EmptyQueryResponse / ErrorResponse",
                    onParameterStatus,
                    onNotification
                )(collectAllResults(channel, sql, pid, rows, affected, onParameterStatus, onNotification))
        }

    private def collectDataRows(
        channel: PostgresChannel,
        sql: String,
        pid: Long,
        fields: Chunk[FieldDescription],
        acc: Chunk[SqlRow],
        affected: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): (Chunk[SqlRow], Long) < (Async & Abort[SqlException]) =
        channel.receive.flatMap {
            case DataRow(values) =>
                collectDataRows(
                    channel,
                    sql,
                    pid,
                    fields,
                    acc.appended(PostgresRowCodec.row(values, fields)),
                    affected,
                    onParameterStatus,
                    onNotification
                )

            case CommandComplete(tag) =>
                // This result set is done; continue looking for more or ReadyForQuery.
                val count = CommandTag.parseAffectedCount(tag)
                collectAllResults(channel, sql, pid, acc, affected + count, onParameterStatus, onNotification)

            case EmptyQueryResponse =>
                collectAllResults(channel, sql, pid, acc, affected, onParameterStatus, onNotification)

            case msg =>
                ReadLoopSideband.handle(
                    msg,
                    channel,
                    Present(sql),
                    0,
                    pid,
                    "collecting rows",
                    "DataRow / CommandComplete / EmptyQueryResponse / ErrorResponse",
                    onParameterStatus,
                    onNotification
                )(collectDataRows(channel, sql, pid, fields, acc, affected, onParameterStatus, onNotification))
        }

end SimpleQueryExchange
