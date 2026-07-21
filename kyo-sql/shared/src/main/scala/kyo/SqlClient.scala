package kyo

import kyo.*
import kyo.Log
import kyo.SqlSchema
import kyo.SqlSchema.BoundValue
import kyo.internal.SqlBackend
import kyo.internal.SqlRender
import kyo.internal.TransactionContext
import kyo.internal.client.MySqlClientBackend
import kyo.internal.client.PgSqlClientBackend
import kyo.internal.client.SqlClientBackend
import kyo.internal.mysql.BoundMysqlParam
import kyo.internal.mysql.MysqlConnection
import kyo.internal.mysql.MysqlRow
import kyo.internal.mysql.exchange.MysqlPipelineExchange
import kyo.internal.postgres.BoundParam
import kyo.internal.postgres.PostgresConnection
import kyo.internal.postgres.exchange.PipelineExchange
import kyo.internal.postgres.types.EncodingRegistry
import kyo.net.NetTlsConfig

/** Sealed handle for a database connection pool.
  *
  * `SqlClient` is the main entry point for all database operations. Obtain a Postgres client via [[SqlClient.init]] or a MySQL client via
  * [[SqlClient.initMy]]. The concrete subclass ([[PgSqlClient]] or [[MySqlSqlClient]]) carries the backend at the type level, so
  * backend-specific operations (e.g. [[PgSqlClient.copyIn]], [[MySqlSqlClient.loadLocalInfile]]) are available without a cast.
  *
  * ==API surface==
  *
  * Query and execute methods are defined as extension methods below. Backend-specific methods are extension methods on the concrete
  * subclasses.
  *
  * ==Lifecycle==
  *
  * Calling [[SqlClient.init]] acquires a connection pool scoped to the enclosing [[Scope]]. The pool is released when the scope exits.
  *
  * ==Fiber-local active client==
  *
  * Use [[SqlClient.let]] to install a client for a block of code, [[SqlClient.withConfig]] to override config, and [[SqlClient.use]] to
  * access the active client without threading it manually.
  */
