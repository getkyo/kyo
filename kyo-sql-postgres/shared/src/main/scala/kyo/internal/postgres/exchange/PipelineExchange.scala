package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlCodec.Format
import kyo.SqlConnectionClosedException
import kyo.SqlConnectionWritePanicException
import kyo.SqlException
import kyo.SqlRow
import kyo.internal.postgres.*

/** Implements the PostgreSQL extended-query pipeline mode.
  *
  * Sends multiple `Bind`/`Execute`/`Sync` triples in one TCP write, then reads all responses in order. Reduces RTT for batched
  * inserts/updates from N to ~1.
  *
  * Each statement is wrapped with its own `Sync` so that a per-statement error (SQLSTATE `22xxx`, `23xxx`, etc.) aborts only the failing
  * portal; subsequent portals in the same pipeline continue. The protocol guarantee is: each `Sync` causes the server to return
  * `ReadyForQuery`, so an error in the i-th statement is isolated and does not prevent reading statements i+1..N.
  *
  * ==Pipeline semantics==
  *
  * The caller passes `stmts: Chunk[PipelineStmt]`. The exchange:
  *   1. Resolves each statement's [[PreparedStmt]] from cache (or wire via Parse/Describe). This phase is NOT batched because
  *      Parse/Describe each need their own Sync barrier.
  *   2. Encodes all Bind/Execute/Sync triples into a single [[PostgresBufferWriter]] and writes them in one `conn.write`.
  *   3. Reads back one block of `BindComplete`/`DataRow*`/`CommandComplete|EmptyQueryResponse`/`ReadyForQuery` per statement. A
  *      per-statement `ErrorResponse`/`ReadyForQuery` pair is caught and recorded as a [[Result.Failure]] for that slot.
  *   4. Returns a `Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]]`, one entry per input statement.
  */
