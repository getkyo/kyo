package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlCodec.Format
import kyo.SqlException
import kyo.SqlRow
import kyo.db.Connection
import kyo.internal.postgres.*

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
  * A [[Scope.ensure]] finalizer fires on every exit path, and what it does is keyed on the exit edge, because the two edges owe the wire
  * different things.
  *
  * An exit that left the session idle (normal completion, or a typed failure the server itself answered) owns the resynchronisation: Close
  * + Sync + drain to ReadyForQuery, uninterruptible and bounded by `cleanupTimeout`, marking the channel corrupted when the cleanup itself
  * fails so the connection cannot be pooled with another statement's bytes still queued. That drain is one round trip by construction:
  * between batches the wire is clean, and after a mid-stream ErrorResponse the server discards until Sync.
  *
  * A dirty exit (interrupt, panic, protocol-fatal failure) must NOT read. The server may still be producing the abandoned batch, and
  * reading it out waits on exactly the work the caller asked to stop, with the pool's wire cancel sequenced behind the wait. So the
  * finalizer only WRITES on that edge: Close + Sync when the portal loop had the wire, giving the reclaim's drain (which runs after the
  * cancel) exactly one ReadyForQuery to find; nothing at all while still in the prepare phase, whose own Sync is already the outstanding
  * barrier and a second one would leave a CloseComplete + ReadyForQuery pair queued past that drain.
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
      * @param cleanupTimeout
      *   budget for the idle-edge cleanup (Close + Sync + drain); on expiry the channel is marked corrupted. Callers thread the configured
      *   reclaim budget; the default mirrors [[kyo.SqlConfig]]'s default `cancelTimeout`
      */
    def stream(
        channel: PostgresChannel,
        stmtCache: Cache[String, PreparedStmt],
        stmtCounter: AtomicLong,
        sql: String,
        params: Chunk[BoundParam[?]],
        batchSize: Int,
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async,
        cleanupTimeout: Duration = 2.seconds
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        val paramOids = ExtendedQueryExchange.paramOidsOf(params)

        Stream:
            // Generate a unique named portal so we can explicitly close it later.
            // Random.nextLong gives uniqueness-by-randomness without a JDK System call.
            Random.nextLong.flatMap { rnd =>
                val portalName = s"p_${ExtendedQueryExchange.cacheKey(sql, paramOids)}_${rnd.toHexString}"
                // False until prepareStmt has consumed its own ReadyForQuery, true once the portal loop owns the
                // wire. A dirty exit is resolved differently in the two phases: the portal loop keeps zero
                // barriers outstanding (Flush pipeline) so the cleanup owes one Sync, while the prepare phase's
                // own Sync is already outstanding and a second would desynchronise the reclaim's drain.
                AtomicRef.init(false).flatMap { portalPhase =>
                    // Register the portal cleanup in the enclosing Scope. Fires on every exit path; what it does
                    // is keyed on the exit edge (see the class scaladoc, "Cleanup discipline").
                    Scope.ensure(error =>
                        cleanup(channel, portalName, portalPhase, cleanupTimeout, onParameterStatus, onNotification, error)
                    ).andThen:
                        for
                            stmt <- ExtendedQueryExchange.prepareStmt(
                                channel,
                                stmtCache,
                                stmtCounter,
                                sql,
                                paramOids,
                                pid,
                                onParameterStatus,
                                onNotification
                            )
                            // The prepare barrier is consumed; from here a dirty exit owes the wire a Close + Sync.
                            _ <- portalPhase.set(true)
                            fields = stmt.rowDescription match
                                case Absent      => Chunk.empty[FieldDescription]
                                case Present(rd) => rd.fields
                            format =
                                if stmt.resultFormats.nonEmpty then Format.fromCode(stmt.resultFormats(0))
                                else Format.Text
                            // Pipeline Bind + first Execute + Flush in one message group.
                            _ <- sendBindAndExecuteWithFlush(channel, stmt, portalName, params, batchSize)
                            // Read the first batch (includes BindComplete response); no Sync was sent so no ReadyForQuery.
                            firstBatch <- readBatch(channel, fields, format, pid, onParameterStatus, onNotification, isFirstBatch = true)
                            // Emit first batch, then loop for subsequent batches on PortalSuspended.
                            _ <- emitAndLoop(
                                channel,
                                portalName,
                                batchSize,
                                fields,
                                format,
                                firstBatch,
                                pid,
                                onParameterStatus,
                                onNotification
                            )
                        yield ()
                        end for
                }
            }

    end stream

    // --- Private helpers ---

    /** The Scope finalizer for one stream, keyed on the exit edge.
      *
      * [[kyo.db.Connection.leftSessionIdle]] is the discriminator, the same predicate the adapter's settle and the pool's exit
      * decision key on, so all three layers classify one exit the same way.
      *
      * Idle edge: this cleanup owns the resynchronisation. Close + Sync + drain to ReadyForQuery, uninterruptible ([[Async.mask]]) and
      * bounded by `cleanupTimeout`; a cleanup that fails or overruns marks the channel corrupted instead of vanishing, so the connection
      * fails fast rather than serving another statement's bytes. The same discipline as [[CopyExchange]]'s cleanup, minus the latch: a
      * stream's cleanup runs inside the Scope finalizer chain, sequenced before the pool can hand the connection to anyone.
      *
      * Dirty edge: no reads, ever. The reclaim owns them, in its own order (cancel first, then drain), under its own budget. In the portal
      * phase this writes Close + Sync so that drain has its one barrier; in the prepare phase it writes nothing, because the statement's
      * own Sync is the barrier already outstanding. The writes are bounded too, so a dead transport cannot park the finalizer chain and
      * hold the lease's permit behind it.
      */
    private def cleanup(
        channel: PostgresChannel,
        portalName: String,
        portalPhase: AtomicRef[Boolean],
        cleanupTimeout: Duration,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async,
        error: Maybe[Result.Error[Any]]
    )(using Frame): Unit < Async =
        if Connection.leftSessionIdle(error) then
            Async.mask {
                Abort.run[Timeout](
                    Async.timeout(cleanupTimeout)(
                        Abort.run[SqlException](closePortalAndSync(channel, portalName, onParameterStatus, onNotification))
                    )
                ).flatMap {
                    case Result.Success(Result.Success(_)) => ()
                    case _                                 => channel.markCorrupted()
                }
            }
        else
            portalPhase.get.flatMap {
                case true =>
                    Abort.run[Timeout](
                        Async.timeout(cleanupTimeout)(
                            Abort.run[SqlException](sendCloseAndSync(channel, portalName))
                        )
                    ).unit
                case false =>
                    ()
            }
    end cleanup

    /** Writes Close(portal) + Sync without reading anything back.
      *
      * The dirty-exit half of the cleanup: the responses these elicit (CloseComplete, ReadyForQuery) are deliberately left on the wire for
      * the reclaim's drain, which runs after the wire cancel has truncated whatever the server was still producing.
      */
    private def sendCloseAndSync(
        channel: PostgresChannel,
        portalName: String
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        val closeM = channel.marshallers.close
        val syncM  = channel.marshallers.sync
        for
            // Close the portal (may already be gone after CommandComplete, the server gracefully handles this).
            _ <- channel.send(Close('P'.toByte, portalName))(using closeM)
            // Sync: ends the extended-query pipeline; the server answers with the ReadyForQuery barrier.
            _ <- channel.send(kyo.internal.postgres.SyncMessage)(using syncM)
        yield ()
        end for
    end sendCloseAndSync

    /** Closes the named portal, sends Sync, and drains to ReadyForQuery: the idle-edge cleanup.
      *
      * Note: after a stream completes normally (CommandComplete seen), the portal is already gone server-side. The server may return an
      * error for the `Close` of a non-existent portal. We send the Close anyway and swallow any error, then drain to ReadyForQuery.
      */
    private def closePortalAndSync(
        channel: PostgresChannel,
        portalName: String,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        sendCloseAndSync(channel, portalName).andThen(
            ReadyForQueryDrain.run(channel, onParameterStatus, onNotification).unit
        )

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
        val paramFormats: Chunk[Short]            = params.map(_.encoder.format.code)
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

    /** Reads DataRow* until PortalSuspended, CommandComplete, or EmptyQueryResponse. No ReadyForQuery is present in either shape because the
      * preceding message group ended with Flush, not Sync.
      *
      * `isFirstBatch` covers the one difference between the first Execute batch and every batch after it: only the first one follows a
      * `Bind`, so only it can see `BindComplete` (and, in the cache-miss path where Parse/Bind/Execute/Flush are pipelined together,
      * `ParseComplete`). Subsequent batches never legitimately see either, so they fall through to the same unrecognized-message failure as
      * any other unexpected message; `contextLabel` and `expectedLabel` are chosen accordingly.
      */
    private def readBatch(
        channel: PostgresChannel,
        fields: Chunk[FieldDescription],
        format: Format,
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async,
        isFirstBatch: Boolean = false
    )(using Frame): BatchResult < (Async & Abort[SqlException]) =
        val contextLabel = if isFirstBatch then "first Execute batch" else "Execute batch"
        val expectedLabel =
            if isFirstBatch then "BindComplete / DataRow / PortalSuspended / CommandComplete / EmptyQueryResponse / ErrorResponse"
            else "DataRow / PortalSuspended / CommandComplete / EmptyQueryResponse / ErrorResponse"

        def loop(acc: Chunk[SqlRow])(using Frame): BatchResult < (Async & Abort[SqlException]) =
            channel.receive.flatMap {
                case BindComplete if isFirstBatch =>
                    loop(acc)

                case ParseComplete if isFirstBatch =>
                    loop(acc)

                case DataRow(values) =>
                    loop(acc.appended(PostgresRowCodec.row(values, fields, format)))

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

                case msg =>
                    // Error during Bind/Execute is NOT preceded by a ReadyForQuery drain here: no Sync has been sent
                    // yet, so the server never answers a Flush-only pipeline with one. The Scope.ensure finalizer
                    // sends Close + Sync + drain regardless of how this cycle ends.
                    ReadLoopSideband.handle(
                        msg,
                        channel,
                        Absent,
                        0,
                        pid,
                        contextLabel,
                        expectedLabel,
                        onParameterStatus,
                        onNotification,
                        drainOnError = false
                    )(loop(acc))
            }

        loop(Chunk.empty)
    end readBatch

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

end StreamQueryExchange
