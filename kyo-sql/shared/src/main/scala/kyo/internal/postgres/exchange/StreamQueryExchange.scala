package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlException
import kyo.SqlRow
import kyo.internal.postgres.*
import kyo.internal.postgres.types.Format

/** Implements demand-driven streaming of Postgres result sets via named portals.
  *
  * ==Protocol sequence==
  *
  * Setup (Bind + first Execute), using `Flush` between batches to keep the portal alive:
  * {{{
  *   client → Bind(portalName, stmtName, ...)
  *   client → Execute(portalName, batchSize)
  *   client → Flush                          ← NOT Sync; Flush preserves the portal
  *   server → BindComplete DataRow*N [PortalSuspended | CommandComplete | EmptyQueryResponse]
  * }}}
  *
  * Subsequent batches (PortalSuspended response):
  * {{{
  *   client → Execute(portalName, batchSize)
  *   client → Flush
  *   server → DataRow*N [PortalSuspended | CommandComplete]
  * }}}
  *
  * Termination (after CommandComplete or early cancellation):
  * {{{
  *   client → Close('P', portalName)
  *   client → Sync                           ← Sync here ends the extended-query pipeline
  *   server → CloseComplete ReadyForQuery
  * }}}
  *
  * ==Why Flush instead of Sync between Execute calls?==
  *
  * Postgres named portals persist until the end of the current transaction. In autocommit mode (no explicit BEGIN/COMMIT), `Sync` acts as
  * an implicit transaction boundary and destroys all open portals. To allow resuming a named portal across multiple `Execute` calls, we use
  * `Flush` between batches, `Flush` forces the server to emit pending output without committing the implicit transaction. `Sync` is sent
  * only once, after the final `Close`, to return the connection to the ReadyForQuery state.
  *
  * ==Cleanup discipline==
  *
  * Portal cleanup is guaranteed via [[Scope.ensure]] on every exit path (normal completion, [[SqlException]] abort, fiber interruption).
  * The stream's effect type includes [[Scope]]; callers must provide a [[Scope]] in which the portal's lifetime is managed.
  */
