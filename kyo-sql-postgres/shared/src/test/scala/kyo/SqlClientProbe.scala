package kyo

import kyo.Sql.BoundValue
import kyo.db.Connection
import kyo.db.Idiom
import kyo.db.Runtime
import kyo.internal.TransactionContext
import kyo.internal.client.SqlConnectionPool
import kyo.internal.postgres.PostgresSqlConnection

/** A recording [[kyo.db.Connection]] plus a [[PostgresClient]] that routes statements to it, for the client-level tests whose
  * subject is routing rather than either wire protocol.
  *
  * The client is the real one: it renders every statement through its own dialect and the server version it is told to report, so an
  * assertion here is on the SQL and the binds a server would actually receive. Only the connection underneath is a probe, installed through
  * [[SqlClient.txLocal]] so a statement takes it instead of leasing one. The pool the client carries is therefore never asked for a
  * connection, and its factory panics to say so rather than opening a socket.
  */
private[kyo] object SqlClientProbe:

    /** A statement as the SPI received it: the rendered SQL and the values bound to it. */
    final case class Statement(sql: String, params: Chunk[Any]) derives CanEqual

    object Statement:
        def of(sql: String, params: Chunk[BoundValue[?]]): Statement = Statement(sql, params.map(_.value))

    /** One SPI call the probe was asked to make. */
    enum Call derives CanEqual:
        /** [[kyo.db.Connection.pipelined]] with the statements it received, in submission order. */
        case Pipelined(stmts: Chunk[Statement])

        /** [[kyo.db.Connection.streamQuery]] with the statement and the batch size it received. */
        case Streamed(stmt: Statement, batchSize: Int)
    end Call

    /** Records every SPI call into `calls`, and answers a query with `rows`.
      *
      * `pipelined` reports one success per statement, with the statement's 1-based position as its affected-row count, so a test can tell
      * the results apart by position without a server.
      */
    final class Probe(calls: AtomicRef[Chunk[Call]], rows: Chunk[SqlRow]) extends Connection:

        def id: Long = 1L

        private def record(call: Call)(using Frame): Unit < Sync =
            calls.getAndUpdate(_.appended(call)).unit

        def pipelined(stmts: Chunk[(String, Chunk[BoundValue[?]])])(using
            Frame
        ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
            record(Call.Pipelined(stmts.map(Statement.of))).andThen {
                Chunk.from(stmts.toSeq.zipWithIndex.map { case (_, idx) =>
                    Result.Success(SqlClient.PipelineBuilder.Outcome(Chunk.empty, (idx + 1).toLong))
                })
            }

        def streamQuery(sql: String, params: Chunk[BoundValue[?]], batchSize: Int)(using
            Frame
        ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
            Stream[SqlRow, Async & Abort[SqlException] & Scope](
                record(Call.Streamed(Statement.of(sql, params), batchSize)).andThen(Stream.init(rows).emit)
            )

        // --- lifecycle ---

        def isOpen(using Frame): Boolean < Sync           = true
        def close(using Frame): Unit < Async              = ()
        def closeNow(using Frame, AllowUnsafe): Unit      = ()
        def inFlight(using AllowUnsafe): Boolean          = false
        def inOpenTransaction(using AllowUnsafe): Boolean = false

        // --- members no probe-driven leaf reaches ---

        private def unused[A](member: String)(using Frame): A < (Async & Abort[SqlException]) =
            Abort.panic(new AssertionError(s"SqlClientProbe: $member is not driven by this harness"))

        def extendedQuery(sql: String, params: Chunk[BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
            unused("extendedQuery")
        def extendedExecute(sql: String, params: Chunk[BoundValue[?]])(using Frame): Long < (Async & Abort[SqlException]) =
            unused("extendedExecute")
        def extendedExecuteInsert(sql: String, params: Chunk[BoundValue[?]])(using
            Frame
        ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) = unused("extendedExecuteInsert")
        def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) = unused("simpleQuery")
        def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException])        = unused("simpleExecute")
        def beginTransaction(isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(using
            Frame
        ): Unit < (Async & Abort[SqlException]) = unused("beginTransaction")
        def commitTransaction(using Frame): Unit < (Async & Abort[SqlException])                 = unused("commitTransaction")
        def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException])               = unused("rollbackTransaction")
        def savepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])           = unused("savepoint")
        def releaseSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException])    = unused("releaseSavepoint")
        def rollbackToSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) = unused("rollbackToSavepoint")
        def ping(using Frame): Unit < (Async & Abort[SqlException])                              = unused("ping")
        def resetSession(using Frame): Unit < (Async & Abort[SqlException])                      = unused("resetSession")
        def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException]) =
            unused("acquireAdvisoryLock")
        def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException]) = unused("releaseAdvisoryLock")
        def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException])                 = unused("cancelInFlight")
        def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException])      = unused("rollbackIfOpenTransaction")
        def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException])                 = unused("drainToIdle")
        def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException])   = unused("serverVersion")
    end Probe

    /** The version the probe client reports, high enough that no dialect capability gate narrows the rendered SQL. */
    val serverVersion: Idiom.ServerVersion = Idiom.ServerVersion(16, 2, 0)

    /** Runs `f` with a Postgres client whose statements route to a probe connection, and the probe's recorded calls.
      *
      * @param config
      *   the client's configuration, which is what `streamQuery`'s implicit batch size is read from
      * @param rows
      *   the rows the probe answers a stream with
      */
    def withClient[A, S](config: SqlConfig = SqlConfig.default, rows: Chunk[SqlRow] = Chunk.empty)(
        f: (PostgresClient, () => Chunk[Call] < Sync) => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        Abort.get(SqlConfig.Url.parse("postgres://probe:probe@127.0.0.1:1/probe")).flatMap { url =>
            AtomicRef.init(Chunk.empty[Call]).flatMap { calls =>
                AtomicBoolean.init(false).flatMap { closedRef =>
                    AtomicRef.init(Maybe(serverVersion)).flatMap { versionRef =>
                        val factory = new Connection.Factory[PostgresSqlConnection]:
                            def open(address: SqlConfig.Address, password: Maybe[String], c: SqlConfig)(using
                                Frame
                            ): PostgresSqlConnection < (Async & Abort[SqlException]) =
                                Abort.panic(new AssertionError("SqlClientProbe: the probe client must never lease a connection"))
                        // Unsafe: SqlConnectionPool.init needs AllowUnsafe for its ring buffer, as it does in
                        // PostgresClient.opened. This pool holds no connection and is never polled.
                        Sync.Unsafe.defer(SqlConnectionPool.init(config, factory, Absent, summon[Frame])).flatMap { pool =>
                            val runtime = new Runtime[PostgresSqlConnection](url, config, pool, closedRef, versionRef)
                            val client  = new PostgresClient(runtime)
                            val probe   = new Probe(calls, rows)
                            DB.run(client) {
                                // The context names `client` because routing is gated on the owning client, so a
                                // fabricated session has to say whose it is. Without that this fixture would look
                                // like it worked while every statement fell through to the pool, whose factory
                                // panics.
                                Meter.initMutexUnscoped.flatMap { meter =>
                                    SqlClient.txLocal.let(Present(TransactionContext(client, probe, 1, Chunk.empty, meter))) {
                                        f(client, () => calls.get)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

end SqlClientProbe
