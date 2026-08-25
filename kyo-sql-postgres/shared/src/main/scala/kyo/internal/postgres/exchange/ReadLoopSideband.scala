package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlException
import kyo.internal.postgres.*

/** Shared tail for every Postgres read loop that pulls a [[BackendMessage]] off the wire while looking for its own primary responses.
  *
  * Every extended-protocol, simple-query, and streaming read loop interleaves its primary messages (`DataRow`, `CommandComplete`,
  * `RowDescription`, ...) with three sideband message kinds the wire can deliver at any point (`ParameterStatus`, `NotificationResponse`,
  * `NoticeResponse`) plus the two edges every loop needs: a protocol-level `ErrorResponse` and an unrecognized message. Stating that
  * dispatch once keeps the seven read loops that reuse it from drifting on it independently.
  *
  * A caller that needs its own `ErrorResponse` handling (e.g. [[PipelineExchange]]'s per-statement failure isolation) matches it before
  * falling through to [[handle]]; `handle`'s own `ErrorResponse` branch is then simply never reached for that caller.
  */
private[exchange] object ReadLoopSideband:

    /** Dispatches one sideband message, or fails on `ErrorResponse` / an unrecognized message, otherwise resuming `loop`.
      *
      * @param sqlText
      *   the SQL text to attach to a raised [[kyo.SqlServerException]], or [[Absent]] when none applies at this point in the cycle
      * @param paramCount
      *   the bound-parameter count to attach to a raised [[kyo.SqlServerException]]
      * @param contextLabel
      *   the phase name reported in [[SqlConnectionUnexpectedMessageException]] for an unrecognized message
      * @param expectedLabel
      *   the expected-message-shapes description reported alongside `contextLabel`
      * @param drainOnError
      *   whether an `ErrorResponse` must be preceded by a drain to [[ReadyForQuery]] before failing; false for the streaming loops whose
      *   `ErrorResponse` arrives before any `Sync` was sent
      */
    def handle[A](
        msg: BackendMessage,
        channel: PostgresChannel,
        sqlText: Maybe[String],
        paramCount: Int,
        pid: Long,
        contextLabel: String,
        expectedLabel: String,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async,
        drainOnError: Boolean = true
    )(loop: => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        msg match
            case ParameterStatus(name, value) =>
                onParameterStatus(name, value).andThen(loop)

            case n: NotificationResponse =>
                onNotification(n).andThen(loop)

            case NoticeResponse(_) =>
                loop

            case ErrorResponse(fields) =>
                val fail = Abort.fail(ServerErrors.mkServerError(fields, sqlText, paramCount, Present(pid)))
                if drainOnError then ReadyForQueryDrain.run(channel, onParameterStatus, onNotification).andThen(fail)
                else fail

            case other =>
                Abort.fail(SqlConnectionUnexpectedMessageException(contextLabel, expectedLabel, other.toString))
    end handle

end ReadLoopSideband
