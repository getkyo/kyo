package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlException
import kyo.SqlRow
import kyo.internal.postgres.*

/** Executes the Postgres simple-query protocol cycle.
  *
  * Wire sequence:
  *   1. Send [[Query]] message containing the SQL text.
  *   2. Delegate to [[QueryResultExchange]] for each result set (multi-statement SQL produces one result set per statement).
  *   3. Accumulate all rows across all result sets.
  *   4. Read [[ReadyForQuery]], via [[BarrierGuard]] in the caller.
  *
  * The simple-query protocol is used exclusively for `executeRaw` (multi-statement scripts) and for the initial health-check query. Normal
  * `query`/`execute` calls use the Extended Protocol.
  */
object SimpleQueryExchange:

    /** Sends `sql` as a simple query and returns all rows from all result sets.
      *
      * The returned [[Chunk[SqlRow]]] contains rows from every statement in order. DML statements contribute no rows but their command tags
      * are counted. The caller is expected to wrap this call with [[BarrierGuard.runWithBarrier]] to drain [[ReadyForQuery]].
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

    /** Accumulates result sets until [[ReadyForQuery]] (which is consumed here, so do NOT wrap with [[BarrierGuard]]). */
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
                val count = parseAffectedCount(tag)
                collectAllResults(channel, sql, pid, rows, affected + count, onParameterStatus, onNotification)

            case EmptyQueryResponse =>
                collectAllResults(channel, sql, pid, rows, affected, onParameterStatus, onNotification)

            case ParameterStatus(name, value) =>
                onParameterStatus(
                    name,
                    value
                ).andThen(collectAllResults(channel, sql, pid, rows, affected, onParameterStatus, onNotification))

            case n: NotificationResponse =>
                onNotification(n).andThen(collectAllResults(channel, sql, pid, rows, affected, onParameterStatus, onNotification))

            case NoticeResponse(_) =>
                collectAllResults(channel, sql, pid, rows, affected, onParameterStatus, onNotification)

            case ErrorResponse(fields) =>
                // Drain to ReadyForQuery before raising, the server always sends RFQ after an error.
                drainToReadyForQuery(channel).andThen(Abort.fail(QueryResultExchange.mkServerError(fields, Present(sql), 0, Present(pid))))

            case other =>
                Abort.fail(SqlException.Connection(s"Unexpected message in simple-query cycle: $other", summon[Frame]))
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
                    acc.appended(new SqlRow(values, fields)),
                    affected,
                    onParameterStatus,
                    onNotification
                )

            case CommandComplete(tag) =>
                // This result set is done; continue looking for more or ReadyForQuery.
                val count = parseAffectedCount(tag)
                collectAllResults(channel, sql, pid, acc, affected + count, onParameterStatus, onNotification)

            case EmptyQueryResponse =>
                collectAllResults(channel, sql, pid, acc, affected, onParameterStatus, onNotification)

            case ParameterStatus(name, value) =>
                onParameterStatus(
                    name,
                    value
                ).andThen(collectDataRows(channel, sql, pid, fields, acc, affected, onParameterStatus, onNotification))

            case n: NotificationResponse =>
                onNotification(n).andThen(collectDataRows(channel, sql, pid, fields, acc, affected, onParameterStatus, onNotification))

            case NoticeResponse(_) =>
                collectDataRows(channel, sql, pid, fields, acc, affected, onParameterStatus, onNotification)

            case ErrorResponse(fields) =>
                // Drain to ReadyForQuery before raising, the server always sends RFQ after an error.
                drainToReadyForQuery(channel).andThen(Abort.fail(QueryResultExchange.mkServerError(fields, Present(sql), 0, Present(pid))))

            case other =>
                Abort.fail(SqlException.Connection(s"Unexpected message while collecting rows: $other", summon[Frame]))
        }

    /** Drains messages until ReadyForQuery (inclusive), discarding all. Used after ErrorResponse. */
    private def drainToReadyForQuery(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.receive.flatMap {
            case _: ReadyForQuery => ()
            case _                => drainToReadyForQuery(channel)
        }

    /** Parses the affected-row count from a command tag.
      *
      * Examples: "INSERT 0 1" → 1, "UPDATE 5" → 5, "DELETE 2" → 2, "SELECT 3" → 3, "CREATE TABLE" → 0.
      */
    private def parseAffectedCount(tag: String): Long =
        val parts = tag.split(' ')
        if parts.length >= 2 then
            parts.last.toLongOption.getOrElse(0L)
        else 0L
    end parseAffectedCount

end SimpleQueryExchange