object PipelineExchange:

    /** Counts the number of pipeline batch TCP writes issued.
      *
      * Incremented once per successful [[run]] invocation (one increment = one TCP write carrying all Bind/Execute/Sync triples). Used by
      * integration tests to verify the single-round-trip guarantee.
      *
      * Visibility: `private[kyo]` so that test code in `kyo.sql.*` can read and reset the counter while keeping it out of the public API.
      *
      * An [[kyo.AtomicInt.Unsafe]], thread-safe across the fibers that increment it; readers and writers carry `AllowUnsafe` because the
      * counter lives outside any effect context.
      */
    // Unsafe: module-load init, before any live Frame exists.
    private[kyo] val writeCount = AtomicInt.Unsafe.init(0)(using AllowUnsafe.embrace.danger)

    /** A single pipeline statement: the prepared [[PreparedStmt]] plus bound parameters. */
    final case class PipelineStmt(
        stmt: PreparedStmt,
        params: Chunk[BoundParam[?]]
    )

    /** Sends all `stmts` in one TCP write and reads all responses, returning one result per statement.
      *
      * @param channel
      *   the active [[PostgresChannel]]
      * @param stmts
      *   the pipeline statements (already prepared, no Parse/Describe)
      * @param onParameterStatus
      *   callback for `ParameterStatus` messages
      * @param onNotification
      *   callback for `NotificationResponse` messages
      * @return
      *   one pipeline result per statement, in submission order
      */
    def run(
        channel: PostgresChannel,
        stmts: Chunk[PipelineStmt],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        if stmts.isEmpty then Chunk.empty
        else
            // 1. Encode ALL Bind/Execute/Sync triples into one buffer.
            val buf      = new PostgresBufferWriter
            val bindM    = channel.marshallers.bind
            val executeM = channel.marshallers.execute
            val syncM    = channel.marshallers.sync

            stmts.foreach { ps =>
                val paramFormats: Chunk[Short]            = ps.params.map(_.encoder.format.code)
                val paramValues: Chunk[Maybe[Span[Byte]]] = ps.params.map(_.encoded)
                val bindMsg = Bind(
                    portalName = "",
                    stmtName = ps.stmt.name,
                    paramFormats = paramFormats,
                    paramValues = paramValues,
                    resultFormats = ps.stmt.resultFormats
                )
                bindM.write(bindMsg, buf)
                executeM.write(Execute("", 0), buf)
                syncM.write(SyncMessage, buf)
            }

            // 2. One TCP write for all triples.
            val bytes = buf.toSpan
            Abort.run[Closed](channel.conn.outbound.safe.put(bytes)).flatMap {
                case Result.Success(_) =>
                    // Unsafe: increments the transport-local write counter (one increment = one TCP batch flush).
                    Sync.Unsafe.defer(discard(writeCount.incrementAndGet()))
                case Result.Failure(_) => Abort.fail(SqlConnectionClosedException("writing (pipeline)"))
                case Result.Panic(t) =>
                    Log.error(s"[kyo-sql] PipelineExchange: write panic: ${t.getMessage}").andThen(
                        Abort.fail(SqlConnectionWritePanicException(t))
                    )
            }.andThen {
                // 3. Read responses for each statement in order.
                readAllResponses(channel, stmts, pid, onParameterStatus, onNotification)
            }
    end run

    /** Resolves a [[PreparedStmt]] for each input statement (using the per-connection cache), then runs [[run]]. */
    def prepare(
        channel: PostgresChannel,
        stmtCache: Cache[String, PreparedStmt],
        stmtCounter: AtomicLong,
        stmts: Chunk[(String, Chunk[BoundParam[?]])],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        if stmts.isEmpty then Chunk.empty
        else
            // Resolve prepared stmts sequentially (each cache miss needs a Parse/Describe/Sync round trip).
            Kyo.foreach(stmts) { case (sql, params) =>
                ExtendedQueryExchange.prepareStmt(
                    channel,
                    stmtCache,
                    stmtCounter,
                    sql,
                    ExtendedQueryExchange.paramOidsOf(params),
                    pid,
                    onParameterStatus,
                    onNotification
                ).map { stmt =>
                    PipelineStmt(stmt, params)
                }
            }.flatMap { pipelineStmts =>
                run(channel, pipelineStmts, pid, onParameterStatus, onNotification)
            }
    end prepare

    // --- Internal: read all responses ---

    private def readAllResponses(
        channel: PostgresChannel,
        stmts: Chunk[PipelineStmt],
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        def loop(
            idx: Int,
            acc: Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]]
        ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
            if idx >= stmts.size then acc
            else
                val stmt = stmts(idx)
                readOneStatementResult(channel, stmt, pid, onParameterStatus, onNotification).flatMap { result =>
                    loop(idx + 1, acc.appended(result))
                }

        loop(0, Chunk.empty)
    end readAllResponses

    /** Reads the response for a single pipelined statement (up to and including `ReadyForQuery`).
      *
      * Always returns a pipeline result, never raises [[Abort[SqlException]]] for per-statement errors. Connection-level errors
      * (e.g. closed TCP socket) are re-raised.
      */
    private def readOneStatementResult(
        channel: PostgresChannel,
        stmt: PipelineStmt,
        pid: Long,
        onParameterStatus: (String, String) => Unit < Async,
        onNotification: NotificationResponse => Unit < Async
    )(using Frame): Result[SqlException, SqlClient.PipelineBuilder.Outcome] < (Async & Abort[SqlException]) =
        val fields: Chunk[FieldDescription] = stmt.stmt.rowDescription match
            case Absent      => Chunk.empty
            case Present(rd) => rd.fields

        def loop(
            bindSeen: Boolean,
            acc: Chunk[SqlRow],
            failed: Maybe[SqlException]
        ): Result[SqlException, SqlClient.PipelineBuilder.Outcome] < (Async & Abort[SqlException]) =
            channel.receive.flatMap {
                case BindComplete =>
                    loop(bindSeen = true, acc, failed)

                case DataRow(values) =>
                    val format = if stmt.stmt.resultFormats.nonEmpty then
                        Format.fromCode(stmt.stmt.resultFormats(0))
                    else Format.Text
                    loop(bindSeen, acc.appended(PostgresRowCodec.row(values, fields, format)), failed)

                case CommandComplete(tag) =>
                    val affected = CommandTag.parseAffectedCount(tag)
                    // CommandComplete is followed by ReadyForQuery in pipelined mode.
                    ReadyForQueryDrain.run(channel, onParameterStatus, onNotification).map { _ =>
                        failed match
                            case Absent     => Result.Success(SqlClient.PipelineBuilder.Outcome(acc, affected))
                            case Present(e) => Result.Failure(e)
                    }

                case EmptyQueryResponse =>
                    ReadyForQueryDrain.run(channel, onParameterStatus, onNotification).map { _ =>
                        failed match
                            case Absent     => Result.Success(SqlClient.PipelineBuilder.Outcome(acc, 0L))
                            case Present(e) => Result.Failure(e)
                    }

                case _: ReadyForQuery =>
                    // If we get RFQ without CommandComplete, treat as empty success or recorded failure.
                    failed match
                        case Absent     => Result.Success(SqlClient.PipelineBuilder.Outcome(acc, 0L))
                        case Present(e) => Result.Failure(e)

                case ErrorResponse(errorFields) =>
                    // A per-statement failure, unlike every other read loop's ErrorResponse: it is recorded and the
                    // pipeline keeps draining, rather than aborting the whole cycle, so it stays outside
                    // ReadLoopSideband.handle's ErrorResponse branch (that one always aborts).
                    val ex = ServerErrors.mkServerError(errorFields, Present(stmt.stmt.sql), stmt.params.size, Present(pid))
                    // Record the error; still need to drain to ReadyForQuery.
                    ReadyForQueryDrain.run(channel, onParameterStatus, onNotification).map(_ => Result.Failure(ex))

                case msg =>
                    // Connection-level error, re-raise (not a per-statement error).
                    ReadLoopSideband.handle(
                        msg,
                        channel,
                        Present(stmt.stmt.sql),
                        stmt.params.size,
                        pid,
                        "pipeline Execute",
                        "BindComplete / DataRow / CommandComplete / EmptyQueryResponse / ReadyForQuery / ErrorResponse",
                        onParameterStatus,
                        onNotification
                    )(loop(bindSeen, acc, failed))
            }

        loop(bindSeen = false, Chunk.empty, Absent)
    end readOneStatementResult

end PipelineExchange