private[postgres] object StreamQueryExchange:

    /** Streams query results as a demand-driven [[Stream]].
      *
      * The returned stream includes [[Scope]] in its effect set. The [[Scope]] is used to register the portal-close finalizer that fires on
      * any exit path (normal completion, [[SqlException]] abort, fiber interruption). Callers must either run the stream inside an existing
      * [[Scope]] or use `Scope.run` when consuming the stream.
      *
      * @param channel
      *   the Postgres wire channel (exclusive ownership for stream duration)
      * @param stmtCache
      *   per-connection prepared-statement cache (shared with [[ExtendedQueryExchange]])
      * @param sql
      *   parameterised SQL (with `$1`, `$2`, ... placeholders) or bare SQL
      * @param params
      *   encoded parameter values, one per placeholder
      * @param batchSize
      *   number of rows to fetch per `Execute` call; must be > 0
      * @param onParameterStatus
      *   callback for in-session ParameterStatus messages
      * @param onNotification
      *   callback for asynchronous NotificationResponse messages
      */
    def stream(
        channel: PostgresChannel,
        stmtCache: Cache[String, PreparedStmt],
        sql: String,
        params: Chunk[BoundParam[?]],
        batchSize: Int,
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        val paramOids = ExtendedQueryExchange.paramOidsOf(params)

        Stream:
            // Generate a unique named portal so we can explicitly close it later.
            // Random.nextLong gives uniqueness-by-randomness without a JDK System call.
            Random.nextLong.flatMap { rnd =>
                val portalName = s"p_${ExtendedQueryExchange.cacheKey(sql, paramOids)}_${rnd.toHexString}"
                // Register the portal-close finalizer in the enclosing Scope.
                // Fires on normal stream completion, on Abort[SqlException], and on fiber interruption.
                Scope.ensure(_ => Abort.run(closePortalAndSync(channel, portalName)).unit).andThen:
                    for
                        stmt <- ExtendedQueryExchange.prepareStmt(
                            channel,
                            stmtCache,
                            sql,
                            paramOids,
                            pid,
                            onParameterStatus,
                            onNotification
                        )
                        fields = stmt.rowDescription match
                            case Absent      => Chunk.empty[FieldDescription]
                            case Present(rd) => rd.fields
                        format =
                            if stmt.resultFormats.nonEmpty then Format.fromCode(stmt.resultFormats(0))
                            else Format.Text
                        // Pipeline Bind + first Execute + Flush in one message group.
                        _ <- sendBindAndExecuteWithFlush(channel, stmt, portalName, params, batchSize)
                        // Read the first batch (includes BindComplete response); no Sync was sent so no ReadyForQuery.
                        firstBatch <- readFirstBatch(channel, fields, format, pid, onParameterStatus, onNotification)
                        // Emit first batch, then loop for subsequent batches on PortalSuspended.
                        _ <- emitAndLoop(channel, portalName, batchSize, fields, format, firstBatch, pid, onParameterStatus, onNotification)
                    yield ()
                    end for
            }

    end stream

    // --- Private helpers ---

    /** Closes the named portal and sends Sync to return the connection to ReadyForQuery.
      *
      * This is the cleanup action registered in [[Scope.ensure]]. It fires on every exit path.
      *
      * Note: after a stream completes normally (CommandComplete seen), the portal is already gone server-side. The server may return an
      * error for the `Close` of a non-existent portal. We send the Close anyway and swallow any error, then drain to ReadyForQuery.
      */
    private def closePortalAndSync(
        channel: PostgresChannel,
        portalName: String
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        val closeM = channel.marshallers.close
        val syncM  = channel.marshallers.sync
        for
            // Close the portal (may already be gone after CommandComplete, the server gracefully handles this).
            _ <- channel.send(Close('P'.toByte, portalName))(using closeM)
            // Sync: ends the extended-query pipeline and returns the connection to ReadyForQuery.
            _ <- channel.send(kyo.internal.postgres.Sync)(using syncM)
            // Drain everything until ReadyForQuery (consumes CloseComplete or ErrorResponse from Close).
            _ <- drainToReadyForQuery(channel)
        yield ()
        end for
    end closePortalAndSync

    /** Pipelines Bind(portalName) + Execute(portalName, batchSize) + Flush.
      *
      * Using Flush instead of Sync preserves the named portal across multiple Execute calls. The server sends BindComplete + DataRow* +
      * PortalSuspended|CommandComplete without a ReadyForQuery.
      */
    private def sendBindAndExecuteWithFlush(
        channel: PostgresChannel,
        stmt: PreparedStmt,
        portalName: String,
        params: Chunk[BoundParam[?]],
        batchSize: Int
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        val paramFormats: Chunk[Short]            = params.map(_.format.code)
        val paramValues: Chunk[Maybe[Span[Byte]]] = params.map(_.encoded)
        val bindMsg = Bind(
            portalName = portalName,
            stmtName = stmt.name,
            paramFormats = paramFormats,
            paramValues = paramValues,
            resultFormats = stmt.resultFormats
        )
        val bindM    = channel.marshallers.bind
        val executeM = channel.marshallers.execute
        val flushM   = channel.marshallers.flush
        for
            _ <- channel.send(bindMsg)(using bindM)
            _ <- channel.send(Execute(portalName, batchSize))(using executeM)
            // Flush (not Sync), preserves the portal for subsequent Execute calls.
            _ <- channel.send(kyo.internal.postgres.Flush)(using flushM)
        yield ()
        end for
    end sendBindAndExecuteWithFlush

    /** Reads the first Execute batch response, which includes the BindComplete message.
      *
      * Server sends (after Flush): BindComplete DataRow* [PortalSuspended | CommandComplete | EmptyQueryResponse] No ReadyForQuery is
      * present after Flush.
      */
    private def readFirstBatch(
        channel: PostgresChannel,
        fields: Chunk[FieldDescription],
        format: Format,
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): BatchResult < (Async & Abort[SqlException]) =
        def loop(bindSeen: Boolean, acc: Chunk[SqlRow])(using Frame): BatchResult < (Async & Abort[SqlException]) =
            channel.receive.flatMap {
                case BindComplete =>
                    loop(bindSeen = true, acc)

                case ParseComplete =>
                    loop(bindSeen, acc)

                case DataRow(values) =>
                    loop(bindSeen, acc.appended(new SqlRow(values, fields, format)))

                case PortalSuspended =>
                    // More rows available; no ReadyForQuery after Flush.
                    BatchResult.Suspended(acc)

                case CommandComplete(_) =>
                    // All rows sent in this (and only) batch.
                    BatchResult.Complete(acc)

                case EmptyQueryResponse =>
                    BatchResult.Complete(acc)

                case _: ReadyForQuery =>
                    // Unexpected after Flush; treat as end-of-stream.
                    BatchResult.Complete(acc)

                case ParameterStatus(name, value) =>
                    onParameterStatus(name, value).andThen(loop(bindSeen, acc))

                case n: NotificationResponse =>
                    onNotification(n).andThen(loop(bindSeen, acc))

                case NoticeResponse(_) =>
                    loop(bindSeen, acc)

                case ErrorResponse(errFields) =>
                    // Error during Bind/Execute. Drain to ReadyForQuery is NOT needed here because we haven't
                    // sent Sync yet, the server doesn't send ReadyForQuery in response to a Flush-only pipeline.
                    // However, we still need to drain any remaining messages before the error is propagated.
                    // The Scope.ensure finalizer will send Close + Sync + drain.
                    Abort.fail(QueryResultExchange.mkServerError(errFields, Absent, 0, Present(pid)))

                case other =>
                    Abort.fail(SqlException.Connection(s"Unexpected message during first Execute batch: $other", summon[Frame]))
            }

        loop(bindSeen = false, Chunk.empty)
    end readFirstBatch

    /** Emits the given batch, then loops on PortalSuspended to fetch more batches via Execute + Flush. */
    private def emitAndLoop(
        channel: PostgresChannel,
        portalName: String,
        batchSize: Int,
        fields: Chunk[FieldDescription],
        format: Format,
        batch: BatchResult,
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): Unit < (Emit[Chunk[SqlRow]] & Async & Abort[SqlException]) =
        batch match
            case BatchResult.Complete(rows) =>
                // Stream is done; emit the final (possibly empty) batch.
                if rows.isEmpty then ()
                else Emit.valueWith(rows)(())

            case BatchResult.Suspended(rows) =>
                // More rows available; emit this batch, then fetch the next.
                val fetchNext: Unit < (Emit[Chunk[SqlRow]] & Async & Abort[SqlException]) =
                    sendNextExecuteWithFlush(channel, portalName, batchSize).andThen(
                        readBatch(channel, fields, format, pid, onParameterStatus, onNotification).flatMap { nextBatch =>
                            emitAndLoop(channel, portalName, batchSize, fields, format, nextBatch, pid, onParameterStatus, onNotification)
                        }
                    )
                if rows.isEmpty then fetchNext
                else Emit.valueWith(rows)(fetchNext)
    end emitAndLoop

    /** Sends Execute(batchSize) + Flush for subsequent batches (after the first). */
    private def sendNextExecuteWithFlush(
        channel: PostgresChannel,
        portalName: String,
        batchSize: Int
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        val executeM = channel.marshallers.execute
        val flushM   = channel.marshallers.flush
        for
            _ <- channel.send(Execute(portalName, batchSize))(using executeM)
            _ <- channel.send(kyo.internal.postgres.Flush)(using flushM)
        yield ()
        end for
    end sendNextExecuteWithFlush

    /** Result of reading one Execute batch. */
    private enum BatchResult:
        /** PortalSuspended, more rows are available; caller should re-Execute. */
        case Suspended(rows: Chunk[SqlRow])

        /** CommandComplete or EmptyQueryResponse, stream is exhausted. */
        case Complete(rows: Chunk[SqlRow])
    end BatchResult

    /** Reads DataRow* until PortalSuspended or CommandComplete.
      *
      * Used for batches after the first (no BindComplete expected). No ReadyForQuery is present because the preceding message group ended
      * with Flush, not Sync.
      */
    private def readBatch(
        channel: PostgresChannel,
        fields: Chunk[FieldDescription],
        format: Format,
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): BatchResult < (Async & Abort[SqlException]) =
        def loop(acc: Chunk[SqlRow])(using Frame): BatchResult < (Async & Abort[SqlException]) =
            channel.receive.flatMap {
                case DataRow(values) =>
                    loop(acc.appended(new SqlRow(values, fields, format)))

                case PortalSuspended =>
                    BatchResult.Suspended(acc)

                case CommandComplete(_) =>
                    BatchResult.Complete(acc)

                case EmptyQueryResponse =>
                    BatchResult.Complete(acc)

                case _: ReadyForQuery =>
                    // Unexpected after Flush; treat as done.
                    BatchResult.Complete(acc)

                case ParameterStatus(name, value) =>
                    onParameterStatus(name, value).andThen(loop(acc))

                case n: NotificationResponse =>
                    onNotification(n).andThen(loop(acc))

                case NoticeResponse(_) =>
                    loop(acc)

                case ErrorResponse(errFields) =>
                    // Error during Execute. Scope.ensure will clean up via Close + Sync + drain.
                    Abort.fail(QueryResultExchange.mkServerError(errFields, Absent, 0, Present(pid)))

                case other =>
                    Abort.fail(SqlException.Connection(s"Unexpected message during Execute batch: $other", summon[Frame]))
            }

        loop(Chunk.empty)
    end readBatch

    /** Drains the channel until ReadyForQuery, discarding all intermediate messages. */
    private def drainToReadyForQuery(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.receive.flatMap {
            case _: ReadyForQuery => ()
            case _                => drainToReadyForQuery(channel)
        }

end StreamQueryExchange
