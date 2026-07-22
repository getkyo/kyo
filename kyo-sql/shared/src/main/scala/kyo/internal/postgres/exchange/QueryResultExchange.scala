package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlException
import kyo.SqlRow
import kyo.SqlServerException
import kyo.internal.postgres.*

/** Result of a single query result set. */
final case class QueryResult(
    fields: Chunk[FieldDescription],
    rows: Chunk[SqlRow],
    tag: String
)

/** Accumulates a single Postgres query result set from the wire.
  *
  * Reads:
  *   - (Optional) [[RowDescription]], column metadata; Absent for non-SELECT statements.
  *   - 0..N [[DataRow]] messages, row data.
  *   - [[CommandComplete]] or [[EmptyQueryResponse]], marks end of result set.
  *
  * Mid-stream messages handled:
  *   - [[ParameterStatus]], updates the parameters map via callback.
  *   - [[NoticeResponse]], logged and discarded.
  *   - [[NotificationResponse]], enqueued to the notifications channel.
  *   - [[ErrorResponse]], converted to [[SqlServerException]] and raised via [[Abort]].
  *
  * Does NOT read the trailing [[ReadyForQuery]]; that is the caller's responsibility (via [[BarrierGuard]]).
  */
object QueryResultExchange:

    def run(
        channel: PostgresChannel,
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): QueryResult < (Async & Abort[SqlException]) =
        // First message may be RowDescription or CommandComplete (for DML without rows)
        channel.receive.flatMap {
            case RowDescription(fields) =>
                collectRows(channel, pid, fields, Chunk.empty, onParameterStatus, onNotification)

            case CommandComplete(tag) =>
                QueryResult(Chunk.empty, Chunk.empty, tag)

            case EmptyQueryResponse =>
                QueryResult(Chunk.empty, Chunk.empty, "")

            case ParameterStatus(name, value) =>
                onParameterStatus(name, value).andThen(run(channel, pid, onParameterStatus, onNotification))

            case n: NotificationResponse =>
                onNotification(n).andThen(run(channel, pid, onParameterStatus, onNotification))

            case NoticeResponse(_) =>
                run(channel, pid, onParameterStatus, onNotification)

            case ErrorResponse(fields) =>
                Abort.fail(mkServerError(fields, Absent, 0, Present(pid)))

            case other =>
                Abort.fail(SqlConnectionUnexpectedMessageException(
                    "QueryResult",
                    "RowDescription / CommandComplete / EmptyQueryResponse / ErrorResponse",
                    other.toString
                ))
        }

    private def collectRows(
        channel: PostgresChannel,
        pid: Long,
        fields: Chunk[FieldDescription],
        acc: Chunk[SqlRow],
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): QueryResult < (Async & Abort[SqlException]) =
        channel.receive.flatMap {
            case DataRow(values) =>
                collectRows(channel, pid, fields, acc.appended(new SqlRow(values, fields)), onParameterStatus, onNotification)

            case CommandComplete(tag) =>
                QueryResult(fields, acc, tag)

            case EmptyQueryResponse =>
                QueryResult(fields, acc, "")

            case ParameterStatus(name, value) =>
                onParameterStatus(name, value).andThen(collectRows(channel, pid, fields, acc, onParameterStatus, onNotification))

            case n: NotificationResponse =>
                onNotification(n).andThen(collectRows(channel, pid, fields, acc, onParameterStatus, onNotification))

            case NoticeResponse(_) =>
                collectRows(channel, pid, fields, acc, onParameterStatus, onNotification)

            case ErrorResponse(fields) =>
                Abort.fail(mkServerError(fields, Absent, 0, Present(pid)))

            case other =>
                Abort.fail(SqlConnectionUnexpectedMessageException(
                    "row collection",
                    "DataRow / CommandComplete / EmptyQueryResponse / ErrorResponse",
                    other.toString
                ))
        }

    // --- Error construction ---
    // var accumulators here are local to a single error-path parse; not concurrent.

    private val SqlTextMaxLen    = 4096
    private val TruncationSuffix = "... [truncated]"

    private def truncateSqlText(sql: String): String =
        if sql.length <= SqlTextMaxLen then sql
        else sql.take(SqlTextMaxLen) + TruncationSuffix

    /** Converts an [[ErrorResponse]] field list to a [[SqlServerException]].
      *
      * @param fields
      *   raw wire fields from the ErrorResponse message
      * @param sqlText
      *   optional SQL text; truncated to 4096 chars if provided
      * @param paramCount
      *   number of bound parameters (0 if not applicable)
      * @param connectionId
      *   optional connection/thread ID
      */
    def mkServerError(
        fields: Chunk[(Byte, String)],
        sqlText: Maybe[String],
        paramCount: Int,
        connectionId: Maybe[Long]
    )(using Frame): SqlServerException =
        var sqlState = "00000"
        var severity = "ERROR"
        var message  = "Unknown server error"
        var detail   = Maybe.empty[String]
        var hint     = Maybe.empty[String]
        var position = Maybe.empty[Int]
        val extra    = scala.collection.mutable.Map.empty[String, String]

        fields.foreach { case (tag, value) =>
            if tag == 'C'.toByte then sqlState = value
            else if tag == 'V'.toByte then severity = value
            else if tag == 'S'.toByte then
                if severity == "ERROR" then severity = value // 'S' is localised; 'V' overrides if present
            else if tag == 'M'.toByte then message = value
            else if tag == 'D'.toByte then detail = Present(value)
            else if tag == 'H'.toByte then hint = Present(value)
            else if tag == 'P'.toByte then value.toIntOption.foreach(p => position = Present(p))
            else if tag == 's'.toByte then extra("schema") = value
            else if tag == 't'.toByte then extra("table") = value
            else if tag == 'c'.toByte then extra("column") = value
            else if tag == 'd'.toByte then extra("datatype") = value
            else if tag == 'n'.toByte then extra("constraint") = value
            else if tag == 'F'.toByte then extra("file") = value
            else if tag == 'L'.toByte then extra("line") = value
            else if tag == 'R'.toByte then extra("routine") = value
        // else: ignore unknown fields
        }

        val truncatedSql = sqlText.map(truncateSqlText)
        SqlServerException(sqlState, severity, message, detail, hint, position, extra.toMap, truncatedSql, paramCount, connectionId)
    end mkServerError

end QueryResultExchange
