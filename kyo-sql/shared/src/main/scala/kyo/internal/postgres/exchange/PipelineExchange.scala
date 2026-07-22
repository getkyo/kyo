package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlConnectionClosedException
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlConnectionWritePanicException
import kyo.SqlException
import kyo.SqlRow
import kyo.internal.postgres.*
import kyo.internal.postgres.types.EncodingRegistry
import kyo.internal.postgres.types.Format

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
      * Implementation note: backed by a raw `java.util.concurrent.atomic.AtomicInteger` (a JDK thread-safe type). This is test-only
      * instrumentation and does NOT breach the kyo-sql safe-only policy, it is a JDK atomic, not a Kyo `.Unsafe` API or `AllowUnsafe`
      * bypass.
      */
    private[kyo] val writeCount = new java.util.concurrent.atomic.AtomicInteger(0)

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
                val paramFormats: Chunk[Short]            = ps.params.map(_.format.code)
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
                syncM.write(kyo.internal.postgres.Sync, buf)
            }

            // 2. One TCP write for all triples.
            val bytes = buf.toSpan
            Abort.run[Closed](channel.conn.outbound.safe.put(bytes)).flatMap {
                case Result.Success(_) =>
                    // Increment the transport-local write counter (one increment = one TCP batch flush).
                    val _ = writeCount.incrementAndGet()
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
        registry: EncodingRegistry,
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
                    loop(bindSeen, acc.appended(new SqlRow(values, fields, format)), failed)

                case CommandComplete(tag) =>
                    val affected = parseAffectedCount(tag)
                    // CommandComplete is followed by ReadyForQuery in pipelined mode.
                    drainToReadyForQuery(channel).map { _ =>
                        failed match
                            case Absent     => Result.Success(SqlClient.PipelineBuilder.Outcome(acc, affected))
                            case Present(e) => Result.Failure(e)
                    }

                case EmptyQueryResponse =>
                    drainToReadyForQuery(channel).map { _ =>
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
                    val ex = QueryResultExchange.mkServerError(errorFields, Present(stmt.stmt.sql), stmt.params.size, Present(pid))
                    // Record the error; still need to drain to ReadyForQuery.
                    drainToReadyForQuery(channel).map(_ => Result.Failure(ex))

                case ParameterStatus(name, value) =>
                    onParameterStatus(name, value).andThen(loop(bindSeen, acc, failed))

                case n: NotificationResponse =>
                    onNotification(n).andThen(loop(bindSeen, acc, failed))

                case NoticeResponse(_) =>
                    loop(bindSeen, acc, failed)

                case other =>
                    // Connection-level error, re-raise (not a per-statement error).
                    Abort.fail(SqlConnectionUnexpectedMessageException(
                        "pipeline Execute",
                        "BindComplete / DataRow / CommandComplete / EmptyQueryResponse / ReadyForQuery / ErrorResponse",
                        other.toString
                    ))
            }

        loop(bindSeen = false, Chunk.empty, Absent)
    end readOneStatementResult

    private def drainToReadyForQuery(channel: PostgresChannel)(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.receive.flatMap {
            case _: ReadyForQuery => ()
            case ErrorResponse(_) => drainToReadyForQuery(channel) // error in error recovery, keep draining
            case _                => drainToReadyForQuery(channel)
        }

    private def parseAffectedCount(tag: String): Long =
        val parts = tag.split(' ')
        if parts.length >= 2 then parts.last.toLongOption.getOrElse(0L)
        else 0L
    end parseAffectedCount

end PipelineExchange
