package kyo.internal.mysql.exchange

import kyo.*
import kyo.SqlException
import kyo.SqlRow
import kyo.internal.mysql.*
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.types.Format

/** Implements MySQL sequential pipeline execution.
  *
  * MySQL's wire protocol is request/response: each COM_STMT_PREPARE + COM_STMT_EXECUTE round-trip is independent and cannot be interleaved.
  * There is no equivalent to PostgreSQL's `Sync`-barrier pipeline mode where multiple Bind/Execute/Sync triples are sent in one TCP write.
  *
  * This exchange provides the same `Result[SqlException, SqlClient.PipelineBuilder.Outcome]`-per-statement interface as the Postgres `PipelineExchange`: each statement is
  * executed in order using the extended (binary) protocol; a per-statement server error is caught and recorded as
  * [[kyo.Result.Failure]] without aborting subsequent statements.
  *
  * ==Semantics==
  *
  *   1. Statements are executed strictly sequentially on the same connection (in-transaction or from the pool).
  *   2. Per-statement `Abort[SqlException]` is caught with `Abort.run`; a failure is recorded and the next statement proceeds.
  *   3. Connection-level errors (socket closed, panic) re-raise and abort the entire pipeline.
  *   4. Returns a `Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]]`, one entry per input statement, in submission order.
  *
  * ==Transaction context==
  *
  * [[run]] is used outside an active transaction (caller has already acquired a connection from the pool). [[runInTransaction]] is used
  * when the caller already holds a [[MysqlConnection]] (inside `SqlClient.transaction`). Both delegate to [[runOnConnection]].
  */
object MysqlPipelineExchange:

    /** A single pipeline statement: SQL text + bound parameters. */
    final case class PipelineStmt(
        sql: String,
        params: Chunk[BoundMysqlParam[?]]
    )

    /** Executes `stmts` sequentially on an already-acquired `conn`.
      *
      * Each statement is isolated with `Abort.run[SqlException]` so that a per-statement server error is recorded as
      * [[kyo.Result.Failure]] without aborting subsequent statements. Connection-level panics re-raise.
      *
      * @param conn
      *   the active [[MysqlConnection]] (must NOT be used concurrently, caller ensures serial access)
      * @param stmts
      *   the pipeline statements in submission order
      * @return
      *   one pipeline result per statement, in submission order
      */
    def runOnConnection(
        conn: MysqlConnection,
        stmts: Chunk[PipelineStmt]
    )(using Frame): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        if stmts.isEmpty then Chunk.empty
        else
            def loop(
                idx: Int,
                acc: Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]]
            ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
                if idx >= stmts.size then acc
                else
                    val stmt = stmts(idx)
                    executeOne(conn, stmt).flatMap { result =>
                        loop(idx + 1, acc.appended(result))
                    }
            loop(0, Chunk.empty)

    /** Executes all statements sequentially outside a transaction (caller provides the connection). */
    def run(
        conn: MysqlConnection,
        stmts: Chunk[PipelineStmt]
    )(using Frame): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        runOnConnection(conn, stmts)

    /** Executes all statements sequentially on the transaction-bound connection. */
    def runInTransaction(
        conn: MysqlConnection,
        stmts: Chunk[PipelineStmt]
    )(using Frame): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        runOnConnection(conn, stmts)

    // --- Internal helpers ---

    /** Executes a single [[PipelineStmt]] and wraps the result as pipeline result.
      *
      * Uses `Abort.run[SqlException]` to isolate per-statement errors: a server-level error becomes [[kyo.Result.Failure]]; a
      * connection-level panic is re-raised via `Abort.error`.
      *
      * Delegates to [[ExtendedQueryExchange.prepareAndExecute]] (a single prepare+execute round-trip per statement) rather than calling the
      * higher-level `query`/`execute` helpers to avoid a redundant cache lookup.
      */
    private def executeOne(
        conn: MysqlConnection,
        stmt: PipelineStmt
    )(using Frame): Result[SqlException, SqlClient.PipelineBuilder.Outcome] < (Async & Abort[SqlException]) =
        Abort.run[SqlException] {
            conn.drainPendingCloses.andThen(
                conn.serverCapabilities.get.flatMap { caps =>
                    conn.connectionId.get.flatMap { cid =>
                        val deprecateEof = (caps & Capabilities.CLIENT_DEPRECATE_EOF) != 0L
                        ExtendedQueryExchange.prepareAndExecute(
                            conn.channel,
                            conn.preparedStmts,
                            stmt.sql,
                            stmt.params,
                            deprecateEof,
                            Maybe(cid)
                        ).map { case (mysqlRows, affectedRows, _) =>
                            if mysqlRows.nonEmpty then
                                val sqlRows = mysqlRows.map { r =>
                                    val fields = r.columns.map { column =>
                                        FieldDescription(
                                            name = column.name,
                                            tableOid = 0,
                                            columnAttr = 0,
                                            dataType = 0,
                                            dataTypeSize = 0,
                                            typeModifier = 0,
                                            formatCode = 0
                                        )
                                    }
                                    new SqlRow(r.values, fields, Format.Text)
                                }
                                (Result.Success(SqlClient.PipelineBuilder.Outcome(
                                    sqlRows,
                                    0L
                                )): Result[SqlException, SqlClient.PipelineBuilder.Outcome])
                            else
                                (Result.Success(SqlClient.PipelineBuilder.Outcome(
                                    Chunk.empty,
                                    affectedRows
                                )): Result[SqlException, SqlClient.PipelineBuilder.Outcome]
                            )
                        }
                    }
                }
            )
        }.flatMap {
            case Result.Success(r) => r
            case Result.Failure(e) => Result.Failure(e)
            case Result.Panic(t) =>
                java.lang.System.err.println(s"[kyo-sql] MysqlPipelineExchange: unexpected panic: ${t.getMessage}")
                Abort.error(Result.Panic(t))
        }

end MysqlPipelineExchange