sealed abstract class SqlClient:
    self =>
    val backend: SqlClientBackend
    val url: SqlConfig.Url
    val config: SqlConfig
    val closedRef: AtomicBoolean

    /** Returns the server address (host, port, db, user) for this client's pool. */
    def address: SqlConfig.Address = self.url.address

    /** Returns all rows for a parameterised query using the extended protocol.
      *
      * If a transaction is active in the current fiber (via [[SqlClient.txLocal]]), uses the bound connection directly. Otherwise acquires a
      * connection from the pool.
      *
      * NOTE: If the pool has been closed (via [[close]]), this method surfaces [[SqlException.Connection]] rather than [[kyo.Closed]].
      * See [[close]] for the rationale.
      */
    def query(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        self.query(SqlAst.Fragment.lit[Any](sql))

    def query(executable: SqlAst.Executable[?])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        val rendered = SqlRender.render(executable, self.sqlBackend, summon[Frame])
        SqlClient.txLocal.use {
            case Present(ctx: TransactionContext.Pg) =>
                ctx.connection.extendedQuery(rendered.sql, rendered.params.flatMap(SqlClientBackend.boundToPostgres))
            case Present(ctx: TransactionContext.My) =>
                // MySQL transaction: use simple query on the bound connection (params must be empty).
                // For parameterized queries inside a MySQL transaction, use MysqlConnection.extendedQuery directly.
                if rendered.params.nonEmpty then
                    Abort.fail(SqlException.Request(
                        "Parameterized query inside a MySQL transaction requires MysqlConnection.extendedQuery directly.",
                        Maybe.Absent,
                        summon[Frame]
                    ))
                else
                    ctx.connection.simpleQuery(rendered.sql).map(rows =>
                        rows.map(r =>
                            import kyo.internal.postgres.FieldDescription
                            import kyo.internal.postgres.types.Format
                            val fields = r.columns.map(column =>
                                FieldDescription(column.name, 0, 0, 0, 0, 0, 0)
                            )
                            new SqlRow(r.values, fields, Format.Text)
                        )
                    )
            case Absent =>
                SqlClient.local.use { (_, config) =>
                    self.backend.queryBound(self.url.address, self.url.password, rendered.sql, rendered.params, config)
                }
        }
    end query

    /** Executes a DML statement and returns the number of affected rows.
      *
      * If a transaction is active in the current fiber (via [[SqlClient.txLocal]]), uses the bound connection directly. Otherwise acquires a
      * connection from the pool.
      */
    def execute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        self.execute(SqlAst.Fragment.lit[Any](sql))

    def execute(executable: SqlAst.Executable[?])(using Frame): Long < (Async & Abort[SqlException]) =
        val rendered = SqlRender.render(executable, self.sqlBackend, summon[Frame])
        SqlClient.txLocal.use {
            case Present(ctx: TransactionContext.Pg) =>
                ctx.connection.extendedExecute(rendered.sql, rendered.params.flatMap(SqlClientBackend.boundToPostgres))
            case Present(ctx: TransactionContext.My) =>
                // MySQL transaction: route through simple execute (params must be empty).
                // For parameterized DML inside a MySQL transaction, use MysqlConnection.extendedExecute directly.
                if rendered.params.nonEmpty then
                    Abort.fail(SqlException.Request(
                        "Parameterized execute inside a MySQL transaction requires MysqlConnection.extendedExecute directly.",
                        Maybe.Absent,
                        summon[Frame]
                    ))
                else
                    ctx.connection.simpleExecute(rendered.sql)
            case Absent =>
                SqlClient.local.use { (_, config) =>
                    self.backend.executeBound(self.url.address, self.url.password, rendered.sql, rendered.params, config)
                }
        }
    end execute

    /** Streams rows from a parameterised query using the Postgres portal protocol.
      *
      * If a transaction is active in the current fiber (via [[SqlClient.txLocal]]), uses the bound connection directly and does NOT release the
      * connection when the stream ends (the connection is held by the enclosing `transaction { ... }`). Otherwise acquires a dedicated
      * connection from the pool for the duration of the stream.
      */
    def streamQuery(sql: String)(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        self.streamQuery(SqlAst.Fragment.lit[Any](sql))

    def streamQuery(executable: SqlAst.Executable[?])(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            SqlClient.local.use { (_, config) =>
                self.streamQuery(executable, config.streamBatchSize).emit
            }
        )

    def streamQuery(
        executable: SqlAst.Executable[?],
        batchSize: Int
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        val rendered = SqlRender.render(executable, self.sqlBackend, summon[Frame])
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            SqlClient.txLocal.use {
                case Present(ctx: TransactionContext.Pg) =>
                    // Inside a transaction: use the bound connection. Do NOT release it, the
                    // transaction holds the connection for the full transaction lifetime.
                    ctx.connection.streamQuery(rendered.sql, rendered.params.flatMap(SqlClientBackend.boundToPostgres), batchSize).emit
                case Present(_: TransactionContext.My) =>
                    Abort.fail(SqlException.Request(
                        "Use MysqlConnection.streamQuery inside a MySQL transaction",
                        Maybe.Absent,
                        summon[Frame]
                    ))
                case Absent =>
                    SqlClient.local.use { (_, config) =>
                        self.backend.streamBound(
                            self.url.address,
                            self.url.password,
                            rendered.sql,
                            rendered.params,
                            batchSize,
                            config
                        ).emit
                    }
            }
        )
    end streamQuery

    /** Executes `sql` as a raw simple-query against a pooled connection.
      *
      * Intended for migrations and multi-statement scripts that cannot use Bind/Execute. If a transaction is active, uses the bound
      * connection directly.
      */
    def executeRaw(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        SqlClient.txLocal.use {
            case Present(ctx: TransactionContext.Pg) =>
                ctx.connection.simpleExecute(sql)
            case Present(ctx: TransactionContext.My) =>
                // MySQL transaction: simple execute on the bound connection.
                ctx.connection.simpleExecute(sql)
            case Absent =>
                SqlClient.local.use { (_, config) =>
                    self.backend.executeRaw(self.url.address, self.url.password, sql, config)
                }
        }

    /** Returns the [[kyo.internal.SqlBackend]] discriminator for this client.
      *
      * Derived from the URL's driver tag at runtime. Used by the `.run` / `.runDynamic` extension methods on `Query` / `Action` to pick
      * the right rendered SQL string from a [[SqlStatic.BackendSql]] and to summon the right [[BoundValue]] dispatch.
      */
    private[kyo] def sqlBackend: SqlBackend =
        self.url.address.driver match
            case "postgres" => SqlBackend.Postgres
            case "mysql"    => SqlBackend.Mysql
            case other =>
                bug(s"Unknown driver '$other' on active SqlClient")

    /** Renders an AST node into a [[Sql.Rendered]] using this client's backend syntax. The client-driven counterpart to
      * [[SqlAst.SqlAst.renderPostgres]] / [[SqlAst.SqlAst.renderMysql]]: use this when a client is already in hand and the caller wants
      * the SQL text matching whatever engine the client talks to (logging, migration script emission, dry-run inspection).
      */
    def render(ast: SqlAst.SqlAst[?])(using frame: Frame): Sql.Rendered =
        val r = kyo.internal.SqlRender.render(ast, self.sqlBackend, frame)
        Sql.Rendered(r.sql, r.params)

    /** Runs a Postgres query with pre-converted `BoundParam` params. Used by [[kyo.internal.SqlBackendOps.postgres]].
      *
      * Threads [[SqlClient.txLocal]] so transaction-bound connections are reused. `private[kyo]`, callers must already hold `BoundParam`-typed
      * params (i.e. be inside the `kyo.internal` DSL pipeline).
      */
    private[kyo] def executePgQuery(sql: String, params: Chunk[BoundParam[?]])(using
        Frame
    ): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        val pgSql = SqlClient.translatePlaceholders(sql)
        SqlClient.txLocal.use {
            case Present(ctx: TransactionContext.Pg) =>
                ctx.connection.extendedQuery(pgSql, params)
            case Present(myCtx: TransactionContext.My) =>
                if params.nonEmpty then
                    Abort.fail(SqlException.Request(
                        "Parameterized query inside a MySQL transaction requires MysqlConnection.extendedQuery directly.",
                        Maybe.Absent,
                        summon[Frame]
                    ))
                else
                    myCtx.connection.simpleQuery(sql).map(rows =>
                        rows.map(r =>
                            import kyo.internal.postgres.FieldDescription
                            import kyo.internal.postgres.types.Format
                            val fields = r.columns.map(column =>
                                FieldDescription(column.name, 0, 0, 0, 0, 0, 0)
                            )
                            new SqlRow(r.values, fields, Format.Text)
                        )
                    )
            case Absent =>
                SqlClient.local.use { (_, config) =>
                    self.backend.query(self.url.address, self.url.password, pgSql, params, config)
                }
        }
    end executePgQuery

    /** Runs a Postgres DML statement with pre-converted `BoundParam` params. Used by [[kyo.internal.SqlBackendOps.postgres]]. */
    private[kyo] def executePgUpdate(sql: String, params: Chunk[BoundParam[?]])(using Frame): Long < (Async & Abort[SqlException]) =
        val pgSql = SqlClient.translatePlaceholders(sql)
        SqlClient.txLocal.use {
            case Present(ctx: TransactionContext.Pg) =>
                ctx.connection.extendedExecute(pgSql, params)
            case Present(myCtx: TransactionContext.My) =>
                if params.nonEmpty then
                    Abort.fail(SqlException.Request(
                        "Parameterized execute inside a MySQL transaction requires MysqlConnection.extendedExecute directly.",
                        Maybe.Absent,
                        summon[Frame]
                    ))
                else
                    myCtx.connection.simpleExecute(sql)
            case Absent =>
                SqlClient.local.use { (_, config) =>
                    self.backend.execute(self.url.address, self.url.password, pgSql, params, config)
                }
        }
    end executePgUpdate

    /** Streams a Postgres query with pre-converted `BoundParam` params. Used by [[kyo.internal.SqlBackendOps.postgres]]. */
    private[kyo] def streamPgQuery(sql: String, params: Chunk[BoundParam[?]], batchSize: Int)(using
        Frame
    ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        val pgSql = SqlClient.translatePlaceholders(sql)
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            SqlClient.txLocal.use {
                case Present(ctx: TransactionContext.Pg) =>
                    ctx.connection.streamQuery(pgSql, params, batchSize).emit
                case Present(_: TransactionContext.My) =>
                    Abort.fail(SqlException.Request(
                        "Use MysqlConnection.streamQuery inside a MySQL transaction",
                        Maybe.Absent,
                        summon[Frame]
                    ))
                case Absent =>
                    SqlClient.local.use { (_, config) =>
                        self.backend.streamQuery(self.url.address, self.url.password, pgSql, params, batchSize, config).emit
                    }
            }
        )
    end streamPgQuery

    /** Returns the rows of a parameterised query using the backend-agnostic [[BoundValue]] surface, decoded as `A` via the supplied
      * [[SqlSchema]].
      *
      * Threads the fiber-SqlClient.local [[SqlClient.txLocal]] so calls inside a `transaction { ... }` block use the bound connection. Dispatches the
      * backend-specific row reader (`schema.readPostgres` for PG, `schema.readMysql` for MySQL).
      *
      * Used by the `.run` / `.runDynamic` extension methods on `Query[A]`.
      *
      * ==Error types==
      * This method can abort with any [[SqlException]] subtype:
      *   - [[SqlException.Connection]], pool exhausted, TCP failure, or acquire timeout before the query was sent.
      *   - [[SqlException.Request]], SQL serialization or parameter encoding failure before the query was sent.
      *   - [[SqlException.Server]], the database rejected the query and returned an error response.
      *   - [[SqlException.Decode]], the query succeeded but a returned column value could not be converted to the target Scala type.
      *     Each row is decoded independently; a `Decode` failure on one row aborts the entire `Chunk` result. Check the [[SqlSchema]]
      *     derivation or widen the column type to diagnose.
      *   - [[SqlException.Unsupported]], the [[SqlSchema]] decoder called a structural read operation (array, map) that the backend
      *     does not yet implement. Re-derive the schema without the unsupported structural type, or supply a custom decoder via
      *     [[SqlSchema.withDecoder]].
      *
      * @tparam A
      *   the decoded row type; must have a [[SqlSchema]] instance
      */
    private[kyo] def executeBoundQuery[A](sql: String, params: Chunk[BoundValue[?]])(using
        schema: SqlSchema[A],
        frame: Frame
    ): Chunk[A] < (Async & Abort[SqlException]) =
        val rowsK: Chunk[SqlRow] < (Async & Abort[SqlException]) =
            SqlClient.txLocal.use {
                case Present(ctx: TransactionContext.Pg) =>
                    ctx.connection.extendedQuery(sql, params.flatMap(SqlClientBackend.boundToPostgres))
                case Present(ctx: TransactionContext.My) =>
                    ctx.connection.extendedQuery(sql, params.flatMap(SqlClientBackend.boundToMysql)).map { mysqlRows =>
                        mysqlRows.map { r =>
                            import kyo.internal.postgres.FieldDescription
                            import kyo.internal.postgres.types.Format
                            val fields = r.columns.map(col => FieldDescription(col.name, 0, 0, 0, 0, 0, 0))
                            new SqlRow(r.values, fields, Format.Text)
                        }
                    }
                case Absent =>
                    SqlClient.local.use { (_, config) =>
                        self.backend.queryBound(self.url.address, self.url.password, sql, params, config)
                    }
            }
        val isPg = self.url.address.driver == "postgres"
        rowsK.map { rows =>
            Kyo.foreach(rows) { row =>
                val decodeK: A < Abort[SqlException.Decode] =
                    if isPg then schema.readPostgres(row) else schema.readMysql(row)
                Abort.recover[SqlException.Decode](
                    (e: SqlException.Decode) => Abort.fail(e: SqlException),
                    t => Abort.error(Result.Panic(t))
                )(decodeK)
            }
        }
    end executeBoundQuery

    /** Executes a parameterised DML statement using the [[BoundValue]] surface and returns the affected-row count.
      *
      * Threads the fiber-SqlClient.local [[SqlClient.txLocal]] so calls inside a `transaction { ... }` block use the bound connection.
      *
      * Used by the `.run` / `.runDynamic` extension methods on `Update[T, F]` / `Delete[T, F]`.
      */
    private[kyo] def executeBoundUpdate(sql: String, params: Chunk[BoundValue[?]])(using
        Frame
    ): Long < (Async & Abort[SqlException]) =
        SqlClient.txLocal.use {
            case Present(ctx: TransactionContext.Pg) =>
                ctx.connection.extendedExecute(sql, params.flatMap(SqlClientBackend.boundToPostgres))
            case Present(ctx: TransactionContext.My) =>
                ctx.connection.extendedExecute(sql, params.flatMap(SqlClientBackend.boundToMysql))
            case Absent =>
                SqlClient.local.use { (_, config) =>
                    self.backend.executeBound(self.url.address, self.url.password, sql, params, config)
                }
        }

    /** Executes a parameterised INSERT statement using the [[BoundValue]] surface and returns an [[InsertResult]].
      *
      * Threads the fiber-SqlClient.local [[SqlClient.txLocal]] so calls inside a `transaction { ... }` block use the bound connection.
      *
      * Used by the `.run` / `.runDynamic` extension methods on `Insert[T, F]`.
      */
    private[kyo] def executeBoundInsert(sql: String, params: Chunk[BoundValue[?]])(using
        Frame
    ): InsertResult < (Async & Abort[SqlException]) =
        SqlClient.txLocal.use {
            case Present(ctx: TransactionContext.Pg) =>
                ctx.connection.extendedExecuteInsert(sql, params.flatMap(SqlClientBackend.boundToPostgres))
            case Present(ctx: TransactionContext.My) =>
                ctx.connection.extendedExecuteInsert(sql, params.flatMap(SqlClientBackend.boundToMysql))
            case Absent =>
                SqlClient.local.use { (_, config) =>
                    self.backend.executeInsert(self.url.address, self.url.password, sql, params, config)
                }
        }

    /** Executes a pre-rendered MySQL DML statement with MySQL-encoded parameters.
      *
      * Called by [[SqlBackendOps.mysql]] when `B = Mysql`. `private[kyo]` to keep this internal.
      */
    private[kyo] def executeMysqlFrag(sql: String, params: Chunk[BoundMysqlParam[?]])(using
        Frame
    ): Long < (Async & Abort[SqlException]) =
        SqlClient.local.use { (_, config) =>
            self.backend.executeMysql(self.url.address, self.url.password, sql, params, config)
        }

    /** Returns all rows from a pre-rendered MySQL query with MySQL-encoded parameters.
      *
      * Called by [[SqlBackendOps.mysql]] when `B = Mysql`. Returns [[Chunk[SqlRow]]] for unified downstream decoding. `private[kyo]` to
      * keep this internal.
      */
    private[kyo] def queryMysqlFrag(sql: String, params: Chunk[BoundMysqlParam[?]])(using
        Frame
    ): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        SqlClient.local.use { (_, config) =>
            self.backend.queryMysql(self.url.address, self.url.password, sql, params, config)
        }

    /** Streams rows from a pre-rendered MySQL query with MySQL-encoded parameters.
      *
      * Called by [[SqlBackendOps.mysql]] when `B = Mysql`. `private[kyo]` to keep this internal.
      */
    private[kyo] def streamQueryMysqlFrag(
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        batchSize: Int
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            SqlClient.local.use { (_, config) =>
                self.backend.streamQueryMysqlRows(self.url.address, self.url.password, sql, params, batchSize, config).emit
            }
        )

    /** Releases all pooled connections, waiting up to `gracePeriod` for in-flight queries to complete before force-closing.
      *
      * The canonical close variant. Marks the pool as closed (preventing new connection acquisitions) and then waits up to
      * `gracePeriod` for any in-flight queries to return their connections. After the grace period, any remaining connections are
      * force-closed. Idempotent, a second call is a no-op.
      *
      * @param gracePeriod
      *   maximum time to wait for in-flight queries to complete; `Duration.Zero` forces an immediate close
      *
      * NOTE: Operations attempted after `close` (including operations on other methods of this client) will surface as
      * [[SqlException.Connection]], not [[kyo.Closed]]. This is a deliberate design choice: `SqlClient`'s entire public surface is
      * uniformly typed `Abort[SqlException]`, and `SqlException.Connection` correctly models "cannot reach the database" regardless of
      * whether the cause is a network failure or a closed pool. `kyo.Closed` is the idiom for kernel concurrency primitives
      * (`Channel`/`Queue`/`Hub`); it is not a subtype of `SqlException` and adding it to every method's effect row would double every
      * caller's error-handling burden for one failure mode already covered.
      */
    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        self.closedRef.compareAndSet(false, true).flatMap {
            case false => () // already closed, idempotent no-op
            case true  => self.backend.closeAll(gracePeriod)
        }

    /** Releases all pooled connections using the config's `closeGrace` duration (default 30 seconds).
      *
      * Delegates to `close(config.closeGrace)`. Idempotent, a second call is a no-op.
      */
    def close(using Frame): Unit < Async =
        self.close(self.config.closeGrace)

    /** Returns `true` if [[close]] has been called on this client, `false` otherwise.
      *
      * The predicate reflects only the SqlClient.local closed flag set by [[close]] / [[closeNow]]; it does not probe the underlying pool or
      * network.
      */
    def isClosed(using Frame): Boolean < Sync = self.closedRef.get

    /** Releases all pooled connections immediately without waiting for in-flight queries.
      *
      * Delegates to `close(Duration.Zero)`. Idempotent.
      */
    def closeNow(using Frame): Unit < Async =
        self.close(Duration.Zero)

    /** Returns the [[kyo.SqlClient.Metrics]] instance used by this client.
      *
      * Use for observability tooling and testing. The returned instance is the same one instrumented by the backend, counters and
      * histograms reflect the real operation history of this client.
      */
    def metrics: kyo.SqlClient.Metrics =
        self.backend.metrics

    /** Sends a [[CancelRequest]] on a fresh TCP connection to interrupt a running query.
      *
      * The `handle` is obtained from [[cancellableQuery]]. This method opens a brand-new TCP connection (never acquires from the pool),
      * sends the 16-byte cancel packet, and closes the connection. The server will interrupt the query identified by the handle's
      * `(processId, secretKey)` pair, causing the query fiber to receive a [[SqlException.Server]] with SQLSTATE `57014`.
      *
      * If the query has already completed, the cancel is a no-op (the server finds no matching active query).
      */
    def cancel(handle: SqlCancelHandle)(using Frame): Unit < (Async & Abort[SqlException]) =
        handle match
            case h: SqlCancelHandle.Pg => self.backend.cancel(h)
            case h: SqlCancelHandle.My => self.backend.cancelMysql(h, self.url.password, self.config)

    /** Subscribes to PostgreSQL `LISTEN`/`NOTIFY` notifications on the named channel.
      *
      * Acquires a dedicated connection from the pool, sends `LISTEN <channel>`, and returns a [[Stream]] that emits one
      * [[Notification]] per `NOTIFY` message received on that channel. When the stream ends (the enclosing [[Scope]] exits, or the
      * caller calls `.take(n)`), `UNLISTEN <channel>` is sent and the connection is released back to the pool.
      *
      * This method does `LISTEN channel` automatically. To send `NOTIFY`, use a separate client call or a second connection.
      *
      * @param channel
      *   PostgreSQL channel name (case-sensitive; will be quoted to preserve case)
      */
    def notifications(channel: String)(using Frame): Stream[SqlNotification, Async & Abort[SqlException] & Scope] =
        Stream[SqlNotification, Async & Abort[SqlException] & Scope](
            SqlClient.local.use { (_, config) =>
                self.backend.notificationStream(self.url.address, self.url.password, channel, config).emit
            }
        )

    /** Runs `body` inside a database transaction.
      *
      * Acquires a single connection from the pool and binds it to the fiber-SqlClient.local [[SqlClient.txLocal]] for the duration of `body`. All
      * `query`/`execute`/`streamQuery` calls within `body` automatically use the bound connection.
      *
      * On success: sends `COMMIT`. On `Abort[SqlException]`: sends `ROLLBACK` and re-raises the error. On `Result.Panic` (unhandled
      * exception): sends `ROLLBACK` and re-raises.
      *
      * ==Nested transactions==
      *
      * If `transaction { ... transaction { ... } ... }` is detected (the inner call sees an active [[TransactionContext]] from
      * [[SqlClient.txLocal]]), the inner call uses a `SAVEPOINT` instead of a new `BEGIN`:
      *
      *   - Inner begin → `SAVEPOINT sp_<uuid>`
      *   - Inner success → `RELEASE SAVEPOINT sp_<uuid>`
      *   - Inner failure → `ROLLBACK TO SAVEPOINT sp_<uuid>` (outer transaction continues)
      *
      * The inner call does NOT acquire a new connection, it reuses the outer's.
      *
      * WARNING: The `isolation` parameter is silently ignored on nested transactions; PostgreSQL and MySQL do not support per-savepoint
      * isolation levels.
      *
      * @param isolation
      *   optional isolation level; [[Absent]] uses the server default (PostgreSQL: `READ COMMITTED`)
      * @param readOnly
      *   if `true`, sends `BEGIN … READ ONLY` (or just reuses the outer connection for nested calls)
      * @param body
      *   the computation to run inside the transaction
      */
    private def transactionImpl[A, S](
        isolation: Maybe[SqlIsolationLevel],
        readOnly: Boolean,
        body: A < S
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        SqlClient.txLocal.use {
            case Present(ctx: TransactionContext.Pg) =>
                // --- Nested Postgres transaction: use a SAVEPOINT ---
                Random.nextStringAlphanumeric(20).flatMap { suffix =>
                    val spName = s"sp_$suffix"
                    val conn   = ctx.connection
                    val innerCtx = ctx.copy(
                        depth = ctx.depth + 1,
                        savepointStack = spName :: ctx.savepointStack
                    )
                    // isolation is silently ignored for nested savepoints (PG limitation).
                    conn.savepointTransaction(spName).andThen {
                        Abort.run[Any](
                            SqlClient.txLocal.let(Present(innerCtx))(body)
                        ).flatMap {
                            case Result.Success(a) =>
                                conn.releaseSavepointTransaction(spName).andThen(a)
                            case Result.Failure(e) =>
                                conn.rollbackToSavepointTransaction(spName).andThen {
                                    e match
                                        case sqlEx: SqlException => Abort.fail(sqlEx)
                                        case other =>
                                            Abort.error(Result.Panic(new RuntimeException(
                                                s"Transaction body aborted with non-SqlException failure: $other"
                                            )))
                                }
                            case Result.Panic(t) =>
                                conn.rollbackToSavepointTransaction(spName).andThen(Abort.error(Result.Panic(t)))
                        }
                    }
                }

            case Present(ctx: TransactionContext.My) =>
                // --- Nested MySQL transaction: use a SAVEPOINT ---
                Random.nextStringAlphanumeric(20).flatMap { suffix =>
                    val spName = s"sp_$suffix"
                    val conn   = ctx.connection
                    val innerCtx = ctx.copy(
                        depth = ctx.depth + 1,
                        savepointStack = spName :: ctx.savepointStack
                    )
                    // isolation is silently ignored for nested savepoints (MySQL limitation).
                    self.backend.mysqlSavepointTransaction(conn, spName).andThen {
                        Abort.run[Any](
                            SqlClient.txLocal.let(Present(innerCtx))(body)
                        ).flatMap {
                            case Result.Success(a) =>
                                self.backend.mysqlReleaseSavepoint(conn, spName).andThen(a)
                            case Result.Failure(e) =>
                                self.backend.mysqlRollbackToSavepoint(conn, spName).andThen {
                                    e match
                                        case sqlEx: SqlException => Abort.fail(sqlEx)
                                        case other =>
                                            Abort.error(Result.Panic(new RuntimeException(
                                                s"Transaction body aborted with non-SqlException failure: $other"
                                            )))
                                }
                            case Result.Panic(t) =>
                                self.backend.mysqlRollbackToSavepoint(conn, spName).andThen(Abort.error(Result.Panic(t)))
                        }
                    }
                }

            case Absent =>
                // --- Outermost transaction: acquire connection from pool ---
                // Dispatch on the backend type at runtime: Postgres uses `withConnection`;
                // MySQL uses `withMysqlConnection`.
                SqlClient.local.use { (_, config) =>
                    self.backend match
                        case _: MySqlClientBackend =>
                            self.backend.withMysqlConnection(self.url.address, self.url.password, config) { conn =>
                                val ctx = TransactionContext.My(conn, 0, Nil)
                                conn.connectionId.get.flatMap { connId =>
                                    Log.debug(s"kyo.sql: tx begin connection=$connId").andThen(
                                        conn.beginTransaction(isolation, readOnly).andThen {
                                            Abort.run[Any](
                                                SqlClient.txLocal.let(Present(ctx))(body)
                                            ).flatMap {
                                                case Result.Success(a) =>
                                                    Log.debug(
                                                        s"kyo.sql: tx commit connection=$connId"
                                                    ).andThen(conn.commitTransaction).andThen(a)
                                                case Result.Failure(e) =>
                                                    Log.debug(
                                                        s"kyo.sql: tx rollback connection=$connId"
                                                    ).andThen(conn.rollbackTransaction).andThen {
                                                        e match
                                                            case sqlEx: SqlException => Abort.fail(sqlEx)
                                                            case other =>
                                                                Abort.error(Result.Panic(new RuntimeException(
                                                                    s"Transaction body aborted with non-SqlException failure: $other"
                                                                )))
                                                    }
                                                case Result.Panic(t) =>
                                                    Log.debug(
                                                        s"kyo.sql: tx rollback connection=$connId"
                                                    ).andThen(conn.rollbackTransaction).andThen(Abort.error(Result.Panic(t)))
                                            }
                                        }
                                    )
                                }
                            }
                        case _ =>
                            self.backend.withConnection(self.url.address, self.url.password, config) { conn =>
                                val ctx = TransactionContext.Pg(conn, 0, Nil)
                                Log.debug(s"kyo.sql: tx begin connection=${conn.processId}").andThen(
                                    conn.beginTransaction(isolation, readOnly).andThen {
                                        Abort.run[Any](
                                            SqlClient.txLocal.let(Present(ctx))(body)
                                        ).flatMap {
                                            case Result.Success(a) =>
                                                Log.debug(
                                                    s"kyo.sql: tx commit connection=${conn.processId}"
                                                ).andThen(conn.commitTransaction).andThen(a)
                                            case Result.Failure(e) =>
                                                Log.debug(
                                                    s"kyo.sql: tx rollback connection=${conn.processId}"
                                                ).andThen(conn.rollbackTransaction).andThen {
                                                    e match
                                                        case sqlEx: SqlException => Abort.fail(sqlEx)
                                                        case other =>
                                                            Abort.error(Result.Panic(new RuntimeException(
                                                                s"Transaction body aborted with non-SqlException failure: $other"
                                                            )))
                                                }
                                            case Result.Panic(t) =>
                                                Log.debug(
                                                    s"kyo.sql: tx rollback connection=${conn.processId}"
                                                ).andThen(conn.rollbackTransaction).andThen(Abort.error(Result.Panic(t)))
                                        }
                                    }
                                )
                            }
                }
        }
    end transactionImpl

    /** Typed-error sibling of [[transactionImpl]]. Catches both `SqlException` and a caller-provided `E` failure via
      * `Abort.run[E | SqlException]`, re-failing each branch on its respective slot of the output's `Abort[SqlException | E]`. Rollback
      * fires for any non-Success outcome (typed failure or panic). Mirrors the three transaction-context branches (Pg savepoint / My
      * savepoint / outermost) of [[transactionImpl]].
      */
    private def transactionTypedImpl[E, A, S](
        isolation: Maybe[SqlIsolationLevel],
        readOnly: Boolean,
        body: A < (S & Abort[E])
    )(using ConcreteTag[E], Frame): A < (S & Async & Abort[SqlException | E]) =
        SqlClient.txLocal.use {
            case Present(ctx: TransactionContext.Pg) =>
                Random.nextStringAlphanumeric(20).flatMap { suffix =>
                    val spName = s"sp_$suffix"
                    val conn   = ctx.connection
                    val innerCtx = ctx.copy(
                        depth = ctx.depth + 1,
                        savepointStack = spName :: ctx.savepointStack
                    )
                    conn.savepointTransaction(spName).andThen {
                        Abort.run[E | SqlException](
                            SqlClient.txLocal.let(Present(innerCtx))(body)
                        ).flatMap {
                            case Result.Success(a) =>
                                conn.releaseSavepointTransaction(spName).andThen(a)
                            case Result.Failure(e) =>
                                conn.rollbackToSavepointTransaction(spName).andThen(Abort.fail[SqlException | E](e))
                            case Result.Panic(t) =>
                                conn.rollbackToSavepointTransaction(spName).andThen(Abort.error(Result.Panic(t)))
                        }
                    }
                }

            case Present(ctx: TransactionContext.My) =>
                Random.nextStringAlphanumeric(20).flatMap { suffix =>
                    val spName = s"sp_$suffix"
                    val conn   = ctx.connection
                    val innerCtx = ctx.copy(
                        depth = ctx.depth + 1,
                        savepointStack = spName :: ctx.savepointStack
                    )
                    self.backend.mysqlSavepointTransaction(conn, spName).andThen {
                        Abort.run[E | SqlException](
                            SqlClient.txLocal.let(Present(innerCtx))(body)
                        ).flatMap {
                            case Result.Success(a) =>
                                self.backend.mysqlReleaseSavepoint(conn, spName).andThen(a)
                            case Result.Failure(e) =>
                                self.backend.mysqlRollbackToSavepoint(conn, spName).andThen(Abort.fail[SqlException | E](e))
                            case Result.Panic(t) =>
                                self.backend.mysqlRollbackToSavepoint(conn, spName).andThen(Abort.error(Result.Panic(t)))
                        }
                    }
                }

            case Absent =>
                SqlClient.local.use { (_, config) =>
                    self.backend match
                        case _: MySqlClientBackend =>
                            self.backend.withMysqlConnection(self.url.address, self.url.password, config) { conn =>
                                val ctx = TransactionContext.My(conn, 0, Nil)
                                conn.connectionId.get.flatMap { connId =>
                                    Log.debug(s"kyo.sql: tx-typed begin connection=$connId").andThen(
                                        conn.beginTransaction(isolation, readOnly).andThen {
                                            Abort.run[E | SqlException](
                                                SqlClient.txLocal.let(Present(ctx))(body)
                                            ).flatMap {
                                                case Result.Success(a) =>
                                                    Log.debug(s"kyo.sql: tx-typed commit connection=$connId")
                                                        .andThen(conn.commitTransaction).andThen(a)
                                                case Result.Failure(e) =>
                                                    Log.debug(s"kyo.sql: tx-typed rollback connection=$connId")
                                                        .andThen(conn.rollbackTransaction)
                                                        .andThen(Abort.fail[SqlException | E](e))
                                                case Result.Panic(t) =>
                                                    Log.debug(s"kyo.sql: tx-typed rollback connection=$connId")
                                                        .andThen(conn.rollbackTransaction)
                                                        .andThen(Abort.error(Result.Panic(t)))
                                            }
                                        }
                                    )
                                }
                            }
                        case _ =>
                            self.backend.withConnection(self.url.address, self.url.password, config) { conn =>
                                val ctx = TransactionContext.Pg(conn, 0, Nil)
                                Log.debug(s"kyo.sql: tx-typed begin connection=${conn.processId}").andThen(
                                    conn.beginTransaction(isolation, readOnly).andThen {
                                        Abort.run[E | SqlException](
                                            SqlClient.txLocal.let(Present(ctx))(body)
                                        ).flatMap {
                                            case Result.Success(a) =>
                                                Log.debug(s"kyo.sql: tx-typed commit connection=${conn.processId}")
                                                    .andThen(conn.commitTransaction).andThen(a)
                                            case Result.Failure(e) =>
                                                Log.debug(s"kyo.sql: tx-typed rollback connection=${conn.processId}")
                                                    .andThen(conn.rollbackTransaction)
                                                    .andThen(Abort.fail[SqlException | E](e))
                                            case Result.Panic(t) =>
                                                Log.debug(s"kyo.sql: tx-typed rollback connection=${conn.processId}")
                                                    .andThen(conn.rollbackTransaction)
                                                    .andThen(Abort.error(Result.Panic(t)))
                                        }
                                    }
                                )
                            }
                }
        }
    end transactionTypedImpl

    /** Runs `body` inside a database transaction using the server default isolation level and read-write mode.
      *
      * Equivalent to `transaction(Absent, false)(body)`.
      *
      * @param body
      *   the computation to run inside the transaction
      */
    def transaction[A, S](body: A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        transactionImpl(Absent, false, body)

    /** Runs `body` inside a database transaction with an explicit isolation level or read-only mode.
      *
      * @param isolation
      *   optional isolation level; `Absent` uses the server default
      * @param readOnly
      *   if `true`, sends `BEGIN … READ ONLY`
      * @param body
      *   the computation to run inside the transaction
      */
    def transaction[A, S](
        isolation: Maybe[SqlIsolationLevel],
        readOnly: Boolean
    )(body: A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        transactionImpl(isolation, readOnly, body)

    /** Runs `body` inside a database transaction, preserving the body's typed [[Abort]] error `E` across rollback.
      *
      * The default [[transaction]] coerces every non-[[SqlException]] failure (including typed `Abort[E]` failures from user code) into
      * [[kyo.Result.Panic]]`(new RuntimeException(...))` because its return type has no slot for `E`. [[transactionTyped]] adds the
      * missing slot: `Abort[SqlException | E]` on the return effect row, so callers can write
      * `transactionTyped[MyError]{ ... Abort.fail(myErr) ... }` and downstream handlers see the original `MyError` value rather than a
      * panic-wrapped string.
      *
      * Rollback semantics are unchanged, any [[SqlException]] OR `E` failure rolls back; only [[Result.Success]] commits.
      *
      * @tparam E
      *   the additional typed error type to preserve through the transaction boundary
      * @tparam A
      *   the body's success type
      * @tparam S
      *   the body's residual effect row (must NOT already contain Abort[SqlException] or Abort[E], those are appended here)
      */
    def transactionTyped[E, A, S](
        body: A < (S & Abort[E])
    )(using ConcreteTag[E], Frame): A < (S & Async & Abort[SqlException | E]) =
        transactionTypedImpl[E, A, S](Absent, false, body)

    /** Typed-error transaction with explicit isolation and read-only mode. See the no-arg [[transactionTyped]] for semantics. */
    def transactionTyped[E, A, S](
        isolation: Maybe[SqlIsolationLevel],
        readOnly: Boolean
    )(body: A < (S & Abort[E]))(using ConcreteTag[E], Frame): A < (S & Async & Abort[SqlException | E]) =
        transactionTypedImpl[E, A, S](isolation, readOnly, body)

    /** Executes multiple statements in a single logical batch.
      *
      * On PostgreSQL, all statements are sent in a single TCP write using the extended-protocol pipeline mode (one Bind/Execute/Sync
      * triple per statement). On MySQL, statements are executed sequentially on the same connection using the extended (binary)
      * protocol; MySQL's wire protocol does not support multi-statement batch writes.
      *
      * The `body` lambda receives a [[SqlClient.PipelineBuilder]] and registers statements via [[SqlClient.PipelineBuilder.execute]] /
      * [[SqlClient.PipelineBuilder.query]]. When the body returns, all registered statements are dispatched and their responses are read in
      * order.
      *
      * Returns one [[SqlStatementResult]] per registered statement, in submission order. A per-statement failure is recorded as
      * [[SqlStatementResult.Failure]] and does NOT abort subsequent statements. A connection-level failure raises `Abort[SqlException]`
      * for the entire pipeline.
      *
      * @param body
      *   lambda that registers statements on the [[SqlClient.PipelineBuilder]]
      * @return
      *   per-statement results in submission order
      */
    def pipeline[S](
        body: SqlClient.PipelineBuilder => Unit < S
    )(using Frame): Chunk[SqlStatementResult] < (Async & Abort[SqlException] & S) =
        val builder = new SqlClient.PipelineBuilder
        body(builder).andThen {
            val rawStmts = builder.drainStmts()
            if rawStmts.isEmpty then Chunk.empty
            else
                SqlClient.txLocal.use {
                    case Present(ctx: TransactionContext.Pg) =>
                        // Inside a Postgres transaction: translate placeholders + encode PG params.
                        val pgStmts = rawStmts.map { case (sql, params) =>
                            (SqlClient.translatePlaceholders(sql), Chunk.from(params).flatMap(SqlClientBackend.boundToPostgres))
                        }
                        ctx.connection.pipelined(pgStmts)

                    case Present(ctx: TransactionContext.My) =>
                        // Inside a MySQL transaction: execute sequentially on the bound connection.
                        val myStmts = rawStmts.map { case (sql, params) =>
                            (sql, Chunk.from(params).flatMap(SqlClientBackend.boundToMysql))
                        }
                        MysqlPipelineExchange.runInTransaction(
                            ctx.connection,
                            myStmts.map { case (sql, params) =>
                                MysqlPipelineExchange.PipelineStmt(sql, params)
                            }
                        )

                    case Absent =>
                        SqlClient.local.use { (_, config) =>
                            self.backend match
                                case _: MySqlClientBackend =>
                                    // MySQL: acquire a connection from the pool and execute sequentially.
                                    val myStmts = rawStmts.map { case (sql, params) =>
                                        MysqlPipelineExchange.PipelineStmt(
                                            sql,
                                            Chunk.from(params).flatMap(SqlClientBackend.boundToMysql)
                                        )
                                    }
                                    self.backend.withMysqlConnection(self.url.address, self.url.password, config) { conn =>
                                        MysqlPipelineExchange.run(conn, myStmts)
                                    }
                                case _ =>
                                    // Postgres: translate placeholders + encode PG params, batch-write.
                                    val pgStmts = rawStmts.map { case (sql, params) =>
                                        (
                                            SqlClient.translatePlaceholders(sql),
                                            Chunk.from(params).flatMap(SqlClientBackend.boundToPostgres)
                                        )
                                    }
                                    self.backend.withConnection(self.url.address, self.url.password, config) { conn =>
                                        conn.pipelined(pgStmts)
                                    }
                        }
                }
            end if
        }
    end pipeline

    /** Probes the server with a minimal round-trip to confirm the client can reach a live database.
      *
      * Acquires a connection from the pool, sends a backend-specific probe (MySQL `COM_PING`; Postgres empty simple-query), and
      * releases the connection back to the pool. Returns `Unit` on success; raises `Abort[SqlException]` on connection, protocol, or
      * server error.
      *
      * Cheaper than `SELECT 1`, MySQL bypasses the SQL parser entirely; Postgres skips parse/plan.
      */
    def ping(using Frame): Unit < (Async & Abort[SqlException]) =
        SqlClient.local.use { (_, config) =>
            self.backend match
                case _: MySqlClientBackend =>
                    self.backend.withMysqlConnection(self.url.address, self.url.password, config) { conn =>
                        conn.ping()
                    }
                case _ =>
                    self.backend.withConnection(self.url.address, self.url.password, config) { conn =>
                        conn.simpleExecute(";").unit
                    }
        }

    /** Convenience wrapper over [[ping]] that returns `true` when the probe succeeds and `false` when it raises a [[SqlException]].
      *
      * Distinct from [[SqlClient.isClosed]] (a SqlClient.local close-flag predicate), `isAlive` reaches the server.
      */
    def isAlive(using Frame): Boolean < (Async & Abort[SqlException]) =
        Abort.run[SqlException](self.ping).map(_.isSuccess)

    /** Explicitly scrubs per-session state (variables, prepared statements, temp tables, listeners, transactions) on a freshly acquired
      * connection.
      *
      *   - MySQL: sends `COM_RESET_CONNECTION`, protocol-native, no SQL round-trip.
      *   - Postgres: runs `DISCARD ALL`, clears prepared statements, session variables, temp tables, listeners, and any open
      *     transaction.
      *
      * Distinct from pool-recycle resets controlled by `SqlConfig.resetOnRelease`; `reset` operates explicitly on the current
      * client and the operation's success/failure is observable to the caller.
      */
    def reset(using Frame): Unit < (Async & Abort[SqlException]) =
        SqlClient.local.use { (_, config) =>
            self.backend match
                case _: MySqlClientBackend =>
                    self.backend.withMysqlConnection(self.url.address, self.url.password, config) { conn =>
                        conn.resetConnection()
                    }
                case _ =>
                    self.backend.withConnection(self.url.address, self.url.password, config) { conn =>
                        conn.simpleExecute("DISCARD ALL").unit
                    }
        }

    /** Acquires a database advisory lock for `key`, runs `body` while holding it, and releases the lock on completion.
      *
      * Because PG and MySQL advisory locks are session-scoped, correct semantics require the acquire and release to happen on the SAME
      * connection. This method pins one connection from the pool for the duration of `body`, so both the acquire SQL and the release SQL
      * execute against that pinned connection (all in-body [[SqlClient]] queries also route to it via [[SqlClient.txLocal]]).
      *
      * PostgreSQL: runs `SELECT pg_advisory_lock(key)` (blocking, session-scoped) on the pinned connection, executes `body`, then always
      * runs `SELECT pg_advisory_unlock(key)` on the same connection (even on `Abort` or panic). Re-entrant on the same session:
      * acquiring twice from the same pinned connection increments an internal counter that the two matching unlocks decrement.
      *
      * MySQL: runs `SELECT GET_LOCK('key', timeoutSeconds)` on the pinned connection. `GET_LOCK` returns 1 on success, 0 on timeout,
      * NULL on error; anything other than 1 raises [[SqlException.Request]] and skips `body`. On success, executes `body`, then always
      * runs `SELECT RELEASE_LOCK('key')` on the same connection.
      *
      * @param key
      *   numeric lock identifier; used as the `bigint` argument to `pg_advisory_lock` on PG, and as the string `key.toString` lock name
      *   for MySQL `GET_LOCK`
      * @param timeout
      *   how long to wait for the lock on MySQL; [[Maybe.Absent]] waits indefinitely (`timeout = -1`); on PG this parameter is unused
      *   (PG session-level advisory locks always block until acquired)
      * @param body
      *   the effect run while the lock is held; nested [[SqlClient]] queries share the pinned connection via [[SqlClient.txLocal]]
      */
    def withAdvisoryLock[A, S](key: Long, timeout: Maybe[Duration] = Maybe.Absent)(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        SqlClient.local.use { (_, config) =>
            self.backend.withAdvisoryLock(self.url.address, self.url.password, key, timeout, config)(body)
        }
    end withAdvisoryLock

    /** Sets auto-commit on or off for the duration of the enclosing [[Scope]], restoring the previous value on exit.
      *
      * Auto-commit semantics differ by backend:
      *
      *   - **MySQL**: has a real server-side `autocommit` variable. `withAutoCommit(false)` emits `SET autocommit=0`; `true` emits `SET
      *     autocommit=1`. The prior value is restored via a matching `SET` when the enclosing [[Scope]] exits.
      *   - **PostgreSQL**: does not have a server-side `autocommit` variable. PostgreSQL is always in autocommit mode at the SQL layer
      *     (each statement is its own transaction unless wrapped in an explicit `BEGIN`/`COMMIT`). `withAutoCommit` on PG is therefore
      *     a client-side no-op, the call succeeds without emitting any SQL, and the [[Scope.ensure]] restore is also a no-op. For
      *     explicit transaction control on PG, use `transaction { ... }` instead.
      *
      * Prefer `transaction(...)` blocks for ACID work; use `withAutoCommit(false)` only when you need MySQL-style implicit-transaction
      * semantics (e.g., to disable mid-batch auto-commits during a bulk load that issues its own COMMITs).
      *
      * @param enabled
      *   true to enable auto-commit, false to disable
      */
    def withAutoCommit(enabled: Boolean)(using Frame): Unit < (Async & Abort[SqlException] & Scope) =
        self.backend match
            case _: MySqlClientBackend =>
                val newVal     = if enabled then 1 else 0
                val oldVal     = if enabled then 0 else 1
                val setSql     = s"SET autocommit=$newVal"
                val restoreSql = s"SET autocommit=$oldVal"
                self.executeRaw(setSql).andThen {
                    Scope.ensure(self.executeRaw(restoreSql).unit)
                }
            case _ =>
                // PostgreSQL has no server-side autocommit GUC. Issuing SET AUTOCOMMIT is invalid
                // (error 42704). PG is always autocommit at the protocol layer, the only way to
                // opt out is an explicit BEGIN/COMMIT, which the transaction(...) API handles.
                // withAutoCommit is a no-op on PG: no SQL is emitted, and the Scope.ensure is empty.
                Scope.ensure(())
    end withAutoCommit

end SqlClient

/** Postgres-backed [[SqlClient]]. Obtain via [[SqlClient.init]] / [[SqlClient.initWith]] / [[SqlClient.initUnscoped]].
  *
  * Backend-specific extension methods (copyIn, copyOut, cancellableQuery, cancellableQueryFiber, parameters, simpleQuery) are available
  * directly on values of this type without a cast.
  */
final class PgSqlClient(
    val backend: PgSqlClientBackend,
    val url: SqlConfig.Url,
    val config: SqlConfig,
    val closedRef: AtomicBoolean
) extends SqlClient:
    self =>

    /** Streams data from `data` into the database using `COPY ... FROM STDIN`.
      *
      * If a transaction is active in the current fiber (via [[SqlClient.txLocal]]), uses the bound connection directly so that the COPY
      * participates in the same transaction (enabling `BEGIN → COPY → ROLLBACK` on the same physical connection). Otherwise acquires a
      * dedicated connection from the pool for the duration of the upload. The connection is returned to the pool after the upload
      * completes (success or failure).
      *
      * On error or cancellation, sends [[CopyFail]] and drains [[ReadyForQuery]] before returning the connection.
      *
      * @param sql
      *   a `COPY ... FROM STDIN` statement (with text, CSV, or binary format options as needed)
      * @param data
      *   the byte stream to upload; each [[Span[Byte]]] element becomes one or more [[CopyData]] packets
      * @return
      *   the number of rows loaded by the server (from the "COPY N" command tag)
      */
    @scala.annotation.targetName("copyInPg")
    def copyIn[S](sql: String, data: Stream[Span[Byte], S])(using Frame): Long < (Async & Abort[SqlException] & S) =
        SqlClient.txLocal.use {
            case Present(ctx: TransactionContext.Pg) =>
                // Inside a Postgres transaction: use the bound connection so COPY participates in the transaction.
                SqlClient.local.use { (_, config) =>
                    ctx.connection.copyIn(sql, data, config.copyOutCleanupTimeout)
                }
            case _ =>
                // Outside a transaction: acquire a dedicated connection from the pool (previous behavior).
                SqlClient.local.use { (_, config) =>
                    self.backend.withConnection(self.url.address, self.url.password, config) { conn =>
                        conn.copyIn(sql, data, config.copyOutCleanupTimeout)
                    }
                }
        }

    /** Executes `COPY ... TO STDOUT` and returns a lazy stream of raw data chunks.
      *
      * Acquires a dedicated connection from the pool; the connection is held for the lifetime of the returned [[Stream]]. Close the
      * stream (or let the enclosing [[Scope]] exit) to release the connection.
      *
      * If the consumer closes the stream before [[CopyDone]] is received, the cleanup path sends [[CopyFail]] and drains
      * [[ReadyForQuery]] before returning the connection (uninterruptible, bounded by `config.copyOutCleanupTimeout`).
      *
      * @param sql
      *   a `COPY ... TO STDOUT` statement
      * @return
      *   a [[Stream]] of [[Span[Byte]]] COPY data payloads; the stream ends when the server sends [[CopyDone]]
      */
    @scala.annotation.targetName("copyOutPg")
    def copyOut(sql: String)(using Frame): Stream[Span[Byte], Async & Abort[SqlException] & Scope] =
        Stream[Span[Byte], Async & Abort[SqlException] & Scope](
            SqlClient.local.use { (_, config) =>
                self.backend.withConnection(self.url.address, self.url.password, config) { conn =>
                    conn.copyOut(sql, config.copyOutCleanupTimeout).emit
                }
            }
        )

    /** Acquires a pooled Postgres connection, runs `sql`, and exposes a [[SqlCancelHandle.Pg]] that can interrupt the query.
      *
      * Returns `(SqlCancelHandle.Pg, Chunk[SqlRow])`. The [[SqlCancelHandle.Pg]] carries the `(processId, secretKey)` pair from
      * `BackendKeyData` and is available immediately. Pass it to [[cancel]] from a separate fiber while this query fiber is active to
      * send a fresh `CancelRequest` TCP connection to the server.
      *
      * Example:
      * {{{
      * for
      *   (handle, rows) <- client.cancellableQuery("SELECT pg_sleep(5)")
      *   _              <- Fiber.init(Async.sleep(1.second).andThen(client.cancel(handle)))
      * yield rows
      * }}}
      *
      * @note
      *   The example above discards the cancel fiber. In production code, store the fiber reference and join or interrupt it after the
      *   query completes to avoid resource leaks.
      */
    @scala.annotation.targetName("cancellableQueryPg0")
    def cancellableQuery(sql: String)(using Frame): (SqlCancelHandle.Pg, Chunk[SqlRow]) < (Async & Abort[SqlException]) =
        cancellableQuery(SqlAst.Fragment.lit[Any](sql))

    @scala.annotation.targetName("cancellableQueryPg")
    def cancellableQuery(
        executable: SqlAst.Executable[?]
    )(using Frame): (SqlCancelHandle.Pg, Chunk[SqlRow]) < (Async & Abort[SqlException]) =
        val rendered = SqlRender.render(executable, self.sqlBackend, summon[Frame])
        SqlClient.local.use { (_, config) =>
            self.backend.withCancelInfo(self.url.address, self.url.password, config) { (conn, pid, secret) =>
                val handle = SqlCancelHandle.Pg(self.url.address, config.tls, pid, secret)
                conn.extendedQuery(rendered.sql, rendered.params.flatMap(SqlClientBackend.boundToPostgres)).map(rows => (handle, rows))
            }
        }
    end cancellableQuery

    /** Acquires a pooled Postgres connection, starts `sql`, and returns the [[SqlCancelHandle.Pg]] together with a [[Fiber]] that
      * resolves to the query rows.
      *
      * No-parameter overload, delegates to the parameterised form with [[Seq.empty]].
      */
    @scala.annotation.targetName("cancellableQueryFiberPg0")
    def cancellableQueryFiber(sql: String)(using
        Frame
    ): (SqlCancelHandle.Pg, Fiber[Chunk[SqlRow], Abort[SqlException]]) < (Async & Abort[SqlException]) =
        cancellableQueryFiber(SqlAst.Fragment.lit[Any](sql))

    /** Acquires a pooled Postgres connection, starts `sql`, and returns the [[SqlCancelHandle.Pg]] together with a [[Fiber]] that
      * resolves to the query rows.
      *
      * Unlike [[cancellableQuery]], which returns `(handle, rows)` as part of the suspended computation result and therefore only
      * yields the handle AFTER the query completes, this overload completes the outer computation as soon as the connection has been
      * acquired and the cancel handle is materialised. The eventual rows are carried by the returned [[Fiber]]. A separate cancelling
      * fiber can read the handle immediately and call [[cancel]] while the query is still in flight.
      *
      * The fiber owns the pooled connection for its lifetime; the connection is returned to the pool when the fiber completes (success,
      * failure, panic, or interrupt). Callers that obtain a handle but do not wish to wait on the rows should still drain the fiber
      * (e.g. via `.get` after `cancel`) or interrupt it to release the connection.
      *
      * Example:
      * {{{
      * for
      *   (handle, queryFiber) <- client.cancellableQueryFiber(sql"SELECT pg_sleep(5)")
      *   _                    <- Async.sleep(1.second)
      *   _                    <- client.cancel(handle)
      *   result               <- Abort.run(queryFiber.get)
      * yield result
      * }}}
      */
    @scala.annotation.targetName("cancellableQueryFiberPg")
    def cancellableQueryFiber(
        executable: SqlAst.Executable[?]
    )(using Frame): (SqlCancelHandle.Pg, Fiber[Chunk[SqlRow], Abort[SqlException]]) < (Async & Abort[SqlException]) =
        val rendered = SqlRender.render(executable, self.sqlBackend, summon[Frame])
        SqlClient.local.use { (_, config) =>
            Fiber.Promise.init[SqlCancelHandle.Pg, Abort[SqlException]].flatMap { handlePromise =>
                Fiber.initUnscoped {
                    Abort.run[SqlException](
                        self.backend.withCancelInfo(self.url.address, self.url.password, config) { (conn, pid, secret) =>
                            val handle = SqlCancelHandle.Pg(self.url.address, config.tls, pid, secret)
                            // Publish the handle BEFORE the query starts so a cancelling fiber can read it
                            // while extendedQuery is still suspended on the wire.
                            handlePromise.complete(Result.succeed(handle)).andThen {
                                conn.extendedQuery(rendered.sql, rendered.params.flatMap(SqlClientBackend.boundToPostgres))
                            }
                        }
                    ).flatMap { result =>
                        result match
                            case Result.Success(rows) =>
                                (rows: Chunk[SqlRow] < (Async & Abort[SqlException]))
                            case Result.Failure(e) =>
                                // The body never reached the success-path complete; surface the failure on the promise
                                // so the outer `handlePromise.get` does not block forever. Idempotent, if the success
                                // path already completed, `complete` returns false and the failure propagates only via
                                // `Abort.fail` to the fiber result.
                                handlePromise.complete(Result.fail(e)).andThen(Abort.fail[SqlException](e))
                            case Result.Panic(t) =>
                                handlePromise.complete(Result.panic(t)).andThen(Abort.error[SqlException](Result.Panic(t)))
                    }
                }.flatMap { fiber =>
                    handlePromise.get.map(handle => (handle, fiber))
                }
            }
        }
    end cancellableQueryFiber

    /** Returns the server startup parameters captured during the connection handshake.
      *
      * Returns the ParameterStatus map (server_version, server_encoding, timezone, integer_datetimes, etc.) populated during startup,
      * plus updates from SET commands. Pool-stable: all connections from the same client return the startup parameter set.
      */
    def parameters(using Frame): Map[String, String] < (Async & Abort[SqlException]) =
        SqlClient.local.use { (_, config) =>
            self.backend.withConnection(self.url.address, self.url.password, config) { conn =>
                conn.parameters.get
            }
        }

    /** Executes `sql` via the Postgres simple-query protocol and returns the rows.
      *
      * Unlike [[query]], this DOES NOT prepare the statement, the query is sent via the Simple Query message (no parse/bind/execute
      * round-trip, no entry in pg_prepared_statements).
      *
      * Use this for one-off SQL where you don't want to pay the prepared-statement cache cost or have the query appear in server-side
      * statement metadata.
      *
      * SQL must be parameterless, passing `?` placeholders is invalid for the simple query protocol. Use [[query]] with [[BoundValue]]
      * params for parameterised queries.
      */
    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        SqlClient.local.use { (_, config) =>
            self.backend.withConnection(self.url.address, self.url.password, config) { conn =>
                conn.simpleQuery(sql)
            }
        }

end PgSqlClient

/** MySQL-backed [[SqlClient]]. Obtain via [[SqlClient.initMy]] / [[SqlClient.initMyWith]] / [[SqlClient.initMyUnscoped]].
  *
  * Backend-specific extension methods (cancellableQuery, cancellableQueryFiber, loadLocalInfile) are available directly on values of this
  * type without a cast.
  */
final class MySqlSqlClient(
    val backend: MySqlClientBackend,
    val url: SqlConfig.Url,
    val config: SqlConfig,
    val closedRef: AtomicBoolean
) extends SqlClient:
    self =>

    /** Acquires a pooled MySQL connection, runs `sql`, and exposes a [[SqlCancelHandle.My]] that can interrupt the query.
      *
      * Returns `(SqlCancelHandle.My, Chunk[SqlRow])`. The [[SqlCancelHandle.My]] is populated from the connection's `connectionId`
      * (assigned during the MySQL handshake) and is available immediately. Pass it to [[cancel]] from a separate fiber while this query
      * fiber is active to send `KILL QUERY <connectionId>`.
      *
      * Example:
      * {{{
      * for
      *   (handle, rows) <- mysqlClient.cancellableQuery("SELECT SLEEP(5)")
      *   _              <- Fiber.init(Async.sleep(1.second).andThen(mysqlClient.cancel(handle)))
      * yield rows
      * }}}
      *
      * @note
      *   The example above discards the cancel fiber. In production code, store the fiber reference and join or interrupt it after the
      *   query completes to avoid resource leaks.
      */
    @scala.annotation.targetName("cancellableQueryMy")
    def cancellableQuery(
        sql: String,
        params: Chunk[BoundMysqlParam[?]]
    )(using Frame): (SqlCancelHandle.My, Chunk[SqlRow]) < (Async & Abort[SqlException]) =
        SqlClient.local.use { (_, config) =>
            self.backend.withCancelInfoMysql(self.url.address, self.url.password, config) { (conn, connId) =>
                val handle = SqlCancelHandle.My(self.url.address, connId)
                conn.extendedQuery(sql, params).map { mysqlRows =>
                    val rows = mysqlRows.map { r =>
                        import kyo.internal.postgres.FieldDescription
                        import kyo.internal.postgres.types.Format
                        val fields = r.columns.map(col => FieldDescription(col.name, 0, 0, 0, 0, 0, 0))
                        new SqlRow(r.values, fields, Format.Text)
                    }
                    (handle, rows)
                }
            }
        }
    end cancellableQuery

    /** Acquires a pooled MySQL connection, runs `executable` (a rendered [[SqlAst.Executable]]), and exposes a [[SqlCancelHandle.My]]
      * that can interrupt the query.
      *
      * The executable is rendered via [[SqlRender]] against the MySQL backend, producing the SQL string and bind parameters. Pass a
      * parameter-free `sql"..."` fragment for no-parameter queries.
      */
    @scala.annotation.targetName("cancellableQueryMyBound")
    def cancellableQuery(
        executable: SqlAst.Executable[?]
    )(using Frame): (SqlCancelHandle.My, Chunk[SqlRow]) < (Async & Abort[SqlException]) =
        val rendered = SqlRender.render(executable, self.sqlBackend, summon[Frame])
        self.cancellableQuery(rendered.sql, rendered.params.flatMap(SqlClientBackend.boundToMysql))
    end cancellableQuery

    /** Acquires a pooled MySQL connection, starts `sql`, and returns the [[SqlCancelHandle.My]] together with a [[Fiber]] that resolves
      * to the query rows.
      *
      * No-parameter overload, delegates to the parameterised form with [[Seq.empty]].
      */
    @scala.annotation.targetName("cancellableQueryFiberMy0")
    def cancellableQueryFiber(sql: String)(using
        Frame
    ): (SqlCancelHandle.My, Fiber[Chunk[SqlRow], Abort[SqlException]]) < (Async & Abort[SqlException]) =
        cancellableQueryFiber(SqlAst.Fragment.lit[Any](sql))

    /** Acquires a pooled MySQL connection, starts `executable`, and returns the [[SqlCancelHandle.My]] together with a [[Fiber]] that
      * resolves to the query rows.
      *
      * Unlike [[cancellableQuery]], which returns `(handle, rows)` as part of the suspended computation result and therefore only
      * yields the handle AFTER the query completes, this overload completes the outer computation as soon as the connection has been
      * acquired and the cancel handle is materialised. The eventual rows are carried by the returned [[Fiber]]. A separate cancelling
      * fiber can read the handle immediately and call [[cancel]] (which sends `KILL QUERY <connectionId>`) while the query is still in
      * flight.
      *
      * The fiber owns the pooled connection for its lifetime; the connection is returned to the pool when the fiber completes (success,
      * failure, panic, or interrupt). Pool sizing must allow at least one additional slot beyond the in-flight queries because
      * [[cancel]] borrows a second connection to send the KILL.
      *
      * Example:
      * {{{
      * for
      *   (handle, queryFiber) <- mysqlClient.cancellableQueryFiber(sql"SELECT SLEEP(5)")
      *   _                    <- Async.sleep(1.second)
      *   _                    <- mysqlClient.cancel(handle)
      *   result               <- Abort.run(queryFiber.get)
      * yield result
      * }}}
      */
    @scala.annotation.targetName("cancellableQueryFiberMy")
    def cancellableQueryFiber(
        executable: SqlAst.Executable[?]
    )(using Frame): (SqlCancelHandle.My, Fiber[Chunk[SqlRow], Abort[SqlException]]) < (Async & Abort[SqlException]) =
        val rendered = SqlRender.render(executable, self.sqlBackend, summon[Frame])
        val myParams = rendered.params.flatMap(SqlClientBackend.boundToMysql)
        SqlClient.local.use { (_, config) =>
            Fiber.Promise.init[SqlCancelHandle.My, Abort[SqlException]].flatMap { handlePromise =>
                Fiber.initUnscoped {
                    Abort.run[SqlException](
                        self.backend.withCancelInfoMysql(self.url.address, self.url.password, config) { (conn, connId) =>
                            val handle = SqlCancelHandle.My(self.url.address, connId)
                            // Publish the handle BEFORE the query starts so a cancelling fiber can read it
                            // while extendedQuery is still suspended on the wire.
                            handlePromise.complete(Result.succeed(handle)).andThen {
                                conn.extendedQuery(rendered.sql, myParams).map { mysqlRows =>
                                    mysqlRows.map { r =>
                                        import kyo.internal.postgres.FieldDescription
                                        import kyo.internal.postgres.types.Format
                                        val fields = r.columns.map(col => FieldDescription(col.name, 0, 0, 0, 0, 0, 0))
                                        new SqlRow(r.values, fields, Format.Text)
                                    }
                                }
                            }
                        }
                    ).flatMap { result =>
                        result match
                            case Result.Success(rows) =>
                                (rows: Chunk[SqlRow] < (Async & Abort[SqlException]))
                            case Result.Failure(e) =>
                                // The body never reached the success-path complete; surface the failure on the promise
                                // so the outer `handlePromise.get` does not block forever.
                                handlePromise.complete(Result.fail(e)).andThen(Abort.fail[SqlException](e))
                            case Result.Panic(t) =>
                                handlePromise.complete(Result.panic(t)).andThen(Abort.error[SqlException](Result.Panic(t)))
                    }
                }.flatMap { fiber =>
                    handlePromise.get.map(handle => (handle, fiber))
                }
            }
        }
    end cancellableQueryFiber

    /** Executes a `LOAD DATA LOCAL INFILE` statement, streaming `data` bytes to the server.
      *
      * The caller supplies the byte stream, use `Stream.from(span)` for in-memory data, `Path.readBytes` for file-backed data, or any
      * other `Stream[Byte, S]` source. The server's filename in the `LOCAL INFILE` SQL is arbitrary; kyo-sql ignores what the server
      * echoes back and always uploads `data` unconditionally.
      *
      * The CLIENT_LOCAL_FILES capability is negotiated automatically. The server must also have `local_infile=ON` (MySQL system
      * variable); otherwise the server rejects the statement with [[SqlException.Server]].
      *
      * @param sql
      *   a `LOAD DATA LOCAL INFILE 'filename' INTO TABLE ...` statement
      * @param data
      *   the byte stream to upload (caller-supplied)
      * @return
      *   the affected-row count from the server's OK packet
      */
    def loadLocalInfile[S](sql: String, data: Stream[Byte, S])(using Frame): Long < (Async & Abort[SqlException] & S) =
        SqlClient.local.use { (_, config) =>
            self.backend.loadLocalInfileMysql(self.url.address, self.url.password, sql, data, config)
        }

end MySqlSqlClient

object SqlClient:

    /** Accumulates pipeline statements for execution via [[SqlClient.pipeline]].
      *
      * Obtain an instance via `client.pipeline { builder => ... }`. Call [[execute]] or [[query]] to register each statement; on body
      * completion the caller flushes all statements in one TCP write.
      *
      * Thread-safety: A [[PipelineBuilder]] is NOT thread-safe. It is always consumed synchronously within the pipeline body before flush.
      */
    final class PipelineBuilder:
        private val _stmts = scala.collection.mutable.ArrayBuffer.empty[(String, Seq[SqlSchema.BoundValue[?]])]

        /** Registers a DML statement for later batch execution. Returns `Unit` within the pipeline body; the actual row-count is returned
          * by the outer `pipeline` call as part of the result [[Chunk]].
          */
        def execute(sql: String): Unit =
            execute(sql, Seq.empty)

        def execute(sql: String, params: Seq[SqlSchema.BoundValue[?]]): Unit =
            val _ = _stmts += ((sql, params))

        /** Registers a query statement for later batch execution. Returns `Unit` within the pipeline body; the rows are returned by the
          * outer `pipeline` call as part of the result [[Chunk]].
          */
        def query(sql: String): Unit =
            query(sql, Seq.empty)

        def query(sql: String, params: Seq[SqlSchema.BoundValue[?]]): Unit =
            val _ = _stmts += ((sql, params))

        /** Returns the accumulated statements as an immutable [[Chunk]]. Called internally by [[SqlClient.pipeline]] after the body
          * returns.
          */
        private[kyo] def drainStmts(): Chunk[(String, Seq[SqlSchema.BoundValue[?]])] =
            Chunk.from(_stmts.toSeq)
    end PipelineBuilder

    /** Encapsulates all Stat counters and histograms for kyo-sql.
      *
      * When `metricsEnabled` is `false`, every method is a no-op (zero allocation beyond the call). When `metricsEnabled` is `true`,
      * metrics are registered under `metricsScope` (default `"kyo.sql"`).
      *
      * Metric names:
      *   - Counters: `connections_acquired`, `connections_released`, `connections_discarded`, `queries_executed`, `queries_failed`,
      *     `retries_attempted`.
      *   - Histograms: `query_duration_ms`, `pool_acquire_wait_ms`.
      */
    final class Metrics(metricsEnabled: Boolean, metricsScope: Maybe[String]):

        private val scopeName: String = metricsScope.getOrElse("kyo.sql")

        // Split scope name on '.' to build nested Stat scopes.
        private val stat: Stat =
            if metricsEnabled then
                val parts = scopeName.split('.')
                if parts.length == 1 then
                    Stat.initScope(parts(0))
                else
                    Stat.initScope(parts(0), parts.drop(1)*)
                end if
            else
                // Dummy stat, never used, but avoids null.
                Stat.initScope("__noop__")

        // --- Counters ---

        private val _connectionsAcquired: Counter =
            if metricsEnabled then stat.initCounter("connections_acquired", "Number of connections acquired from the pool")
            else Metrics.noopCounter

        private val _connectionsReleased: Counter =
            if metricsEnabled then stat.initCounter("connections_released", "Number of connections released back to the pool")
            else Metrics.noopCounter

        private val _connectionsDiscarded: Counter =
            if metricsEnabled then stat.initCounter("connections_discarded", "Number of connections discarded from the pool")
            else Metrics.noopCounter

        private val _queriesExecuted: Counter =
            if metricsEnabled then stat.initCounter("queries_executed", "Number of queries successfully executed")
            else Metrics.noopCounter

        private val _queriesFailed: Counter =
            if metricsEnabled then stat.initCounter("queries_failed", "Number of queries that resulted in an error")
            else Metrics.noopCounter

        private val _retriesAttempted: Counter =
            if metricsEnabled then stat.initCounter("retries_attempted", "Number of retry attempts made")
            else Metrics.noopCounter

        // --- Histograms ---

        private val _queryDurationMs: Histogram =
            if metricsEnabled then stat.initHistogram("query_duration_ms", "Query execution duration in milliseconds")
            else Metrics.noopHistogram

        private val _poolAcquireWaitMs: Histogram =
            if metricsEnabled then
                stat.initHistogram("pool_acquire_wait_ms", "Time spent waiting to acquire a connection from the pool in milliseconds")
            else Metrics.noopHistogram

        // --- Public accessors ---

        def connectionsAcquired: Counter  = _connectionsAcquired
        def connectionsReleased: Counter  = _connectionsReleased
        def connectionsDiscarded: Counter = _connectionsDiscarded
        def queriesExecuted: Counter      = _queriesExecuted
        def queriesFailed: Counter        = _queriesFailed
        def retriesAttempted: Counter     = _retriesAttempted
        def queryDurationMs: Histogram    = _queryDurationMs
        def poolAcquireWaitMs: Histogram  = _poolAcquireWaitMs

        // --- Histogram summary ---

        def queryDurationSummary(using Frame): kyo.stats.internal.Summary < Sync =
            Sync.Unsafe.defer(_queryDurationMs.unsafe.summary())

        def poolAcquireWaitSummary(using Frame): kyo.stats.internal.Summary < Sync =
            Sync.Unsafe.defer(_poolAcquireWaitMs.unsafe.summary())

        // --- Instrumented lifecycle methods ---

        /** Increments `connections_acquired`. */
        def recordAcquire(using Frame): Unit < Sync =
            _connectionsAcquired.inc

        /** Increments `connections_released`. */
        def recordRelease(using Frame): Unit < Sync =
            _connectionsReleased.inc

        /** Increments `connections_discarded`. */
        def recordDiscard(using Frame): Unit < Sync =
            _connectionsDiscarded.inc

        /** Increments `queries_executed`. */
        def recordQueryExecuted(using Frame): Unit < Sync =
            _queriesExecuted.inc

        /** Increments `queries_failed`. */
        def recordQueryFailed(using Frame): Unit < Sync =
            _queriesFailed.inc

        /** Increments `retries_attempted`. */
        def recordRetry(using Frame): Unit < Sync =
            _retriesAttempted.inc

        /** Records `durationMs` in `query_duration_ms`. */
        def recordQueryDuration(durationMs: Long)(using Frame): Unit < Sync =
            _queryDurationMs.observe(durationMs)

        /** Records `waitMs` in `pool_acquire_wait_ms`. */
        def recordPoolAcquireWait(waitMs: Long)(using Frame): Unit < Sync =
            _poolAcquireWaitMs.observe(waitMs)

        /** Runs a query body, timing its execution and incrementing `queries_executed` or `queries_failed` on exit.
          *
          * When disabled, the body is lifted into Sync by `Sync(body)` which is a legal zero-overhead Sync intro. The Sync effect is
          * always in the result type since callers already have Async in scope (Async subsumes Sync), so this adds nothing to the
          * erasure.
          */
        def timedQuery[A, S](body: A < (S & Abort[SqlException]))(using Frame): A < (S & Abort[SqlException] & Sync) =
            if !metricsEnabled then
                body
            else
                Clock.stopwatch.flatMap { sw =>
                    Abort.run[SqlException](body).flatMap { result =>
                        sw.elapsed.flatMap { dur =>
                            _queryDurationMs.observe(dur.toMillis).andThen {
                                result match
                                    case Result.Success(a) =>
                                        _queriesExecuted.inc.andThen(a: A < (S & Abort[SqlException] & Sync))
                                    case Result.Failure(e) =>
                                        _queriesFailed.inc.andThen(Abort.fail[SqlException](e): A < (S & Abort[SqlException] & Sync))
                                    case Result.Panic(t) =>
                                        _queriesFailed.inc.andThen(Abort.error(Result.Panic(t)): A < (S & Abort[SqlException] & Sync))
                            }
                        }
                    }
                }
    end Metrics

    object Metrics:

        /** No-op counter: every method does nothing. Used when `metricsEnabled = false`. */
        private[Metrics] val noopCounter: Counter = new Counter:
            val unsafe                                 = new kyo.stats.internal.UnsafeCounter
            def get(using Frame): Long < Sync          = 0L
            def inc(using Frame): Unit < Sync          = ()
            def add(v: Long)(using Frame): Unit < Sync = ()

        /** No-op histogram: every method does nothing. Used when `metricsEnabled = false`. */
        private[Metrics] val noopHistogram: Histogram = new Histogram:
            val unsafe                                       = new kyo.stats.internal.UnsafeHistogram(Array.empty[Double])
            def observe(v: Long)(using Frame): Unit < Sync   = ()
            def observe(v: Double)(using Frame): Unit < Sync = ()

        /** Construct with defaults: enabled, scope = "kyo.sql". */
        def apply(): Metrics = new Metrics(metricsEnabled = true, metricsScope = Absent)

        /** Construct with explicit enable flag and optional scope override. */
        def apply(metricsEnabled: Boolean, metricsScope: Maybe[String]): Metrics =
            new Metrics(metricsEnabled, metricsScope)
    end Metrics

    // --- Fiber-local active client + config ---

    private val defaultConfig: SqlConfig = SqlConfig.default

    // Stores Maybe[SqlClient] so the "no client" state is represented as Absent, avoiding a null sentinel.
    private[kyo] val local: Local[(Maybe[SqlClient], SqlConfig)] =
        Local.init((Absent, SqlClient.defaultConfig))

    /** Fiber-local active transaction context.
      *
      * [[Absent]] when outside a `transaction { ... }` block. [[Present]] when inside one, queries detect this and use the bound
      * connection directly instead of acquiring from the pool.
      */
    private[kyo] val txLocal: Local[Maybe[TransactionContext]] =
        Local.init(Absent)

    // --- Lifecycle: Postgres ---
    //
    // Lifecycle factories precede fiber-SqlClient.local plumbing per CONTRIBUTING resource-factory convention.
    // All variants delegate downward: init → initWith → create + Scope.ensure(close) + warmup + f(client).

    /** Creates a Postgres [[SqlClient]] scoped to the enclosing [[Scope]].
      *
      * Delegates to `initWith(rawUrl)(identity)`. The pool is released when the enclosing [[Scope]] exits.
      *
      * @param rawUrl
      *   database URL in the form `postgres://user:pw@host:port/db[?opts]`
      */
    def init(rawUrl: String)(using Frame): PgSqlClient < (Async & Scope & Abort[SqlException]) =
        initWith(rawUrl)(identity)

    /** Creates a Postgres [[PgSqlClient]] scoped to the enclosing [[Scope]], using custom config.
      *
      * Delegates to `initWith(rawUrl, config)(identity)`.
      */
    def init(rawUrl: String, config: SqlConfig)(using Frame): PgSqlClient < (Async & Scope & Abort[SqlException]) =
        initWith(rawUrl, config)(identity)

    /** Creates a Postgres [[SqlClient]], registers `Scope.ensure(close)`, applies `f`, and returns.
      *
      * The client is available for the lifetime of the enclosing [[Scope]]. Warmup runs before `f` is called, so connections are ready.
      *
      * Effect set: `Async & Scope & Abort[SqlException] & S` (wider than Channel's `Sync & Scope & S` because warmup is async; auth
      * failures during warm-up surface as [[SqlException.Server]], not wrapped as [[SqlException.Connection]]).
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `postgres://user:pw@host:port/db[?opts]`
      * @param f
      *   function receiving the initialized client
      */
    inline def initWith[B, S](rawUrl: String)(inline f: PgSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Scope & Abort[SqlException]) =
        initWith(rawUrl, SqlConfig.default)(f)

    /** Creates a Postgres [[PgSqlClient]] with custom config, registers `Scope.ensure(close)`, applies `f`, and returns.
      *
      * Effect set: `Async & Scope & Abort[SqlException] & S`.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `postgres://user:pw@host:port/db[?opts]`
      * @param config
      *   config overrides applied on top of URL-derived defaults
      * @param f
      *   function receiving the initialized client
      */
    inline def initWith[B, S](rawUrl: String, config: SqlConfig)(inline f: PgSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Scope & Abort[SqlException]) =
        Abort.get(SqlConfig.Url.parse(rawUrl)).flatMap { url =>
            if url.address.driver != "postgres" then
                Abort.fail(SqlException.Connection(
                    s"SqlClient.init requires a postgres:// URL; got '${url.address.driver}://'. Use SqlClient.initMy for mysql:// URLs.",
                    summon[Frame]
                ))
            else
                SqlClient.sanitizeTypeNames(config.typeNames).flatMap { _ =>
                    SqlClient.mergeConfig(url, config).flatMap { mergedConfig =>
                        // Unsafe: ConnectionPool.init uses AllowUnsafe for ring-buffer initialisation.
                        // Capturing the current Frame so the pool's isAlive/discard callbacks have a call-site.
                        val backend: PgSqlClientBackend < Sync = Sync.Unsafe.defer {
                            SqlClientBackend.initPg(mergedConfig, summon[Frame])
                        }
                        backend.flatMap { (b: PgSqlClientBackend) =>
                            AtomicBoolean.init(false).flatMap { closedRef =>
                                val client = new PgSqlClient(b, url, mergedConfig, closedRef)
                                Scope.ensure(client.close).andThen {
                                    val warmN = mergedConfig.minConnections.min(mergedConfig.maxConnections)
                                    b.warmUp(url.address, url.password, warmN, mergedConfig).andThen(f(client))
                                }
                            }
                        }
                    }
                }
        }
    end initWith

    /** Creates a Postgres [[SqlClient]] with bracket semantics: no [[Scope]] required, close guaranteed via `Sync.ensure`.
      *
      * Warmup runs before `f` is called. The client is closed when `f` returns (success, failure, or panic).
      *
      * Effect set: `Async & Abort[SqlException] & S`, no `Scope` in the set.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `postgres://user:pw@host:port/db[?opts]`
      * @param f
      *   function receiving the initialized client
      */
    inline def use[B, S](rawUrl: String)(inline f: PgSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Abort[SqlException]) =
        use(rawUrl, SqlConfig.default)(f)

    /** Creates a Postgres [[PgSqlClient]] with custom config and bracket semantics: no [[Scope]] required, close guaranteed via
      * `Sync.ensure`.
      *
      * Effect set: `Async & Abort[SqlException] & S`, no `Scope` in the set.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `postgres://user:pw@host:port/db[?opts]`
      * @param config
      *   config overrides applied on top of URL-derived defaults
      * @param f
      *   function receiving the initialized client
      */
    inline def use[B, S](rawUrl: String, config: SqlConfig)(inline f: PgSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Abort[SqlException]) =
        Abort.get(SqlConfig.Url.parse(rawUrl)).flatMap { url =>
            if url.address.driver != "postgres" then
                Abort.fail(SqlException.Connection(
                    s"SqlClient.use requires a postgres:// URL; got '${url.address.driver}://'. Use SqlClient.useMy for mysql:// URLs.",
                    summon[Frame]
                ))
            else
                SqlClient.sanitizeTypeNames(config.typeNames).flatMap { _ =>
                    SqlClient.mergeConfig(url, config).flatMap { mergedConfig =>
                        val backend: PgSqlClientBackend < Sync = Sync.Unsafe.defer {
                            SqlClientBackend.initPg(mergedConfig, summon[Frame])
                        }
                        backend.flatMap { (b: PgSqlClientBackend) =>
                            AtomicBoolean.init(false).flatMap { closedRef =>
                                val client = new PgSqlClient(b, url, mergedConfig, closedRef)
                                val warmN  = mergedConfig.minConnections.min(mergedConfig.maxConnections)
                                // client.close is Async, not Sync, so we cannot use Sync.ensure here.
                                // Instead, Scope.run discharges the Scope effect inline so it does not
                                // appear in the return type, bracket semantics without leaking Scope.
                                Scope.run(
                                    Scope.ensure(client.close).andThen(
                                        b.warmUp(url.address, url.password, warmN, mergedConfig).andThen(f(client))
                                    )
                                )
                            }
                        }
                    }
                }
        }
    end use

    /** Creates a Postgres [[SqlClient]] with no cleanup registered. Delegates to `initUnscopedWith(rawUrl)(identity)`.
      *
      * The caller is responsible for calling `client.close()` when done.
      */
    def initUnscoped(rawUrl: String)(using Frame): PgSqlClient < (Async & Abort[SqlException]) =
        initUnscopedWith(rawUrl)(identity)

    def initUnscoped(rawUrl: String, config: SqlConfig)(using Frame): PgSqlClient < (Async & Abort[SqlException]) =
        initUnscopedWith(rawUrl, config)(identity)

    /** Creates a Postgres [[SqlClient]] with no cleanup registered, applies `f`, and returns.
      *
      * No `Scope.ensure` or `Sync.ensure` is registered. The caller is responsible for calling `client.close()`.
      *
      * Effect set: `Async & Abort[SqlException] & S`.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `postgres://user:pw@host:port/db[?opts]`
      * @param f
      *   function receiving the initialized client
      */
    inline def initUnscopedWith[B, S](rawUrl: String)(inline f: PgSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Abort[SqlException]) =
        initUnscopedWith(rawUrl, SqlConfig.default)(f)

    /** Creates a Postgres [[PgSqlClient]] with custom config, no cleanup registered, applies `f`, and returns.
      *
      * No `Scope.ensure` or `Sync.ensure` is registered. The caller is responsible for calling `client.close()`.
      *
      * Effect set: `Async & Abort[SqlException] & S`.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `postgres://user:pw@host:port/db[?opts]`
      * @param config
      *   config overrides applied on top of URL-derived defaults
      * @param f
      *   function receiving the initialized client
      */
    inline def initUnscopedWith[B, S](rawUrl: String, config: SqlConfig)(inline f: PgSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Abort[SqlException]) =
        Abort.get(SqlConfig.Url.parse(rawUrl)).flatMap { url =>
            if url.address.driver != "postgres" then
                Abort.fail(SqlException.Connection(
                    s"SqlClient.initUnscoped requires a postgres:// URL; got '${url.address.driver}://'. Use SqlClient.initMyUnscoped for mysql:// URLs.",
                    summon[Frame]
                ))
            else
                SqlClient.sanitizeTypeNames(config.typeNames).flatMap { _ =>
                    SqlClient.mergeConfig(url, config).flatMap { mergedConfig =>
                        val backend: PgSqlClientBackend < Sync = Sync.Unsafe.defer {
                            SqlClientBackend.initPg(mergedConfig, summon[Frame])
                        }
                        backend.flatMap { (b: PgSqlClientBackend) =>
                            AtomicBoolean.init(false).flatMap { closedRef =>
                                val client = new PgSqlClient(b, url, mergedConfig, closedRef)
                                val warmN  = mergedConfig.minConnections.min(mergedConfig.maxConnections)
                                b.warmUp(url.address, url.password, warmN, mergedConfig).andThen(f(client))
                            }
                        }
                    }
                }
        }
    end initUnscopedWith

    // --- Lifecycle: MySQL ---

    /** Creates a MySQL [[SqlClient]] scoped to the enclosing [[Scope]].
      *
      * Delegates to `initMyWith(rawUrl)(identity)`. The pool is released when the enclosing [[Scope]] exits.
      *
      * @param rawUrl
      *   database URL in the form `mysql://user:pw@host:port/db[?opts]`
      */
    def initMy(rawUrl: String)(using Frame): MySqlSqlClient < (Async & Scope & Abort[SqlException]) =
        initMyWith(rawUrl)(identity)

    def initMy(rawUrl: String, config: SqlConfig)(using Frame): MySqlSqlClient < (Async & Scope & Abort[SqlException]) =
        initMyWith(rawUrl, config)(identity)

    /** Creates a MySQL [[SqlClient]], registers `Scope.ensure(close)`, applies `f`, and returns.
      *
      * Effect set: `Async & Scope & Abort[SqlException] & S`.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `mysql://user:pw@host:port/db[?opts]`
      * @param f
      *   function receiving the initialized client
      */
    inline def initMyWith[B, S](rawUrl: String)(inline f: MySqlSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Scope & Abort[SqlException]) =
        initMyWith(rawUrl, SqlConfig.default)(f)

    /** Creates a MySQL [[MySqlSqlClient]] with custom config, registers `Scope.ensure(close)`, applies `f`, and returns.
      *
      * Effect set: `Async & Scope & Abort[SqlException] & S`.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `mysql://user:pw@host:port/db[?opts]`
      * @param config
      *   config overrides applied on top of URL-derived defaults
      * @param f
      *   function receiving the initialized client
      */
    inline def initMyWith[B, S](rawUrl: String, config: SqlConfig)(inline f: MySqlSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Scope & Abort[SqlException]) =
        Abort.get(SqlConfig.Url.parse(rawUrl)).flatMap { url =>
            if url.address.driver != "mysql" then
                Abort.fail(SqlException.Connection(
                    s"SqlClient.initMy requires a mysql:// URL; got '${url.address.driver}://'. Use SqlClient.init for postgres:// URLs.",
                    summon[Frame]
                ))
            else
                SqlClient.mergeConfig(url, config).flatMap { mergedConfig =>
                    // Unsafe: ConnectionPool.init uses AllowUnsafe for ring-buffer initialisation.
                    // Capturing the current Frame so the pool's isAlive/discard callbacks have a call-site.
                    val backend: MySqlClientBackend < Sync = Sync.Unsafe.defer {
                        SqlClientBackend.initMy(mergedConfig, summon[Frame])
                    }
                    backend.flatMap { (b: MySqlClientBackend) =>
                        AtomicBoolean.init(false).flatMap { closedRef =>
                            val client = new MySqlSqlClient(b, url, mergedConfig, closedRef)
                            Scope.ensure(client.close).andThen {
                                val warmN = mergedConfig.minConnections.min(mergedConfig.maxConnections)
                                b.warmUp(url.address, url.password, warmN, mergedConfig).andThen(f(client))
                            }
                        }
                    }
                }
        }
    end initMyWith

    /** Creates a MySQL [[SqlClient]] with bracket semantics: no [[Scope]] required, close guaranteed via `Sync.ensure`.
      *
      * Effect set: `Async & Abort[SqlException] & S`, no `Scope` in the set.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `mysql://user:pw@host:port/db[?opts]`
      * @param f
      *   function receiving the initialized client
      */
    inline def useMy[B, S](rawUrl: String)(inline f: MySqlSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Abort[SqlException]) =
        useMy(rawUrl, SqlConfig.default)(f)

    /** Creates a MySQL [[MySqlSqlClient]] with custom config and bracket semantics: no [[Scope]] required, close guaranteed via
      * `Sync.ensure`.
      *
      * Effect set: `Async & Abort[SqlException] & S`, no `Scope` in the set.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `mysql://user:pw@host:port/db[?opts]`
      * @param config
      *   config overrides applied on top of URL-derived defaults
      * @param f
      *   function receiving the initialized client
      */
    inline def useMy[B, S](rawUrl: String, config: SqlConfig)(inline f: MySqlSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Abort[SqlException]) =
        Abort.get(SqlConfig.Url.parse(rawUrl)).flatMap { url =>
            if url.address.driver != "mysql" then
                Abort.fail(SqlException.Connection(
                    s"SqlClient.useMy requires a mysql:// URL; got '${url.address.driver}://'. Use SqlClient.use for postgres:// URLs.",
                    summon[Frame]
                ))
            else
                SqlClient.mergeConfig(url, config).flatMap { mergedConfig =>
                    val backend: MySqlClientBackend < Sync = Sync.Unsafe.defer {
                        SqlClientBackend.initMy(mergedConfig, summon[Frame])
                    }
                    backend.flatMap { (b: MySqlClientBackend) =>
                        AtomicBoolean.init(false).flatMap { closedRef =>
                            val client = new MySqlSqlClient(b, url, mergedConfig, closedRef)
                            val warmN  = mergedConfig.minConnections.min(mergedConfig.maxConnections)
                            // client.close is Async; use Scope.run to discharge Scope inline.
                            Scope.run(
                                Scope.ensure(client.close).andThen(
                                    b.warmUp(url.address, url.password, warmN, mergedConfig).andThen(f(client))
                                )
                            )
                        }
                    }
                }
        }
    end useMy

    /** Creates a MySQL [[SqlClient]] with no cleanup registered. Delegates to `initMyUnscopedWith(rawUrl)(identity)`. */
    def initMyUnscoped(rawUrl: String)(using Frame): MySqlSqlClient < (Async & Abort[SqlException]) =
        initMyUnscopedWith(rawUrl)(identity)

    def initMyUnscoped(rawUrl: String, config: SqlConfig)(using Frame): MySqlSqlClient < (Async & Abort[SqlException]) =
        initMyUnscopedWith(rawUrl, config)(identity)

    /** Creates a MySQL [[SqlClient]] with no cleanup registered, applies `f`, and returns.
      *
      * Effect set: `Async & Abort[SqlException] & S`.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `mysql://user:pw@host:port/db[?opts]`
      * @param f
      *   function receiving the initialized client
      */
    inline def initMyUnscopedWith[B, S](rawUrl: String)(inline f: MySqlSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Abort[SqlException]) =
        initMyUnscopedWith(rawUrl, SqlConfig.default)(f)

    /** Creates a MySQL [[MySqlSqlClient]] with custom config, no cleanup registered, applies `f`, and returns.
      *
      * Effect set: `Async & Abort[SqlException] & S`.
      *
      * @tparam B
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param rawUrl
      *   database URL in the form `mysql://user:pw@host:port/db[?opts]`
      * @param config
      *   config overrides applied on top of URL-derived defaults
      * @param f
      *   function receiving the initialized client
      */
    inline def initMyUnscopedWith[B, S](rawUrl: String, config: SqlConfig)(inline f: MySqlSqlClient => B < S)(using
        inline frame: Frame
    ): B < (S & Async & Abort[SqlException]) =
        Abort.get(SqlConfig.Url.parse(rawUrl)).flatMap { url =>
            if url.address.driver != "mysql" then
                Abort.fail(SqlException.Connection(
                    s"SqlClient.initMyUnscoped requires a mysql:// URL; got '${url.address.driver}://'. Use SqlClient.initUnscoped for postgres:// URLs.",
                    summon[Frame]
                ))
            else
                SqlClient.mergeConfig(url, config).flatMap { mergedConfig =>
                    val backend: MySqlClientBackend < Sync = Sync.Unsafe.defer {
                        SqlClientBackend.initMy(mergedConfig, summon[Frame])
                    }
                    backend.flatMap { (b: MySqlClientBackend) =>
                        AtomicBoolean.init(false).flatMap { closedRef =>
                            val client = new MySqlSqlClient(b, url, mergedConfig, closedRef)
                            val warmN  = mergedConfig.minConnections.min(mergedConfig.maxConnections)
                            b.warmUp(url.address, url.password, warmN, mergedConfig).andThen(f(client))
                        }
                    }
                }
        }
    end initMyUnscopedWith

    // --- Fiber-local active client + config ---

    /** Installs `client` as the active client for the duration of `v`.
      *
      * The client's resolved config (from [[SqlClient.init]]) is also installed in the fiber-SqlClient.local, so that per-query timeouts and other
      * settings take effect without requiring an explicit [[withConfig]] call.
      */
    def let[A, S](client: SqlClient)(v: A < S)(using Frame): A < S =
        // Use the client's own config as the base; any subsequent withConfig calls will override it.
        SqlClient.local.let((Present(client), client.config))(v)
    end let

    /** Reads the active client and applies `f` to it.
      *
      * Effect row widens to `Abort[SqlException]` so callers carrying that effect row (`.run` / `.runDynamic`) compose without an extra
      * `Abort.fold`/`Abort.run` layer. The Absent branch raises a typed `SqlException.Connection`; the Present branch is fully transparent.
      *
      * IMPORTANT: Fails at runtime with [[SqlException.Connection]] if no client is active in the current fiber, wrap the computation in
      * [[SqlClient.let]] first.
      *
      * @tparam A
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param f
      *   function receiving the active client
      */
    def use[A, S](f: SqlClient => A < S)(using Frame): A < (S & Abort[SqlException]) =
        SqlClient.local.use { (maybeClient, _) =>
            maybeClient match
                case Absent =>
                    Abort.fail(SqlException.Connection(
                        "No SqlClient active, wrap the computation in SqlClient.let(client) { ... }",
                        summon[Frame]
                    ))
                case Present(client) =>
                    f(client)
        }

    /** Transforms the active config within the scope of `v`. */
    def withConfig[A, S](f: SqlConfig => SqlConfig)(v: A < S)(using Frame): A < S =
        SqlClient.local.use { (client, config) => SqlClient.local.let((client, f(config)))(v) }

    // --- Companion-level API mirrors ---

    /** Executes a raw SQL string (no parameters) against the active client. */
    def executeRaw(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        use { c => c.executeRaw(sql) }

    /** Runs `body` inside a transaction using the active client (server default isolation). */
    def transaction[A, S](body: A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        use { c => c.transaction(body) }

    /** Runs `body` inside a transaction with explicit isolation/read-only using the active client. */
    def transaction[A, S](
        isolation: Maybe[SqlIsolationLevel],
        readOnly: Boolean
    )(body: A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        use { c => c.transaction(isolation, readOnly)(body) }

    /** Runs `body` inside a transaction using the active client, preserving the body's typed `Abort[E]` failures across the rollback
      * boundary. See instance-side [[SqlClient.transactionTyped]] for full semantics.
      */
    def transactionTyped[E, A, S](
        body: A < (S & Abort[E])
    )(using ConcreteTag[E], Frame): A < (S & Async & Abort[SqlException | E]) =
        use { c => c.transactionTyped[E, A, S](body) }

    /** Typed-error transaction with explicit isolation/read-only using the active client. */
    def transactionTyped[E, A, S](
        isolation: Maybe[SqlIsolationLevel],
        readOnly: Boolean
    )(body: A < (S & Abort[E]))(using ConcreteTag[E], Frame): A < (S & Async & Abort[SqlException | E]) =
        use { c => c.transactionTyped[E, A, S](isolation, readOnly)(body) }

    /** Returns the server address of the active client. */
    def address(using Frame): SqlConfig.Address < (Async & Abort[SqlException]) =
        use { c => c.address }

    /** Returns the metrics instance of the active client. */
    def metrics(using Frame): kyo.SqlClient.Metrics < (Async & Abort[SqlException]) =
        use { c => c.metrics }

    /** Sends a cancel request using the active client. */
    def cancel(handle: SqlCancelHandle)(using Frame): Unit < (Async & Abort[SqlException]) =
        use { c => c.cancel(handle) }

    /** Subscribes to NOTIFY messages on the named channel using the active client.
      *
      * The [[Local]] access is embedded inside the [[Stream]] body so the stream itself is a pure value.
      */
    def notifications(channel: String)(using Frame): Stream[SqlNotification, Async & Abort[SqlException] & Scope] =
        Stream[SqlNotification, Async & Abort[SqlException] & Scope](
            SqlClient.local.use { (maybeClient, _) =>
                maybeClient match
                    case Absent =>
                        Abort.fail(SqlException.Connection(
                            "No SqlClient active, wrap the computation in SqlClient.let(client) { ... }",
                            summon[Frame]
                        ))
                    case Present(c) =>
                        c.notifications(channel).emit
            }
        )

    /** Runs `f` with the active client when it is Postgres-backed.
      *
      * Fails at runtime with `SqlException.Connection` if the active client is backed by MySQL. Use this to reach Postgres-only methods
      * (`copyIn`, `copyOut`, `pipeline`, `cancellableQuery`) with a typed driver-mismatch failure surfaced early.
      *
      * @tparam A
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param f
      *   function receiving the Postgres-backed client
      */
    def usePostgres[A, S](f: SqlClient => A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        SqlClient.local.use { (maybeClient, _) =>
            maybeClient match
                case Absent =>
                    Abort.fail(SqlException.Connection(
                        "No SqlClient active, wrap the computation in SqlClient.let(client) { ... }",
                        summon[Frame]
                    ))
                case Present(client) if client.url.address.driver == "postgres" =>
                    f(client)
                case Present(client) =>
                    Abort.fail(SqlException.Connection(
                        s"usePostgres requires a Postgres client; active client is '${client.url.address.driver}'",
                        summon[Frame]
                    ))
        }

    /** Runs `f` with the active client when it is MySQL-backed.
      *
      * Fails at runtime with `SqlException.Connection` if the active client is backed by Postgres.
      *
      * @tparam A
      *   the result type of `f`
      * @tparam S
      *   the effect row of `f`
      * @param f
      *   function receiving the MySQL-backed client
      */
    def useMysql[A, S](f: SqlClient => A < S)(using Frame): A < (S & Async & Abort[SqlException]) =
        SqlClient.local.use { (maybeClient, _) =>
            maybeClient match
                case Absent =>
                    Abort.fail(SqlException.Connection(
                        "No SqlClient active, wrap the computation in SqlClient.let(client) { ... }",
                        summon[Frame]
                    ))
                case Present(client) if client.url.address.driver == "mysql" =>
                    f(client)
                case Present(client) =>
                    Abort.fail(SqlException.Connection(
                        s"useMysql requires a MySQL client; active client is '${client.url.address.driver}'",
                        summon[Frame]
                    ))
        }

    /** Validates that each type name in `names` does not contain characters that would break SQL literal interpolation.
      *
      * Single-quote (`'`) and backslash (`\`) are rejected because the type names are embedded directly into a simple-query SQL string
      * (`SELECT typname, oid FROM pg_type WHERE typname IN ('a', 'b')`). Any name containing these characters would corrupt the query or
      * allow injection. Type names are expected to be simple identifiers (e.g. `hstore`, `geometry`, `vector`).
      */
    private def sanitizeTypeNames(names: Set[String])(using Frame): Unit < Abort[SqlException.Connection] =
        val invalid = names.filter(n => n.contains('\'') || n.contains('\\'))
        if invalid.nonEmpty then
            Abort.fail(SqlException.Connection(
                s"invalid type name(s) ${invalid.mkString(", ")}: must not contain single-quote or backslash",
                summon[Frame]
            ))
        else ()
        end if
    end sanitizeTypeNames

    /** Merges [[SqlConfig]] overrides with defaults derived from the URL.
      *
      * Calls [[SqlConfig.Url.toConfig]] which delegates to [[kyo.internal.tls.TlsContext.build]]; fails with [[SqlException.Connection]] for
      * invalid sslmode + sslrootcert combinations (e.g., `verify-ca` without `sslrootcert`).
      */
    private def mergeConfig(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlConfig < Abort[SqlException.Connection] =
        url.toConfig.map { urlConfig =>
            urlConfig.copy(
                maxConnections = config.maxConnections,
                minConnections = config.minConnections,
                acquireTimeout = config.acquireTimeout,
                queryTimeout = config.queryTimeout,
                idleTimeout = config.idleTimeout,
                retrySchedule = config.retrySchedule,
                tls = config.tls.orElse(urlConfig.tls),
                caCertPath = config.caCertPath.orElse(urlConfig.caCertPath),
                preparedStmtCacheSize = config.preparedStmtCacheSize,
                preparedStmtTtl = config.preparedStmtTtl,
                pipelineMode = config.pipelineMode,
                // tlsMode comes from URL; programmatic config override is not supported.
                tlsMode = urlConfig.tlsMode,
                typeNames = config.typeNames,
                // Phase 23 lifecycle fields, pass through from user config.
                maxLifetime = config.maxLifetime,
                connectionTestQuery = config.connectionTestQuery,
                connectionInitSql = config.connectionInitSql,
                keepaliveTime = config.keepaliveTime,
                connectTimeout = config.connectTimeout,
                socketTimeout = config.socketTimeout,
                leakDetectionThreshold = config.leakDetectionThreshold,
                connectionInitTimeout = config.connectionInitTimeout
            )
        }
    end mergeConfig

    /** Opens a single connection to `address`, runs the startup handshake, and returns an active [[PostgresConnection]].
      *
      * The connection is scope-managed: it will be terminated when the enclosing [[Scope]] exits.
      */
    def connect(address: SqlConfig.Address)(using Frame): PostgresConnection < (Async & Scope & Abort[SqlException]) =
        connect(address, Absent, Absent)

    def connect(address: SqlConfig.Address, password: Maybe[String])(using
        Frame
    ): PostgresConnection < (Async & Scope & Abort[SqlException]) =
        connect(address, password, Absent)

    def connect(
        address: SqlConfig.Address,
        password: Maybe[String],
        tls: Maybe[kyo.net.NetTlsConfig]
    )(using Frame): PostgresConnection < (Async & Scope & Abort[SqlException]) =
        PostgresConnection.connect(
            address.host,
            address.port,
            address.user,
            address.db,
            password,
            tls,
            64,
            Duration.Infinity,
            EncodingRegistry.builtin
        ).flatMap { conn =>
            Scope.ensure(Abort.run(conn.terminate).unit).andThen(conn)
        }

    // --- Extension methods ---

    // --- Pipeline ---

    // --- Health probes and session reset ---

    // --- Advisory locks ---

    // --- Postgres-specific extension methods ---

    // --- MySQL-specific extension methods ---

    /** Translates `?` placeholders into Postgres `$N` numbered placeholders.
      *
      * Skips `?` characters that appear inside:
      *   - single-quoted string literals (`'...'`), with `''` escape recognised,
      *   - double-quoted identifiers (`"..."`), with `""` escape recognised,
      *   - line comments (`-- … <newline>`),
      *   - block comments (`/* … */`),
      *   - dollar-quoted strings (`$$…$$` or `$tag$…$tag$`), the entire body is copied verbatim.
      *
      * If the input contains no `?` characters, the input is returned unchanged.
      */
    // Performance carve-out: 5 vars + 4 while loops, CONTRIBUTING §Scala Conventions permits
    // mutable state in performance-critical private[kyo] internals encapsulated behind a pure interface.
    // SqlClient.translatePlaceholders is pure String => String on the query hot path.
    private[kyo] def translatePlaceholders(sql: String): String =
        if sql.indexOf('?') < 0 then sql
        else
            val sb  = new StringBuilder(sql.length + 8)
            var i   = 0
            var idx = 0
            val n   = sql.length

            // Tries to match a dollar-tag starting at position `start` (which must be a '$').
            // A dollar-tag is `$<tag>$` where <tag> may be empty or contain only letters/digits/underscores.
            // Returns Present(tag-with-dollar-delimiters, endOfOpenTag) on success, Absent otherwise.
            def matchDollarTag(start: Int): Maybe[(String, Int)] =
                // start points at the opening '$'
                var j = start + 1
                while j < n && (sql.charAt(j) == '_' || Character.isLetterOrDigit(sql.charAt(j))) do j += 1
                if j < n && sql.charAt(j) == '$' then
                    // tag found: sql[start..j] inclusive is the delimiter e.g. "$body$"
                    Maybe((sql.substring(start, j + 1), j + 1))
                else Maybe.Absent
                end if
            end matchDollarTag

            while i < n do
                val c = sql.charAt(i)
                c match
                    case '\'' =>
                        // Single-quoted string literal, copy verbatim, handling doubled '' as escape.
                        val _ = sb.append(c)
                        i += 1
                        var done = false
                        while !done && i < n do
                            val ch = sql.charAt(i)
                            val _  = sb.append(ch)
                            if ch == '\'' then
                                if i + 1 < n && sql.charAt(i + 1) == '\'' then
                                    val _ = sb.append('\'')
                                    i += 2
                                else
                                    i += 1
                                    done = true
                            else i += 1
                            end if
                        end while
                    case '"' =>
                        // Double-quoted identifier, copy verbatim, handling doubled "" as escape.
                        val _ = sb.append(c)
                        i += 1
                        var done = false
                        while !done && i < n do
                            val ch = sql.charAt(i)
                            val _  = sb.append(ch)
                            if ch == '"' then
                                if i + 1 < n && sql.charAt(i + 1) == '"' then
                                    val _ = sb.append('"')
                                    i += 2
                                else
                                    i += 1
                                    done = true
                            else i += 1
                            end if
                        end while
                    case '-' if i + 1 < n && sql.charAt(i + 1) == '-' =>
                        // Line comment, copy through end-of-line.
                        while i < n && sql.charAt(i) != '\n' do
                            val _ = sb.append(sql.charAt(i))
                            i += 1
                        if i < n then
                            val _ = sb.append('\n')
                            i += 1
                    case '/' if i + 1 < n && sql.charAt(i + 1) == '*' =>
                        // Block comment, copy through closing */.
                        val _ = sb.append("/*")
                        i += 2
                        var done = false
                        while !done && i < n do
                            val ch = sql.charAt(i)
                            val _  = sb.append(ch)
                            if ch == '*' && i + 1 < n && sql.charAt(i + 1) == '/' then
                                val _ = sb.append('/')
                                i += 2
                                done = true
                            else i += 1
                            end if
                        end while
                    case '$' =>
                        // Dollar-quoted string: `$tag$body$tag$`, copy entire span verbatim.
                        matchDollarTag(i) match
                            case Present((tag, bodyStart)) =>
                                // Emit the opening tag.
                                val _ = sb.append(tag)
                                // Scan forward for the matching closing tag.
                                var j    = bodyStart
                                var done = false
                                while !done && j < n do
                                    if sql.charAt(j) == '$' && sql.startsWith(tag, j) then
                                        // Emit body up to closing tag, then the closing tag itself.
                                        val _ = sb.append(sql.substring(bodyStart, j))
                                        val _ = sb.append(tag)
                                        j += tag.length
                                        done = true
                                    else j += 1
                                    end if
                                end while
                                if !done then
                                    // Unclosed dollar-quoted string, emit remainder verbatim.
                                    val _ = sb.append(sql.substring(bodyStart, n))
                                    j = n
                                end if
                                i = j
                            case Absent =>
                                // Bare '$', not a valid dollar-tag opener; emit as-is.
                                val _ = sb.append(c)
                                i += 1
                    case '?' =>
                        idx += 1
                        val _ = sb.append('$').append(idx)
                        i += 1
                    case _ =>
                        val _ = sb.append(c)
                        i += 1
                end match
            end while
            sb.toString
        end if
    end translatePlaceholders

end SqlClient
