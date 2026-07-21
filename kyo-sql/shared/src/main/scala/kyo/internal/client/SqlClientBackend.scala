package kyo.internal.client

import java.util.concurrent.ConcurrentHashMap
import kyo.*
import kyo.SqlMetrics
import kyo.SqlSchema.BoundValue
import kyo.internal.client.ConnectionPool
import kyo.internal.mysql.BoundMysqlParam
import kyo.internal.mysql.MysqlConnection
import kyo.internal.mysql.MysqlRow
import kyo.internal.postgres.BoundParam
import kyo.internal.postgres.FieldDescription
import kyo.internal.postgres.PostgresConnection
import kyo.internal.postgres.exchange.CancelExchange
import kyo.internal.postgres.types.EncodingRegistry
import kyo.internal.postgres.types.Format
import kyo.internal.tls.TlsMode
import kyo.internal.tls.TlsNegotiator
import kyo.net.NetAddress

/** Sealed orchestration layer for kyo-sql: retry → timeout → pool → connect+execute.
  *
  * Two concrete implementations:
  *   - [[SqlClientBackend.Pg]], backed by a [[ConnectionPool[NetAddress, PostgresConnection]]], uses the Postgres extended protocol.
  *   - [[SqlClientBackend.My]], backed by a [[ConnectionPool[NetAddress, MysqlConnection]]], uses the MySQL binary prepared-statement protocol.
  *
  * All public methods accept a `SqlClientConfig` (sourced from the fiber-local in `SqlClient`) and apply the four-layer chain (retry → pool
  * → connect+execute → timeout) to execute a single query or statement.
  *
  * Both variants expose an identical surface:
  *   - `query` / `execute` / `executeRaw` / `streamQuery`, SQL operations
  *   - `cancel` / `cancelMysql`, query cancellation (backend-specific)
  *   - `withConnection`, PG-only: acquire a connection and run `f` (used by transaction)
  *   - `withMysqlConnection`, MySQL-only: acquire a MySQL connection and run `f` (used by transaction)
  *   - `closeAll`, drain and close the pool
  *
  * ==Unsafe usage==
  *
  * `ConnectionPool`'s `poll`, `release`, `discard`, `tryReserve`, `unreserve`, and `close` all require `AllowUnsafe`, they operate on a
  * lock-free Vyukov ring buffer that does CAS directly on atomic longs. Each call site carries a `// Unsafe:` justification comment as
  * required by STEERING.md. The `isAlive` and `discard` callbacks passed to `ConnectionPool.init` are invoked by the pool while inside an
  * `AllowUnsafe` context, outside of any Kyo fiber suspension. Running `conn.isOpen` / `conn.close` there requires evaluating a `< Sync`
  * effect synchronously via `Sync.Unsafe.evalOrThrow`. This is STEERING case 2 (cleanup / health-check callback invoked outside the Kyo
  * fiber boundary).
  */
sealed trait SqlClientBackend:

    val clientFrame: Frame

    /** Metrics instance, no-op when `metricsEnabled = false` in the config. */
    val metrics: SqlMetrics

    // --- Abstract operations exposed to SqlClient ---

    /** Returns all rows for a parameterised query via the extended/binary protocol. */
    def query(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundParam[?]],
        config: SqlClientConfig
    )(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException])

    /** Executes a parameterised DML statement and returns the affected-row count. */
    def execute(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundParam[?]],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException])

    /** Executes a raw simple-query (no parameters, may be multi-statement) and returns the affected-row count. */
    def executeRaw(
        address: SqlAddress,
        password: String,
        sql: String,
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException])

    /** Streams rows from a parameterised query. */
    def streamQuery(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundParam[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope]

    /** Executes a parameterised DML statement using the unified [[BoundValue]] surface.
      *
      * The backend converts each `BoundValue` to its native bind parameter type ([[BoundParam]] for Postgres, [[BoundMysqlParam]] for
      * MySQL) via the carried [[SqlSchema]] and dispatches to the appropriate wire-level path.
      */
    def executeBound(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException])

    /** Executes a parameterised INSERT statement and returns an [[InsertResult]].
      *
      *   - On Postgres, when `sql` includes a `RETURNING <pk>` clause (auto-emitted by the renderer for tables with an auto-key column),
      *     the path reads the response's DataRow(s) and surfaces the LAST generated key as `generatedKey`. When no `RETURNING` is present,
      *     `generatedKey` is `Maybe.Absent`.
      *   - On MySQL, the path reads `last_insert_id` from the OK packet. When `last_insert_id == 0` (no auto-increment value generated),
      *     `generatedKey` is `Maybe.Absent`.
      */
    def executeInsert(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        config: SqlClientConfig
    )(using Frame): InsertResult < (Async & Abort[SqlException])

    /** Returns all rows for a parameterised query using the unified [[BoundValue]] surface. */
    def queryBound(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        config: SqlClientConfig
    )(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException])

    /** Streams rows from a parameterised query using the unified [[BoundValue]] surface. */
    def streamBound(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope]

    /** Executes a parameterised MySQL query and returns all rows as backend-neutral [[Row]] values.
      *
      * Only implemented by [[SqlClientBackend.My]]; [[SqlClientBackend.Pg]] raises [[SqlException.Connection]]. The MySQL backend converts
      * [[MysqlRow]] to [[Row]] with synthetic field descriptors (same as the simple-query path).
      */
    def queryMysql(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        config: SqlClientConfig
    )(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException])

    /** Executes a parameterised MySQL DML statement and returns the affected-row count.
      *
      * Only implemented by [[SqlClientBackend.My]]; [[SqlClientBackend.Pg]] raises [[SqlException.Connection]].
      */
    def executeMysql(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException])

    /** Streams MySQL rows from a parameterised query.
      *
      * Only implemented by [[SqlClientBackend.My]]; [[SqlClientBackend.Pg]] raises [[SqlException.Connection]].
      */
    def streamQueryMysql(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[MysqlRow, Async & Abort[SqlException] & Scope]

    /** Streams MySQL rows from a parameterised query, converting each row to [[SqlRow]] so that [[SqlSchema]] instances can be applied.
      *
      * Only implemented by [[SqlClientBackend.My]]; [[SqlClientBackend.Pg]] raises [[SqlException.Connection]].
      */
    def streamQueryMysqlRows(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope]

    /** Acquires a Postgres connection, calls `f`, then releases.
      *
      * Only implemented by [[SqlClientBackend.Pg]]; [[SqlClientBackend.My]] raises [[SqlException.Connection]].
      */
    def withConnection[A, S](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: PostgresConnection => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException])

    /** Acquires a MySQL connection, calls `f`, then releases.
      *
      * Only implemented by [[SqlClientBackend.My]]; [[SqlClientBackend.Pg]] raises [[SqlException.Connection]].
      */
    def withMysqlConnection[A, S](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: MysqlConnection => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException])

    /** Runs `body` while holding a database advisory lock for `key` on a pinned session.
      *
      * Both the acquire SQL and the release SQL execute on the same pinned connection (PG and MySQL advisory locks are session-scoped, so
      * a pool-mediated release would silently no-op on the wrong session). Backend-specific keyword: PG uses `pg_advisory_lock` /
      * `pg_advisory_unlock`; MySQL uses `GET_LOCK(name, timeoutSeconds)` / `RELEASE_LOCK(name)`. `timeout` is only used on MySQL; PG's
      * `pg_advisory_lock` always blocks until acquired.
      *
      * Release fires on every exit edge (success, `Abort`, panic) before the pinned connection is returned to the pool.
      */
    def withAdvisoryLock[A, S](
        address: SqlAddress,
        password: String,
        key: Long,
        timeout: Maybe[Duration],
        config: SqlClientConfig
    )(body: A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException])

    /** Cancels a Postgres query via `CancelRequest`. */
    def cancel(handle: SqlCancelHandle.Pg)(using Frame): Unit < (Async & Abort[SqlException])

    /** Cancels a MySQL query via `KILL QUERY <connectionId>` on a fresh sidecar connection. */
    def cancelMysql(handle: SqlCancelHandle.My, password: String, config: SqlClientConfig)(using
        Frame
    ): Unit < (Async & Abort[SqlException])

    /** Acquires a Postgres connection and calls `f` with the connection and its cancel info `(conn, pid, secretKey)`.
      *
      * Only implemented by [[SqlClientBackend.Pg]]; [[SqlClientBackend.My]] raises [[SqlException.Connection]].
      */
    def withCancelInfo[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: (PostgresConnection, Int, Int) => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException])

    /** Acquires a MySQL connection and calls `f` with the connection and its `connectionId`.
      *
      * Only implemented by [[SqlClientBackend.My]]; [[SqlClientBackend.Pg]] raises [[SqlException.Connection]].
      */
    def withCancelInfoMysql[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: (MysqlConnection, Long) => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException])

    /** Streams Postgres LISTEN/NOTIFY notifications.
      *
      * Only implemented by [[SqlClientBackend.Pg]]; [[SqlClientBackend.My]] raises [[SqlException.Connection]].
      */
    def notificationStream(
        address: SqlAddress,
        password: String,
        channel: String,
        config: SqlClientConfig
    )(using Frame): Stream[SqlNotification, Async & Abort[SqlException] & Scope]

    /** MySQL savepoint helpers, delegated to [[MysqlConnection]]. */
    def mysqlSavepointTransaction(conn: MysqlConnection, name: String)(using Frame): Unit < (Async & Abort[SqlException])
    def mysqlReleaseSavepoint(conn: MysqlConnection, name: String)(using Frame): Unit < (Async & Abort[SqlException])
    def mysqlRollbackToSavepoint(conn: MysqlConnection, name: String)(using Frame): Unit < (Async & Abort[SqlException])

    /** Executes a `LOAD DATA LOCAL INFILE` statement via the MySQL-only LOCAL INFILE protocol.
      *
      * Only implemented by [[MySqlClientBackend]]; [[PgSqlClientBackend]] raises [[SqlException.Connection]].
      */
    def loadLocalInfileMysql[S](
        address: SqlAddress,
        password: String,
        sql: String,
        data: Stream[Byte, S],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException] & S)

    /** Warms up the pool by opening `n` connections concurrently and releasing them to the idle ring.
      *
      * If `n == 0`, returns immediately without opening any connection. If `n > 0`, opens exactly `n` connections concurrently via
      * `Async.fill`. Each opened connection is immediately released to the pool (available for the first user query). Any connection
      * failure aborts the warm-up and propagates the typed [[SqlException]] as-is, callers that pattern-match on specific subclasses (e.g.
      * [[SqlException.Server]] for auth/permission failures) see the original exception, not a blanket [[SqlException.Connection]] wrap.
      * Non-SqlException transport errors are wrapped as [[SqlException.Connection]].
      *
      * Called from [[kyo.sql.SqlClient.init]] / [[kyo.sql.SqlClient.initMy]] with `n = min(config.minConnections, config.maxConnections)`.
      */
    def warmUp(
        address: SqlAddress,
        password: String,
        n: Int,
        config: SqlClientConfig
    )(using Frame): Unit < (Async & Abort[SqlException])

    /** Closes all idle connections and shuts down the pool. Waits up to `gracePeriod` for in-flight queries to complete before
      * force-closing remaining connections. Pass `Duration.Zero` for an immediate force-close.
      */
    def closeAll(gracePeriod: Duration)(using Frame): Unit < Async

    // --- Shared slot-channel helpers (hoisted from both concrete backends) ---

    /** Per-address slot channels: limits concurrency to `maxConnections` per address. */
    protected val slotChans: ConcurrentHashMap[SqlAddress, Channel[Unit]]

    // Unsafe: requires AllowUnsafe to call Sync.Unsafe.evalOrThrow.
    // STEERING case 2: lazy resource initialisation run synchronously from inside Sync.Unsafe.defer.
    protected def getOrCreateSlotChanUnsafe(address: SqlAddress, maxConns: Int)(using AllowUnsafe, Frame): Channel[Unit] =
        slotChans.computeIfAbsent(
            address,
            _ =>
                val capacity = maxConns.max(2)
                val ch       = Sync.Unsafe.evalOrThrow(Channel.initUnscoped[Unit](capacity))
                var i        = 0
                while i < capacity do
                    discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](ch.offer(()))))
                    i += 1
                ch
        )
    end getOrCreateSlotChanUnsafe

    protected def withSlot[A](slotCh: Channel[Unit], config: SqlClientConfig)(
        body: A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        val takeSlot: Unit < (Async & Abort[SqlException]) =
            if config.acquireTimeout == Duration.Infinity then
                Abort.run[Closed](slotCh.take).flatMap {
                    case Result.Success(()) => ()
                    case Result.Failure(_)  => Abort.fail(SqlException.Connection("Connection pool is closed", summon[Frame]))
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] SqlClientBackend.withSlot: slotCh.take panic: ${t.getMessage}")
                        Abort.error(Result.Panic(t))
                }
            else
                Async.timeoutWithError(
                    config.acquireTimeout,
                    Result.Failure(SqlException.Connection(
                        s"Timed out waiting ${config.acquireTimeout} for a connection (pool exhausted)",
                        summon[Frame]
                    ))
                )(
                    Abort.run[Closed](slotCh.take).flatMap {
                        case Result.Success(()) => ()
                        case Result.Failure(_)  => Abort.fail(SqlException.Connection("Connection pool is closed", summon[Frame]))
                        case Result.Panic(t) =>
                            java.lang.System.err.println(
                                s"[kyo-sql] SqlClientBackend.withSlot: slotCh.take panic (timeout path): ${t.getMessage}"
                            )
                            Abort.error(Result.Panic(t))
                    }
                )
            end if
        end takeSlot
        // Time the wait for the slot (pool acquire wait = time blocked waiting for a free connection).
        Clock.stopwatch.flatMap { sw =>
            Abort.run[SqlException](takeSlot).flatMap {
                case Result.Failure(e @ SqlException.Connection(msg, _)) if msg.startsWith("Timed out") =>
                    Log.warn(
                        s"kyo.sql: pool acquire timeout after ${config.acquireTimeout} poolSize=${config.maxConnections}"
                    ).andThen(Abort.fail(e))
                case Result.Failure(e) => Abort.fail(e)
                case Result.Panic(t)   => Abort.error(Result.Panic(t))
                case Result.Success(()) =>
                    sw.elapsed.flatMap { dur =>
                        metrics.recordPoolAcquireWait(dur.toMillis)
                    }.andThen {
                        // Sync.ensure guarantees the slot is returned on every exit edge
                        // (success, Abort, Panic, fiber interrupt). The inner Abort.run[SqlException]
                        // preserves the Log.error path for SqlException.Server before re-raising.
                        Sync.ensure(Sync.Unsafe.defer {
                            discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](slotCh.offer(()))))
                        }) {
                            Abort.run[SqlException](body).flatMap {
                                case Result.Success(a) => a
                                case Result.Failure(e: SqlException.Server) =>
                                    Log.error(s"kyo.sql: server error sqlState=${e.sqlState} msg=${e.message}")
                                        .andThen(Abort.fail[SqlException](e))
                                case Result.Failure(e) => Abort.fail[SqlException](e)
                                case Result.Panic(t)   => Abort.error(Result.Panic(t))
                            }
                        }
                    }
            }
        }
    end withSlot

    protected def withSlotS[A, S](slotCh: Channel[Unit], config: SqlClientConfig)(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        val takeSlot: Unit < (S & Async & Abort[SqlException]) =
            if config.acquireTimeout == Duration.Infinity then
                Abort.run[Closed](slotCh.take).flatMap {
                    case Result.Success(()) => ()
                    case Result.Failure(_)  => Abort.fail(SqlException.Connection("Connection pool is closed", summon[Frame]))
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] SqlClientBackend.withSlotS: slotCh.take panic: ${t.getMessage}")
                        Abort.error(Result.Panic(t))
                }
            else
                Async.timeoutWithError(
                    config.acquireTimeout,
                    Result.Failure(SqlException.Connection(
                        s"Timed out waiting ${config.acquireTimeout} for a connection (pool exhausted)",
                        summon[Frame]
                    ))
                )(
                    Abort.run[Closed](slotCh.take).flatMap {
                        case Result.Success(()) => ()
                        case Result.Failure(_)  => Abort.fail(SqlException.Connection("Connection pool is closed", summon[Frame]))
                        case Result.Panic(t) =>
                            java.lang.System.err.println(
                                s"[kyo-sql] SqlClientBackend.withSlotS: slotCh.take panic (timeout path): ${t.getMessage}"
                            )
                            Abort.error(Result.Panic(t))
                    }
                )
            end if
        end takeSlot
        Abort.run[SqlException](takeSlot).flatMap {
            case Result.Failure(e @ SqlException.Connection(msg, _)) if msg.startsWith("Timed out") =>
                Log.warn(
                    s"kyo.sql: pool acquire timeout after ${config.acquireTimeout} poolSize=${config.maxConnections}"
                ).andThen(Abort.fail(e))
            case Result.Failure(e)  => Abort.fail(e)
            case Result.Panic(t)    => Abort.error(Result.Panic(t))
            case Result.Success(()) =>
                // Per-attempt slot release, wrapped in Sync.ensure so the slot is
                // returned on every exit edge (success, Abort, Panic, fiber interrupt).
                // The inner Abort.run[SqlException] preserves the Log.error path for
                // SqlException.Server before re-raising.
                Sync.ensure(Sync.Unsafe.defer {
                    discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](slotCh.offer(()))))
                }) {
                    Abort.run[SqlException](body).flatMap {
                        case Result.Success(a) => a
                        case Result.Failure(e: SqlException.Server) =>
                            Log.error(s"kyo.sql: server error sqlState=${e.sqlState} msg=${e.message}")
                                .andThen(Abort.fail[SqlException](e))
                        case Result.Failure(e) => Abort.fail[SqlException](e)
                        case Result.Panic(t)   => Abort.error(Result.Panic(t))
                    }
                }
        }
    end withSlotS

end SqlClientBackend

// --- Postgres backend ---

/** Postgres-backed [[SqlClientBackend]].
  *
  * Wraps a [[ConnectionPool[NetAddress, PostgresConnection]]] + per-address slot channels. Behaviour is identical to the original v1 backend.
  */
final class PgSqlClientBackend private[client] (
    private val pool: ConnectionPool[NetAddress, PostgresConnection],
    protected val slotChans: ConcurrentHashMap[SqlAddress, Channel[Unit]],
    val clientFrame: Frame,
    val metrics: SqlMetrics
) extends SqlClientBackend:

    // --- query / execute / executeRaw / streamQuery ---

    def query(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundParam[?]],
        config: SqlClientConfig
    )(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        metrics.timedQuery(retryWith(address, password, config)(conn => conn.extendedQuery(sql, params)))

    def execute(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundParam[?]],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException]) =
        metrics.timedQuery(retryWith(address, password, config)(conn => conn.extendedExecute(sql, params)))

    def executeRaw(
        address: SqlAddress,
        password: String,
        sql: String,
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException]) =
        metrics.timedQuery(retryWith(address, password, config)(conn => conn.simpleExecute(sql)))

    def streamQuery(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundParam[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        // Retry is intentionally absent here: mid-stream retries would require resetting the
        // cursor position, which the current Stream API does not support.
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            acquireStreamConn(address, password, config).flatMap { conn =>
                conn.streamQuery(sql, params, batchSize).emit
            }
        )

    // --- BoundValue surface (unified) ---

    def executeBound(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException]) =
        execute(address, password, sql, params.flatMap(bv => SqlClientBackend.boundToPostgres(bv)), config)

    def executeInsert(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        config: SqlClientConfig
    )(using Frame): InsertResult < (Async & Abort[SqlException]) =
        val pgParams = params.flatMap(bv => SqlClientBackend.boundToPostgres(bv))
        metrics.timedQuery(retryWith(address, password, config)(conn => conn.extendedExecuteInsert(sql, pgParams)))
    end executeInsert

    def queryBound(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        config: SqlClientConfig
    )(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        query(address, password, sql, params.flatMap(bv => SqlClientBackend.boundToPostgres(bv)), config)

    def streamBound(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        streamQuery(address, password, sql, params.flatMap(bv => SqlClientBackend.boundToPostgres(bv)), batchSize, config)

    // --- MySQL operations, not supported on PG backend ---

    def queryMysql(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        config: SqlClientConfig
    )(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        Abort.fail(SqlException.Connection("queryMysql is not supported on the Postgres backend", summon[Frame]))

    def executeMysql(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException]) =
        Abort.fail(SqlException.Connection("executeMysql is not supported on the Postgres backend", summon[Frame]))

    def streamQueryMysql(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[MysqlRow, Async & Abort[SqlException] & Scope] =
        Stream[MysqlRow, Async & Abort[SqlException] & Scope](
            Abort.fail(SqlException.Connection("streamQueryMysql is not supported on the Postgres backend", summon[Frame]))
        )

    def streamQueryMysqlRows(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            Abort.fail(SqlException.Connection("streamQueryMysqlRows is not supported on the Postgres backend", summon[Frame]))
        )

    // --- withConnection (PG transaction support) ---

    def withConnection[A, S](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: PostgresConnection => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException]) =
        // Unsafe: getOrCreateSlotChanUnsafe and all pool ops require AllowUnsafe.
        // STEERING case 2: bridging to kyo-net ConnectionPool.
        Sync.Unsafe.defer(getOrCreateSlotChanUnsafe(address, config.maxConnections)).flatMap { slotCh =>
            val netKey = NetAddress.Tcp(address.host, address.port)
            withSlotS[A, S](slotCh, config) {
                Sync.Unsafe.defer(spinAcquire(netKey)).flatMap {
                    case Present(conn) =>
                        releaseOnExitS[A, S](netKey, conn)(f(conn))
                    case Absent =>
                        // Per-attempt in-flight slot release, see acquireAndRun for the rationale.
                        // Keeps `withConnection` safe under user-level retry wrappers.
                        Abort.run[SqlException](connectAndRunS[A, S](address, password, netKey, config)(f)).flatMap { result =>
                            Sync.Unsafe.defer(poolUnreserve(netKey)).andThen {
                                result match
                                    case Result.Success(a) => (a: A < (S & Async & Abort[SqlException]))
                                    case Result.Failure(e) => Abort.fail[SqlException](e)
                                    case Result.Panic(t)   => Abort.error(Result.Panic(t))
                            }
                        }
                }
            }
        }
    end withConnection

    def withMysqlConnection[A, S](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: MysqlConnection => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException]) =
        Abort.fail(SqlException.Connection("withMysqlConnection is not supported on the Postgres backend", summon[Frame]))

    def withAdvisoryLock[A, S](
        address: SqlAddress,
        password: String,
        key: Long,
        timeout: Maybe[Duration],
        config: SqlClientConfig
    )(body: A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException]) =
        // PG session-level advisory locks always block until acquired; `timeout` is not used here.
        val _ = timeout
        withConnection(address, password, config) { conn =>
            conn.simpleExecute(s"SELECT pg_advisory_lock($key)").andThen {
                Abort.run[SqlException](body).flatMap { result =>
                    Abort.run[SqlException](conn.simpleExecute(s"SELECT pg_advisory_unlock($key)")).andThen {
                        result match
                            case Result.Success(a) => a
                            case Result.Failure(e) => Abort.fail[SqlException](e)
                            case Result.Panic(t)   => Abort.error(Result.Panic(t))
                    }
                }
            }
        }
    end withAdvisoryLock

    // --- Cancel ---

    def cancel(handle: SqlCancelHandle.Pg)(using Frame): Unit < (Async & Abort[SqlException]) =
        CancelExchange.cancel(handle.address, handle.tls, handle.processId, handle.secretKey)

    def cancelMysql(handle: SqlCancelHandle.My, password: String, config: SqlClientConfig)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        Abort.fail(SqlException.Connection(
            "MySQL cancel via SqlClient requires a MySQL pool backend. " +
                "Use SqlClient.initMysql and then client.cancel(handle).",
            summon[Frame]
        ))

    // --- withCancelInfo ---

    def withCancelInfo[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: (PostgresConnection, Int, Int) => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        poolWith(address, password, config) { conn =>
            f(conn, conn.processId, conn.secretKey)
        }

    def withCancelInfoMysql[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: (MysqlConnection, Long) => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        Abort.fail(SqlException.Connection("withCancelInfoMysql is not supported on the Postgres backend", summon[Frame]))

    // --- Notifications ---

    def notificationStream(
        address: SqlAddress,
        password: String,
        channel: String,
        config: SqlClientConfig
    )(using Frame): Stream[SqlNotification, Async & Abort[SqlException] & Scope] =
        Stream[SqlNotification, Async & Abort[SqlException] & Scope](
            PostgresConnection.connect(
                address.host,
                address.port,
                address.user,
                address.db,
                Present(password),
                config.tls,
                config.preparedStmtCacheSize,
                config.preparedStmtTtl,
                config.encodingRegistry
            ).flatMap { conn =>
                Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                    // Postgres identifier quoting: double each `"` inside the name so `foo"; DROP TABLE users; --`
                    // cannot break out of the quoted identifier and inject a second simple-query statement.
                    val quoted = channel.replace("\"", "\"\"")
                    conn.simpleExecute(s"""LISTEN "$quoted"""").andThen {
                        Fiber.init(pumpNotifications(conn)).flatMap { pumpFiber =>
                            Scope.ensure(pumpFiber.interrupt.unit).andThen {
                                emitNotifications(conn).emit
                            }
                        }
                    }
                }
            }
        )

    // --- MySQL savepoint helpers ---

    def mysqlSavepointTransaction(conn: MysqlConnection, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        conn.savepointTransaction(name)

    def mysqlReleaseSavepoint(conn: MysqlConnection, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        conn.releaseSavepointTransaction(name)

    def mysqlRollbackToSavepoint(conn: MysqlConnection, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        conn.rollbackToSavepointTransaction(name)

    // --- loadLocalInfileMysql, not supported on PG backend ---

    def loadLocalInfileMysql[S](
        address: SqlAddress,
        password: String,
        sql: String,
        data: Stream[Byte, S],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException] & S) =
        Abort.fail(SqlException.Connection("loadLocalInfileMysql is not supported on the Postgres backend", summon[Frame]))

    // --- warmUp ---

    def warmUp(
        address: SqlAddress,
        password: String,
        n: Int,
        config: SqlClientConfig
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        if n <= 0 then ()
        else
            val netKey = NetAddress.Tcp(address.host, address.port)
            // Open n connections concurrently, materialising all results so partial successes are visible.
            // Each connect runs under its own Abort.run so a single failure does not short-circuit the rest.
            Async.fill(n, concurrency = n) {
                Abort.run[SqlException](pgConnect(address, password, config))
            }.flatMap { results =>
                val successes = results.collect { case Result.Success(conn) => conn }
                val firstFailure = results.collectFirst {
                    case Result.Failure(e: SqlException) =>
                        // Preserve the typed exception: callers' try/catch on specific subclasses (e.g., SqlException.Server
                        // for auth/permission failures) must see the original class, not a blanket Connection wrap.
                        // Non-SqlException transport errors are not reachable here, Abort.run[SqlException] only captures
                        // SqlException failures; anything else becomes a Result.Panic.
                        e
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] PgSqlClientBackend.warmUp: unexpected panic: ${t.getMessage}")
                        SqlException.Connection(s"Warm-up panic: ${t.getMessage}", summon[Frame])
                }
                firstFailure match
                    case None =>
                        // All connections succeeded, release them to the idle pool.
                        Sync.Unsafe.defer {
                            successes.foreach { conn =>
                                poolRelease(netKey, conn)
                            }
                        }
                    case Some(e) =>
                        // At least one connection failed, close the successful ones to avoid fd leaks,
                        // then propagate the error.
                        // Unsafe: conn.close is Unit < Sync; evalOrThrow is safe here.
                        Sync.Unsafe.defer {
                            successes.foreach { conn =>
                                discard(Sync.Unsafe.evalOrThrow(conn.close))
                            }
                        }.andThen(Abort.fail(e))
                end match
            }

    // --- closeAll ---

    def closeAll(gracePeriod: Duration)(using Frame): Unit < Async =
        // Step 1: Mark the pool as closed and drain idle connections.
        // pool.close() sets closed=true so tryReserve returns false, no new connections are established.
        // Slot channels are kept open during the grace period so in-flight connections can offer slots back.
        // Unsafe: pool.close() is a lock-free drain; requires AllowUnsafe.
        // STEERING case 2: cleanup on shutdown path.
        val idleConnsK: Chunk[PostgresConnection] < Sync =
            Sync.Unsafe.defer(pool.close())
        idleConnsK.flatMap { idleConns =>
            // Step 2: Wait up to gracePeriod for in-flight connections to complete.
            // In-flight count = sum over slot channels of (channel.capacity - channel.size).
            // When every slot channel is full again (size == capacity), all in-flight connections have returned their slots.
            val drainK: Unit < Async =
                if gracePeriod == Duration.Zero then ()
                else
                    // Snapshot of each slot channel. Channel.capacity gives the max slots.
                    val chanSnapshots: Chunk[Channel[Unit]] =
                        val buf = ChunkBuilder.init[Channel[Unit]]
                        slotChans.forEach { (_, ch) => buf.addOne(ch) }
                        buf.result()
                    end chanSnapshots
                    if chanSnapshots.isEmpty then ()
                    else
                        // Poll until all channels are at full capacity or the grace period expires.
                        // Each slot channel starts at capacity; in-flight connections hold taken slots.
                        // When a connection finishes and offers its slot back, size increases toward capacity.
                        def allDrained(): Boolean < Sync =
                            Sync.Unsafe.defer {
                                chanSnapshots.forall { ch =>
                                    Sync.Unsafe.evalOrThrow(Abort.run[Closed](ch.size)) match
                                        case Result.Success(sz) => sz >= ch.capacity
                                        case _                  => true // channel already closed, treat as drained
                                }
                            }
                        end allDrained
                        def pollLoop(): Unit < Async =
                            allDrained().flatMap {
                                case true  => ()
                                case false => Async.sleep(10.millis).andThen(pollLoop())
                            }
                        // Ignore timeout: after the grace period we force-close regardless.
                        Abort.run[Timeout](Async.timeout(gracePeriod)(pollLoop())).unit
                    end if
            end drainK
            drainK.andThen {
                // Step 3: Force-close slot channels and remaining connections.
                // Unsafe: channel.close and pool cleanup require AllowUnsafe.
                // STEERING case 2: cleanup on shutdown path.
                Sync.Unsafe.defer {
                    slotChans.forEach { (_, ch) =>
                        discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](ch.close)))
                    }
                    slotChans.clear()
                    // Synchronous force-close (cannot be interrupted) for the idle conns.
                    // Replaces Kyo.foreach so that a fiber interrupt mid-drain cannot leave
                    // connections un-closed (G-Leak-5).
                    idleConns.foreach { conn =>
                        discard(Sync.Unsafe.evalOrThrow(conn.close))
                    }
                }
            }
        }
    end closeAll

    // --- Pool helpers ---

    private def poolPoll(key: NetAddress)(using AllowUnsafe): Maybe[PostgresConnection] =
        pool.poll(key)

    private def poolRelease(key: NetAddress, conn: PostgresConnection)(using AllowUnsafe): Unit =
        pool.release(key, conn)

    private def poolDiscard(conn: PostgresConnection)(using AllowUnsafe): Unit =
        pool.discard(conn)

    private def poolTryReserve(key: NetAddress)(using AllowUnsafe): Boolean =
        pool.tryReserve(key)

    private def poolUnreserve(key: NetAddress)(using AllowUnsafe): Unit =
        pool.unreserve(key)

    // --- Layer 1: retry ---

    private def retryWith[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: PostgresConnection => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        config.retrySchedule match
            case Absent =>
                poolWith(address, password, config)(f)
            case Present(schedule) =>
                // Wrap each retry attempt: on each re-entry after the first we record a retry.
                // The attempt counter starts at 0 and is incremented atomically before each pool call.
                // The first attempt (prev=0) is NOT a retry; subsequent calls (prev=1, 2, …) record a retry.
                AtomicInt.init(0).flatMap { counter =>
                    Retry[SqlException.Connection](schedule) {
                        counter.getAndUpdate(_ + 1).flatMap { prev =>
                            val recordRetry: Unit < (Async & Abort[SqlException]) =
                                if prev == 0 then ()
                                else
                                    Log.warn(
                                        s"kyo.sql: retrying after connection failure attempt=$prev schedule=$schedule"
                                    ).andThen(metrics.recordRetry)
                            recordRetry.flatMap(_ => poolWith(address, password, config)(f))
                        }
                    }
                }
    end retryWith

    // --- Layer 2: pool ---

    private def poolWith[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: PostgresConnection => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        // Unsafe: getOrCreateSlotChanUnsafe runs Channel.initUnscoped synchronously on the hot path.
        // STEERING case 2: lazy initialisation of a Channel that is pure-Sync.
        Sync.Unsafe.defer(getOrCreateSlotChanUnsafe(address, config.maxConnections)).flatMap { slotCh =>
            withSlot(slotCh, config) {
                acquireAndRun(address, password, config)(f)
            }
        }
    end poolWith

    // --- Layer 3: acquire + execute ---

    private def acquireAndRun[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: PostgresConnection => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        val netKey = NetAddress.Tcp(address.host, address.port)
        val timedF: PostgresConnection => A < (Async & Abort[SqlException]) =
            if config.queryTimeout == Duration.Infinity then f
            else
                conn =>
                    Async.timeoutWithError(
                        config.queryTimeout,
                        // Connection, not Request: the wire is desynced when a query is interrupted
                        // mid-response, so the pool must discard the connection on exit (isProtocolFatal
                        // classifies Connection as fatal). Filing as Request would leave the connection
                        // reusable and corrupt the next borrower.
                        Result.Failure(SqlException.Connection(
                            s"Query exceeded timeout of ${config.queryTimeout}",
                            summon[Frame]
                        ))
                    )(f(conn))
        // Unsafe: pool operations require AllowUnsafe (lock-free CAS on ring buffer).
        // STEERING case 2: bridging to kyo-net ConnectionPool whose API requires AllowUnsafe.
        Sync.Unsafe.defer(spinAcquire(netKey)).flatMap {
            case Present(conn) =>
                metrics.recordAcquire.andThen(releaseOnExit(netKey, conn)(timedF(conn)))
            case Absent =>
                // Per-attempt in-flight slot release, same per-attempt issue as `withSlot`.
                //
                // Under `Retry`, the body is re-evaluated per attempt. A `Sync.ensure(poolUnreserve)`
                // finalizer registered against the outer computation does NOT fire per-attempt, only
                // on the outermost completion / cancellation. After N failed connect attempts,
                // `inFlight` reaches `maxConnections`, `poolTryReserve` permanently returns false,
                // and the next `spinAcquire` enters an unbounded tight CPU spin (poll Absent → tryReserve
                // false → recurse) that hangs the fiber and consumes a CPU core indefinitely.
                //
                // We catch every Result variant (Success, Failure, Panic), unreserve the in-flight slot,
                // then re-raise so callers (and Retry) see the original failure unchanged.
                Abort.run[SqlException](connectAndRun(address, password, netKey, config)(timedF)).flatMap { result =>
                    Sync.Unsafe.defer(poolUnreserve(netKey)).andThen {
                        result match
                            case Result.Success(a) => (a: A < (Async & Abort[SqlException]))
                            case Result.Failure(e) => Abort.fail[SqlException](e)
                            case Result.Panic(t)   => Abort.error(Result.Panic(t))
                    }
                }
        }
    end acquireAndRun

    @annotation.tailrec
    private def spinAcquire(netKey: NetAddress)(using AllowUnsafe): Maybe[PostgresConnection] =
        poolPoll(netKey) match
            case Present(conn) => Present(conn)
            case Absent =>
                if poolTryReserve(netKey) then Absent
                else spinAcquire(netKey) // spin: connection is in transit into the idle ring
        end match
    end spinAcquire

    // Slot release is owned by the OUTER `withSlot` wrapper at poolWith / withConnection /
    // acquireStreamConn, it fires unconditionally on flow exit. The connection-lifecycle release
    // here only handles pool return/discard. Don't re-offer the slot here or you'll double-release
    // and over-fill the slot channel beyond maxConnections.
    private def releaseOnExit[A](netKey: NetAddress, conn: PostgresConnection)(
        body: A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        val connId = conn.processId
        // Capture the current logger now (inside any Log.let scope) so the close log fires to the
        // same sink even though Sync.ensure callbacks run after Local context is unwound.
        Log.use { logger =>
            Sync.ensure { (error: Maybe[Result.Error[Any]]) =>
                // Unsafe: pool.release / pool.discard require AllowUnsafe.
                // STEERING case 2: Sync.ensure callback runs outside fiber suspension.
                Sync.Unsafe.defer {
                    if SqlClientBackend.shouldReleaseOnExit(error) then
                        poolRelease(netKey, conn)
                        discard(Sync.Unsafe.evalOrThrow(metrics.recordRelease))
                        logger.unsafe.debug(s"kyo.sql: closed connection id=$connId reason=released")
                    else
                        poolDiscard(conn)
                        discard(Sync.Unsafe.evalOrThrow(metrics.recordDiscard))
                        logger.unsafe.debug(s"kyo.sql: closed connection id=$connId reason=discarded")
                    end if
                }
            }(body)
        }
    end releaseOnExit

    private def connectAndRun[A](
        address: SqlAddress,
        password: String,
        netKey: NetAddress,
        config: SqlClientConfig
    )(f: PostgresConnection => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        pgConnect(address, password, config).flatMap { conn =>
            Log.debug(
                s"kyo.sql: opened connection id=${conn.processId} host=${address.host} port=${address.port} tls=${config.tls.isDefined}"
            )
                .andThen(metrics.recordAcquire)
                .andThen(releaseOnExit(netKey, conn)(f(conn)))
        }
    end connectAndRun

    private def releaseOnExitS[A, S](netKey: NetAddress, conn: PostgresConnection)(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        val connId = conn.processId
        // Capture the current logger now (inside any Log.let scope) so the close log fires to the
        // same sink even though Sync.ensure callbacks run after Local context is unwound.
        Log.use { logger =>
            Sync.ensure { (error: Maybe[Result.Error[Any]]) =>
                // Unsafe: pool.release / pool.discard require AllowUnsafe.
                // STEERING case 2: Sync.ensure callback runs outside fiber suspension.
                Sync.Unsafe.defer {
                    if SqlClientBackend.shouldReleaseOnExit(error) then
                        poolRelease(netKey, conn)
                        discard(Sync.Unsafe.evalOrThrow(metrics.recordRelease))
                        logger.unsafe.debug(s"kyo.sql: closed connection id=$connId reason=released")
                    else
                        poolDiscard(conn)
                        discard(Sync.Unsafe.evalOrThrow(metrics.recordDiscard))
                        logger.unsafe.debug(s"kyo.sql: closed connection id=$connId reason=discarded")
                    end if
                }
            }(body)
        }
    end releaseOnExitS

    private def connectAndRunS[A, S](
        address: SqlAddress,
        password: String,
        netKey: NetAddress,
        config: SqlClientConfig
    )(f: PostgresConnection => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException]) =
        pgConnect(address, password, config).flatMap { conn =>
            Log.debug(
                s"kyo.sql: opened connection id=${conn.processId} host=${address.host} port=${address.port} tls=${config.tls.isDefined}"
            )
                .andThen(metrics.recordAcquire)
                .andThen(releaseOnExitS[A, S](netKey, conn)(f(conn)))
        }
    end connectAndRunS

    private def acquireStreamConn(
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(using Frame): PostgresConnection < (Async & Abort[SqlException] & Scope) =
        val netKey = NetAddress.Tcp(address.host, address.port)
        // Unsafe: getOrCreateSlotChanUnsafe and pool ops need AllowUnsafe.
        // STEERING case 2: bridging to kyo-net ConnectionPool.
        Sync.Unsafe.defer(getOrCreateSlotChanUnsafe(address, config.maxConnections)).flatMap { slotCh =>
            // Take slot then register slot release in Scope.ensure BEFORE attempting connect.
            // If pgConnect fails, the surrounding Scope still closes and releases the slot,
            // preventing the leak that caused dead-pool deadlock under server restart.
            Abort.run[SqlException](takeSlotForStream(slotCh, config)).flatMap {
                case Result.Failure(e @ SqlException.Connection(msg, _)) if msg.startsWith("Timed out") =>
                    Log.warn(
                        s"kyo.sql: pool acquire timeout after ${config.acquireTimeout} poolSize=${config.maxConnections}"
                    ).andThen(Abort.fail(e))
                case Result.Failure(e) => Abort.fail(e)
                case Result.Panic(t)   => Abort.error(Result.Panic(t))
                case Result.Success(()) =>
                    Scope.ensure {
                        Sync.Unsafe.defer {
                            discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](slotCh.offer(()))))
                        }
                    }.andThen(acquireStreamSlot(address, password, netKey, config))
            }
        }
    end acquireStreamConn

    private def takeSlotForStream(slotCh: Channel[Unit], config: SqlClientConfig)(using Frame): Unit < (Async & Abort[SqlException]) =
        if config.acquireTimeout == Duration.Infinity then
            Abort.run[Closed](slotCh.take).flatMap {
                case Result.Success(()) => ()
                case Result.Failure(_)  => Abort.fail(SqlException.Connection("Connection pool is closed", summon[Frame]))
                case Result.Panic(t) =>
                    java.lang.System.err.println(s"[kyo-sql] PgSqlClientBackend.acquireStreamConn panic: ${t.getMessage}")
                    Abort.error(Result.Panic(t))
            }
        else
            Async.timeoutWithError(
                config.acquireTimeout,
                Result.Failure(SqlException.Connection(
                    s"Timed out waiting ${config.acquireTimeout} for a connection (pool exhausted)",
                    summon[Frame]
                ))
            )(
                Abort.run[Closed](slotCh.take).flatMap {
                    case Result.Success(()) => ()
                    case Result.Failure(_)  => Abort.fail(SqlException.Connection("Connection pool is closed", summon[Frame]))
                    case Result.Panic(t) =>
                        java.lang.System.err.println(
                            s"[kyo-sql] PgSqlClientBackend.acquireStreamConn panic (timeout path): ${t.getMessage}"
                        )
                        Abort.error(Result.Panic(t))
                }
            )

    private def acquireStreamSlot(
        address: SqlAddress,
        password: String,
        netKey: NetAddress,
        config: SqlClientConfig
    )(using Frame): PostgresConnection < (Async & Abort[SqlException] & Scope) =
        // Unsafe: pool ops (spinAcquire, poolRelease, poolUnreserve, poolDiscard) require AllowUnsafe; Scope.ensure callback runs outside fiber suspension.
        // STEERING case 2: bridging to kyo-net ConnectionPool and cleanup path from Scope.ensure.
        // Slot release is owned by the OUTER Scope.ensure in acquireStreamConn, the per-conn
        // Scope.ensure here only handles connection lifecycle.
        // G-Leak-3 fix: on stream abort we check whether the error is protocol-fatal.
        // Protocol-fatal errors (Connection, Decode, Server with sqlState 08*/25*) leave the wire
        // desynchronised, returning such a connection to the pool would corrupt the next borrower.
        // Non-fatal errors (Unsupported, Request, Server with other states) leave the wire intact.
        Sync.Unsafe.defer(spinAcquire(netKey)).flatMap {
            case Present(conn) =>
                Scope.ensure { error =>
                    // Unsafe: pool ops require AllowUnsafe.
                    // STEERING case 2: Scope.ensure callback runs outside fiber suspension.
                    Sync.Unsafe.defer {
                        error match
                            case Absent =>
                                poolRelease(netKey, conn)
                            case Present(Result.Failure(e: SqlException)) if SqlClientBackend.isProtocolFatal(e) =>
                                poolDiscard(conn)
                            case Present(_: Result.Panic) =>
                                // Wire state is unknown after a panic, the cleanup callback can't
                                // determine whether the connection still holds a half-consumed query
                                // response. Returning it to the pool would corrupt the next borrower.
                                poolDiscard(conn)
                            case _ =>
                                poolRelease(netKey, conn)
                    }
                }.andThen(conn)
            case Absent =>
                Sync.ensure(Sync.Unsafe.defer(poolUnreserve(netKey))) {
                    pgConnect(address, password, config).flatMap { conn =>
                        Scope.ensure { error =>
                            // Unsafe: pool ops require AllowUnsafe.
                            // STEERING case 2: Scope.ensure callback runs outside fiber suspension.
                            Sync.Unsafe.defer {
                                error match
                                    case Absent =>
                                        poolRelease(netKey, conn)
                                    case Present(Result.Failure(e: SqlException)) if SqlClientBackend.isProtocolFatal(e) =>
                                        poolDiscard(conn)
                                    case Present(_: Result.Panic) =>
                                        // Wire state is unknown after a panic, discard rather than release.
                                        poolDiscard(conn)
                                    case _ =>
                                        poolRelease(netKey, conn)
                            }
                        }.andThen(conn)
                    }
                }
        }
    end acquireStreamSlot

    // --- Mode-aware PG connect helper ---

    /** Bounds a connection-establishment attempt by `acquireTimeout`.
      *
      * Without this, a TCP connect or Postgres `StartupExchange` against a partially-recovered server (e.g. mid-restart, where the kernel
      * accepts the SYN but the server has not yet bound or is not yet responding to startup) can block indefinitely. Each retry attempt
      * would then hold a slot forever, eventually starving the pool and stalling the entire `Retry` schedule.
      *
      * `acquireTimeout` is the natural budget here: its semantic is "max time to wait for a connection to become available". The slot wait
      * inside `withSlot` is normally near-instant; the dominant cost when reconnecting is the connect+startup itself.
      */
    private def boundedConnect(
        config: SqlClientConfig,
        address: SqlAddress
    )(connect: PostgresConnection < (Async & Abort[SqlException]))(using
        Frame
    ): PostgresConnection < (Async & Abort[SqlException]) =
        if config.acquireTimeout == Duration.Infinity then connect
        else
            Async.timeoutWithError(
                config.acquireTimeout,
                Result.Failure(SqlException.Connection(
                    s"Timed out after ${config.acquireTimeout} establishing connection to ${address.host}:${address.port}",
                    summon[Frame]
                ))
            )(connect)

    /** Connects to Postgres with mode-aware TLS handling.
      *
      *   - `sslmode=disable` / `require` / `verify-ca` / `verify-full`: delegates to [[PostgresConnection.connect]] (strict TLS behaviour).
      *   - `sslmode=prefer`: uses [[PostgresConnection.connectWithNegotiator]] with a [[TlsNegotiator.postgres]] that sends SSLRequest and
      *     falls back to plaintext on 'N'.
      *   - `sslmode=allow`: first attempts plaintext (`tls=Absent`); if the server responds with "SSL required" (SQLSTATE 28000 or message
      *     containing "SSL"), retries using [[PostgresConnection.connectWithNegotiator]] with the TLS config.
      *
      * Bounded by `acquireTimeout` to ensure a stuck connect or startup against a partially-up server cannot stall the retry schedule.
      */
    private def pgConnect(
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(using Frame): PostgresConnection < (Async & Abort[SqlException]) = boundedConnect(config, address) {
        pgConnectInner(address, password, config).flatMap { conn =>
            populateTypeRegistry(config.typeNames, conn).andThen(conn)
        }
    }

    /** Issues `SELECT typname, oid::int4 FROM pg_type WHERE typname IN (...)` on `conn` (already authenticated) and sets
      * `conn.typeRegistryRef` with the resolved name→OID mapping.
      *
      * If any configured name is absent from `pg_type`, fails with [[SqlException.Connection]]. When `typeNames` is empty, returns
      * immediately without any network round-trip.
      */
    private def populateTypeRegistry(
        typeNames: Set[String],
        conn: PostgresConnection
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        if typeNames.isEmpty then ()
        else
            val nameList = typeNames.map(n => s"'$n'").mkString(", ")
            val sql      = s"SELECT typname, oid::int4 FROM pg_type WHERE typname IN ($nameList)"
            conn.simpleQuery(sql).flatMap { rows =>
                val foundMap: Map[String, Int] = rows.foldLeft(Map.empty[String, Int]) { (acc, row) =>
                    val nameBytes = row.column(0).getOrElse(Span.empty[Byte])
                    val oidBytes  = row.column(1).getOrElse(Span.empty[Byte])
                    val name      = new java.lang.String(nameBytes.toArray, "UTF-8")
                    // oid arrives as decimal text (simple-query protocol)
                    val oid = new java.lang.String(oidBytes.toArray, "UTF-8").trim.toInt
                    acc.updated(name, oid)
                }
                val missing = typeNames -- foundMap.keySet
                if missing.nonEmpty then
                    Abort.fail(SqlException.Connection(
                        s"type(s) ${missing.mkString(", ")} not found in pg_type, verify the extension is installed",
                        summon[Frame]
                    ))
                else
                    // Unsafe: AtomicRef.unsafe.set is AllowUnsafe; inside Sync.Unsafe.defer which is the approved bridging pattern.
                    // STEERING case 2: setting a per-connection AtomicRef during connection startup, before the connection is exposed.
                    Sync.Unsafe.defer(conn.typeRegistryRef.unsafe.set(foundMap))
                end if
            }

    private def pgConnectInner(
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(using Frame): PostgresConnection < (Async & Abort[SqlException]) =
        config.tlsMode match
            case TlsMode.Prefer =>
                // prefer: try TLS first via SSLRequest; fall back to plaintext on 'N'.
                config.tls match
                    case Present(tlsConfig) =>
                        val neg = TlsNegotiator.postgres(TlsMode.Prefer, tlsConfig, address.host, address.port)
                        PostgresConnection.connectWithNegotiator(
                            address.host,
                            address.port,
                            address.user,
                            address.db,
                            Present(password),
                            tls = Absent,
                            negotiator = Present(neg),
                            preparedStmtCacheSize = config.preparedStmtCacheSize,
                            preparedStmtTtl = config.preparedStmtTtl,
                            encodingRegistry = config.encodingRegistry
                        )
                    case Absent =>
                        // No TLS config available, fall back to plain connect.
                        PostgresConnection.connect(
                            address.host,
                            address.port,
                            address.user,
                            address.db,
                            Present(password),
                            Absent,
                            config.preparedStmtCacheSize,
                            config.preparedStmtTtl,
                            config.encodingRegistry
                        )
            case TlsMode.Allow =>
                // allow: attempt plaintext first; if server requires SSL, retry with TLS.
                val plaintextAttempt =
                    PostgresConnection.connect(
                        address.host,
                        address.port,
                        address.user,
                        address.db,
                        Present(password),
                        Absent,
                        config.preparedStmtCacheSize,
                        config.preparedStmtTtl,
                        config.encodingRegistry
                    )
                Abort.run[SqlException](plaintextAttempt).flatMap {
                    case Result.Success(conn) =>
                        conn
                    case Result.Failure(e) if pgIsSslRequired(e) =>
                        // Server requires SSL, reconnect with TLS.
                        config.tls match
                            case Present(tlsConfig) =>
                                val neg = TlsNegotiator.postgres(TlsMode.Allow, tlsConfig, address.host, address.port)
                                PostgresConnection.connectWithNegotiator(
                                    address.host,
                                    address.port,
                                    address.user,
                                    address.db,
                                    Present(password),
                                    tls = Present(tlsConfig),
                                    negotiator = Absent, // allow: TlsNegotiator.negotiate is a no-op; use strict TLS upgrade
                                    config.preparedStmtCacheSize,
                                    config.preparedStmtTtl,
                                    config.encodingRegistry
                                )
                            case Absent =>
                                Abort.fail(e)
                    case Result.Failure(e) =>
                        Abort.fail(e)
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] PgSqlClientBackend.pgConnect: allow plaintext panic: ${t.getMessage}")
                        Abort.error(Result.Panic(t))
                }
            case _ =>
                // disable / require / verify-ca / verify-full: strict TLS behaviour.
                PostgresConnection.connect(
                    address.host,
                    address.port,
                    address.user,
                    address.db,
                    Present(password),
                    config.tls,
                    config.preparedStmtCacheSize,
                    config.preparedStmtTtl,
                    config.encodingRegistry
                )

    /** Returns true when a [[SqlException]] indicates the server requires SSL (sslmode=allow retry trigger).
      *
      * PostgreSQL sends SQLSTATE 28000 ("invalid_authorization_specification") when `hostssl` rules in pg_hba.conf require SSL but the
      * client connected plaintext. The StartupExchange wraps the server ErrorResponse as [[SqlException.Connection]] with the message field
      * (M) from the ErrorResponse prepended with "Authentication failed: ".
      *
      * Observed message patterns (locale-sensitive; matched by substring):
      *   - `"no pg_hba.conf entry for host ..., no encryption"`, postgres:16-alpine when only hostssl rules exist
      *   - `"no pg_hba.conf entry for host ..., SSL off"`, older PG versions
      *   - `"SSL connection is required"`, ALTER ROLE ... REQUIRE SSL
      *
      * NOTE: matching on message text is fragile to internationalization. There is no cleaner programmatic signal in the wire protocol
      * because StartupExchange wraps all ErrorResponse messages as Connection exceptions, losing the SQLSTATE field.
      */
    private def pgIsSslRequired(e: SqlException): Boolean =
        // "no encryption" = pg_hba.conf hostssl-only rule rejected plaintext
        // "SSL off" = older PG variants of the same rejection
        // "required" = ALTER ROLE ... REQUIRE SSL rejection
        // "SSL"/"ssl" = generic SSL-required wording
        def messageMatches(message: String): Boolean =
            message.contains("no encryption") ||
                message.contains("SSL off") ||
                (message.contains("SSL") && message.contains("required")) ||
                message.contains("SSL") || message.contains("ssl")
        e match
            case SqlException.Server(sqlState, _, message, _, _, _, _, _, _, _, _) =>
                sqlState == "28000" && messageMatches(message)
            case SqlException.Connection(message, _) =>
                messageMatches(message)
            case _ =>
                false
        end match
    end pgIsSslRequired

    // --- Notification helpers ---

    private def pumpNotifications(conn: PostgresConnection)(using Frame): Unit < Async =
        Abort.run[SqlException](conn.receive).flatMap {
            case Result.Failure(_) =>
                ()
            case Result.Panic(t) =>
                java.lang.System.err.println(s"[kyo-sql] pumpNotifications: panic: ${t.getMessage}")
                ()
            case Result.Success(msg) =>
                import kyo.internal.postgres.NotificationResponse
                msg match
                    case n: NotificationResponse =>
                        Abort.run[Closed](conn.notifications.offerDiscard(n)).andThen(pumpNotifications(conn))
                    case _ =>
                        pumpNotifications(conn)
                end match
        }

    private def emitNotifications(
        conn: PostgresConnection
    )(using Frame): Stream[SqlNotification, Async & Abort[SqlException]] =
        Stream[SqlNotification, Async & Abort[SqlException]]:
            Loop.foreach:
                Abort.run[Closed](conn.notifications.take).flatMap {
                    case Result.Success(n) =>
                        Emit.valueWith(Chunk(SqlNotification(n.channel, n.payload, n.processId)))(Loop.continue)
                    case Result.Failure(_) =>
                        Emit.valueWith(Chunk.empty[SqlNotification])(Loop.done)
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] notificationStream: take panic: ${t.getMessage}")
                        Abort.fail(SqlException.Connection(s"Notification channel panic: ${t.getMessage}", summon[Frame]))
                }

end PgSqlClientBackend

// --- MySQL backend ---

/** MySQL-backed [[SqlClientBackend]].
  *
  * Wraps a [[ConnectionPool[NetAddress, MysqlConnection]]] + per-address slot channels. The `query`/`execute`/`executeRaw`/`streamQuery` methods on the
  * public `Chunk[BoundParam[?]]` surface are not meaningful for MySQL (MySQL uses `BoundMysqlParam`); they raise a clear error. Callers
  * should use the `queryMysql`/`executeMysql`/`streamQueryMysql` variants or go through the MySQL-backed `SqlClient` extension methods.
  *
  * `cancel` opens a fresh authenticated sidecar connection to send `KILL QUERY <connectionId>`.
  *
  * ==Unsafe usage==
  *
  * Same as [[PgSqlClientBackend]]: `ConnectionPool` operations (`poll`, `release`, `discard`, `tryReserve`, `unreserve`, `close`) require
  * `AllowUnsafe`. `isAlive` / `discard` callbacks are STEERING case 2.
  */
final class MySqlClientBackend private[client] (
    private val pool: ConnectionPool[NetAddress, MysqlConnection],
    protected val slotChans: ConcurrentHashMap[SqlAddress, Channel[Unit]],
    val clientFrame: Frame,
    val metrics: SqlMetrics
) extends SqlClientBackend:

    // --- Row conversion helper ---

    /** Converts a [[MysqlRow]] to a [[Row]] with synthetic field descriptors.
      *
      * The synthetic [[FieldDescription]] contains the column name from [[ColumnDefinition41]]. OID and other PG-specific fields are set to
      * zero/defaults. This allows callers to use `row.column(name)` for name-based access.
      */
    private def mysqlRowToRow(r: MysqlRow): SqlRow =
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
        // The `format` field on the produced SqlRow is retained from the source MysqlRow so
        // callers that consume the raw byte column can differentiate binary vs text bytes.
        // Downstream typed decoding via `SqlRow.decode` currently routes MySQL rows through
        // `MysqlRowReader` which reads little-endian binary regardless of format, so callers
        // that need text-protocol simple-query results should compare raw bytes rather than call
        // `decode[T]`. A future generalisation should extend `MysqlRowReader` to also handle
        // text-protocol byte parsing for numeric types.
        new SqlRow(r.values, fields, r.format)
    end mysqlRowToRow

    // --- query / execute / executeRaw / streamQuery (BoundParam surface) ---

    def query(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundParam[?]],
        config: SqlClientConfig
    )(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        // PG-typed params are not used on MySQL; route simple queries through simple-query protocol.
        // The params chunk must be empty for this path to work correctly.
        if params.nonEmpty then
            Abort.fail(SqlException.Connection(
                "MySQL SqlClient.query with BoundParam is not supported, use SqlClient.queryMysqlFrag with BoundMysqlParam.",
                summon[Frame]
            ))
        else
            metrics.timedQuery(retryWith(address, password, config) { conn =>
                conn.simpleQuery(sql).map(rows => rows.map(mysqlRowToRow))
            })

    def execute(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundParam[?]],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException]) =
        if params.nonEmpty then
            Abort.fail(SqlException.Connection(
                "MySQL SqlClient.execute with BoundParam is not supported, use SqlClient.executeMysqlFrag with BoundMysqlParam.",
                summon[Frame]
            ))
        else
            metrics.timedQuery(retryWith(address, password, config)(conn => conn.simpleExecute(sql)))

    def executeRaw(
        address: SqlAddress,
        password: String,
        sql: String,
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException]) =
        metrics.timedQuery(retryWith(address, password, config)(conn => conn.simpleExecute(sql)))

    def streamQuery(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundParam[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        // Retry is intentionally absent here: mid-stream retries would require resetting the
        // cursor position, which the current Stream API does not support.
        if params.nonEmpty then
            Stream[SqlRow, Async & Abort[SqlException] & Scope](
                Abort.fail(SqlException.Connection(
                    "MySQL SqlClient.streamQuery with BoundParam is not supported, use SqlClient.streamQueryMysqlFrag with BoundMysqlParam.",
                    summon[Frame]
                ))
            )
        else
            Stream[SqlRow, Async & Abort[SqlException] & Scope](
                acquireStreamConn(address, password, config).flatMap { conn =>
                    conn.streamQuery(sql, Chunk.empty, batchSize)
                        .mapPure(mysqlRowToRow)
                        .emit
                }
            )

    // --- BoundValue surface (unified) ---

    def executeBound(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException]) =
        executeMysql(address, password, sql, params.flatMap(bv => SqlClientBackend.boundToMysql(bv)), config)

    def executeInsert(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        config: SqlClientConfig
    )(using Frame): InsertResult < (Async & Abort[SqlException]) =
        val myParams = params.flatMap(bv => SqlClientBackend.boundToMysql(bv))
        metrics.timedQuery(retryWith(address, password, config)(conn => conn.extendedExecuteInsert(sql, myParams)))
    end executeInsert

    def queryBound(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        config: SqlClientConfig
    )(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        queryMysql(address, password, sql, params.flatMap(bv => SqlClientBackend.boundToMysql(bv)), config)

    def streamBound(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundValue[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        streamQueryMysqlRows(address, password, sql, params.flatMap(bv => SqlClientBackend.boundToMysql(bv)), batchSize, config)

    // --- MySQL-typed operations ---

    def queryMysql(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        config: SqlClientConfig
    )(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        metrics.timedQuery(retryWith(address, password, config)(conn => conn.extendedQuery(sql, params).map(_.map(mysqlRowToRow))))

    def executeMysql(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException]) =
        metrics.timedQuery(retryWith(address, password, config)(conn => conn.extendedExecute(sql, params)))

    def streamQueryMysql(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[MysqlRow, Async & Abort[SqlException] & Scope] =
        Stream[MysqlRow, Async & Abort[SqlException] & Scope](
            acquireStreamConn(address, password, config).flatMap { conn =>
                conn.streamQuery(sql, params, batchSize).emit
            }
        )

    def streamQueryMysqlRows(
        address: SqlAddress,
        password: String,
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        batchSize: Int,
        config: SqlClientConfig
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            acquireStreamConn(address, password, config).flatMap { conn =>
                conn.streamQuery(sql, params, batchSize)
                    .mapPure(mysqlRowToRow)
                    .emit
            }
        )

    // --- withConnection ---

    def withConnection[A, S](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: PostgresConnection => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException]) =
        Abort.fail(SqlException.Connection("withConnection (Postgres) is not supported on the MySQL backend", summon[Frame]))

    def withMysqlConnection[A, S](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: MysqlConnection => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException]) =
        // Unsafe: getOrCreateSlotChanUnsafe and all pool ops require AllowUnsafe.
        // STEERING case 2: bridging to kyo-net ConnectionPool.
        Sync.Unsafe.defer(getOrCreateSlotChanUnsafe(address, config.maxConnections)).flatMap { slotCh =>
            val netKey = NetAddress.Tcp(address.host, address.port)
            withSlotS[A, S](slotCh, config) {
                Sync.Unsafe.defer(spinAcquire(netKey)).flatMap {
                    case Present(conn) =>
                        conn.connectionId.get.flatMap { connId =>
                            releaseOnExitS[A, S](netKey, conn, connId)(f(conn))
                        }
                    case Absent =>
                        // Per-attempt in-flight slot release, see acquireAndRun for the rationale.
                        // Keeps `withMysqlConnection` safe under user-level retry wrappers.
                        Abort.run[SqlException](connectAndRunS[A, S](address, password, netKey, config)(f)).flatMap { result =>
                            Sync.Unsafe.defer(poolUnreserve(netKey)).andThen {
                                result match
                                    case Result.Success(a) => (a: A < (S & Async & Abort[SqlException]))
                                    case Result.Failure(e) => Abort.fail[SqlException](e)
                                    case Result.Panic(t)   => Abort.error(Result.Panic(t))
                            }
                        }
                }
            }
        }
    end withMysqlConnection

    def withAdvisoryLock[A, S](
        address: SqlAddress,
        password: String,
        key: Long,
        timeout: Maybe[Duration],
        config: SqlClientConfig
    )(body: A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException]) =
        val name           = key.toString
        val timeoutSeconds = timeout.fold(-1L)(d => Math.max(0L, d.toSeconds))
        // Simple (text) query on the pinned connection: `GET_LOCK`'s BIGINT result comes back
        // as the ASCII string "1"/"0"/NULL. The matching `RELEASE_LOCK` targets the same session.
        val lockSql    = s"SELECT GET_LOCK('$name', $timeoutSeconds)"
        val releaseSql = s"SELECT RELEASE_LOCK('$name')"
        withMysqlConnection(address, password, config) { conn =>
            conn.simpleQuery(lockSql).flatMap { rows =>
                val acquired =
                    rows.headMaybe match
                        case Present(row) =>
                            row.column(0) match
                                case Present(bytes) =>
                                    new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8) == "1"
                                case Absent => false
                        case Absent => false
                if !acquired then
                    Abort.fail[SqlException](SqlException.Request(
                        s"GET_LOCK('$name', $timeoutSeconds) timed out or failed (key=$key)",
                        Maybe.Absent,
                        summon[Frame]
                    ))
                else
                    // Both success and error paths run RELEASE_LOCK on the pinned connection
                    // before it returns to the pool. The release failure is swallowed so it
                    // does not shadow the body's outcome.
                    Abort.run[SqlException](body).flatMap { result =>
                        Abort.run[SqlException](conn.simpleExecute(releaseSql)).andThen {
                            result match
                                case Result.Success(a) => a
                                case Result.Failure(e) => Abort.fail[SqlException](e)
                                case Result.Panic(t)   => Abort.error(Result.Panic(t))
                        }
                    }
                end if
            }
        }
    end withAdvisoryLock

    // --- Cancel ---

    def cancel(handle: SqlCancelHandle.Pg)(using Frame): Unit < (Async & Abort[SqlException]) =
        Abort.fail(SqlException.Connection(
            "Postgres cancel (CancelRequest) is not supported on the MySQL backend. Use a MySQL client.",
            summon[Frame]
        ))

    /** Cancels the MySQL query via `KILL QUERY <connectionId>` on a fresh out-of-pool connection.
      *
      * Opens a NEW authenticated MySQL connection (bypassing the pool entirely to avoid two failure modes:
      *   1. Pool exhaustion deadlock: the pool might have no free connections to allocate the sidecar.
      *   2. Self-cancellation: the pool might return the same connection as the target (e.g. after the target's query finishes and the
      *      connection is released back to the pool before cancel fires).
      *
      * Sends `KILL QUERY <connectionId>` on the fresh sidecar, then closes it. The target connection will receive ER_QUERY_INTERRUPTED /
      * SQLSTATE `70100`. If `cancelTimeout` is finite, the entire open+kill+close is wrapped in a timeout.
      */
    def cancelMysql(handle: SqlCancelHandle.My, password: String, config: SqlClientConfig)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        val addr = handle.address
        val doCancel: Unit < (Async & Abort[SqlException] & Scope) =
            Scope.acquireRelease(
                MysqlConnection.connect(
                    addr.host,
                    addr.port,
                    addr.user,
                    Maybe.Present(password),
                    Maybe.Present(addr.db),
                    Maybe.Absent,
                    64,
                    Duration.Infinity
                )
            )(_.closeNow).flatMap {
                sidecarConn =>
                    import kyo.internal.mysql.exchange.MysqlCancelExchange
                    Abort.run[SqlException](MysqlCancelExchange.kill(sidecarConn, handle.connectionId)).flatMap {
                        result =>
                            // Attempt graceful quit before Scope.ensure closes the socket.
                            // Scope.acquireRelease guarantees closeNow on every exit edge
                            // (timeout, interrupt, or normal) so the sidecar TCP socket never leaks.
                            Abort.run(sidecarConn.quit()).andThen {
                                result match
                                    case Result.Success(_) => ()
                                    case Result.Failure(e) => Abort.fail(e)
                                    case Result.Panic(t)   => Abort.error(Result.Panic(t))
                            }
                    }
            }
        val timed: Unit < (Async & Abort[SqlException] & Scope) =
            if config.cancelTimeout == Duration.Infinity then doCancel
            else
                Async.timeoutWithError(
                    config.cancelTimeout,
                    Result.Failure(SqlException.Connection(s"MySQL cancel timed out after ${config.cancelTimeout}", summon[Frame]))
                )(doCancel)
        Scope.run(timed)
    end cancelMysql

    // --- withCancelInfo / withCancelInfoMysql ---

    def withCancelInfo[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: (PostgresConnection, Int, Int) => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        Abort.fail(SqlException.Connection("withCancelInfo (Postgres) is not supported on the MySQL backend", summon[Frame]))

    /** Acquires a MySQL connection from the pool, exposes its `connectionId`, and calls `f`.
      *
      * The connection is held for the lifetime of `f` (not returned to the pool until `f` completes), so the `connectionId` remains stable
      * and the cancellation handle is valid for the full duration of the query.
      */
    def withCancelInfoMysql[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: (MysqlConnection, Long) => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        poolWith(address, password, config) { conn =>
            conn.connectionId.get.flatMap { connId =>
                f(conn, connId)
            }
        }
    end withCancelInfoMysql

    // --- Notifications (PG-only) ---

    def notificationStream(
        address: SqlAddress,
        password: String,
        channel: String,
        config: SqlClientConfig
    )(using Frame): Stream[SqlNotification, Async & Abort[SqlException] & Scope] =
        Stream[SqlNotification, Async & Abort[SqlException] & Scope](
            Abort.fail(SqlException.Connection("PostgreSQL LISTEN/NOTIFY is not supported on the MySQL backend", summon[Frame]))
        )

    // --- MySQL savepoint helpers ---

    def mysqlSavepointTransaction(conn: MysqlConnection, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        conn.savepointTransaction(name)

    def mysqlReleaseSavepoint(conn: MysqlConnection, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        conn.releaseSavepointTransaction(name)

    def mysqlRollbackToSavepoint(conn: MysqlConnection, name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        conn.rollbackToSavepointTransaction(name)

    // --- loadLocalInfileMysql ---

    def loadLocalInfileMysql[S](
        address: SqlAddress,
        password: String,
        sql: String,
        data: Stream[Byte, S],
        config: SqlClientConfig
    )(using Frame): Long < (Async & Abort[SqlException] & S) =
        withMysqlConnection(address, password, config) { conn =>
            conn.loadLocalInfile(sql, data)
        }
    end loadLocalInfileMysql

    // --- warmUp ---

    def warmUp(
        address: SqlAddress,
        password: String,
        n: Int,
        config: SqlClientConfig
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        if n <= 0 then ()
        else
            val netKey = NetAddress.Tcp(address.host, address.port)
            // Open n connections concurrently, materialising all results so partial successes are visible.
            // Each connect runs under its own Abort.run so a single failure does not short-circuit the rest.
            Async.fill(n, concurrency = n) {
                Abort.run[SqlException](myConnect(address, password, config))
            }.flatMap { results =>
                val successes = results.collect { case Result.Success(conn) => conn }
                val firstFailure = results.collectFirst {
                    case Result.Failure(e: SqlException) =>
                        // Preserve the typed exception: callers' try/catch on specific subclasses (e.g., SqlException.Server
                        // for auth/permission failures) must see the original class, not a blanket Connection wrap.
                        // Non-SqlException transport errors are not reachable here, Abort.run[SqlException] only captures
                        // SqlException failures; anything else becomes a Result.Panic.
                        e
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] MySqlClientBackend.warmUp: unexpected panic: ${t.getMessage}")
                        SqlException.Connection(s"Warm-up panic: ${t.getMessage}", summon[Frame])
                }
                firstFailure match
                    case None =>
                        // All connections succeeded, release them to the idle pool.
                        Sync.Unsafe.defer {
                            successes.foreach { conn =>
                                poolRelease(netKey, conn)
                            }
                        }
                    case Some(e) =>
                        // At least one connection failed, close the successful ones to avoid fd leaks,
                        // then propagate the error.
                        // Unsafe: conn.closeNow is Unit < Sync; evalOrThrow is safe here.
                        Sync.Unsafe.defer {
                            successes.foreach { conn =>
                                discard(Sync.Unsafe.evalOrThrow(conn.closeNow))
                            }
                        }.andThen(Abort.fail(e))
                end match
            }

    // --- closeAll ---

    def closeAll(gracePeriod: Duration)(using Frame): Unit < Async =
        // Step 1: Mark the pool as closed and drain idle connections.
        // pool.close() sets closed=true so tryReserve returns false, no new connections are established.
        // Slot channels are kept open during the grace period so in-flight connections can offer slots back.
        // Unsafe: pool.close() is a lock-free drain; requires AllowUnsafe.
        // STEERING case 2: cleanup on shutdown path.
        val idleConnsK: Chunk[MysqlConnection] < Sync =
            Sync.Unsafe.defer(pool.close())
        idleConnsK.flatMap { idleConns =>
            // Step 2: Wait up to gracePeriod for in-flight connections to complete.
            // In-flight count = sum over slot channels of (channel.capacity - channel.size).
            // When every slot channel is full again (size == capacity), all in-flight connections have returned their slots.
            val drainK: Unit < Async =
                if gracePeriod == Duration.Zero then ()
                else
                    // Snapshot of each slot channel. Channel.capacity gives the max slots.
                    val chanSnapshots: Chunk[Channel[Unit]] =
                        val buf = ChunkBuilder.init[Channel[Unit]]
                        slotChans.forEach { (_, ch) => buf.addOne(ch) }
                        buf.result()
                    end chanSnapshots
                    if chanSnapshots.isEmpty then ()
                    else
                        // Poll until all channels are at full capacity or the grace period expires.
                        // Each slot channel starts at capacity; in-flight connections hold taken slots.
                        // When a connection finishes and offers its slot back, size increases toward capacity.
                        def allDrained(): Boolean < Sync =
                            Sync.Unsafe.defer {
                                chanSnapshots.forall { ch =>
                                    Sync.Unsafe.evalOrThrow(Abort.run[Closed](ch.size)) match
                                        case Result.Success(sz) => sz >= ch.capacity
                                        case _                  => true // channel already closed, treat as drained
                                }
                            }
                        end allDrained
                        def pollLoop(): Unit < Async =
                            allDrained().flatMap {
                                case true  => ()
                                case false => Async.sleep(10.millis).andThen(pollLoop())
                            }
                        // Ignore timeout: after the grace period we force-close regardless.
                        Abort.run[Timeout](Async.timeout(gracePeriod)(pollLoop())).unit
                    end if
            end drainK
            drainK.andThen {
                // Step 3: Force-close slot channels and remaining connections.
                // Unsafe: channel.close and pool cleanup require AllowUnsafe.
                // STEERING case 2: cleanup on shutdown path.
                Sync.Unsafe.defer {
                    slotChans.forEach { (_, ch) =>
                        discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](ch.close)))
                    }
                    slotChans.clear()
                    // Synchronous force-close (cannot be interrupted) for the idle conns.
                    // Replaces Kyo.foreach so that a fiber interrupt mid-drain cannot leave
                    // connections un-closed (G-Leak-5). quit() is skipped on the shutdown
                    // path, closeNow (TCP teardown) is sufficient; the server detects EOF.
                    idleConns.foreach { conn =>
                        discard(Sync.Unsafe.evalOrThrow(conn.closeNow))
                    }
                }
            }
        }
    end closeAll

    // --- Pool helpers ---

    private def poolPoll(key: NetAddress)(using AllowUnsafe): Maybe[MysqlConnection] =
        pool.poll(key)

    private def poolRelease(key: NetAddress, conn: MysqlConnection)(using AllowUnsafe): Unit =
        pool.release(key, conn)

    private def poolDiscard(conn: MysqlConnection)(using AllowUnsafe): Unit =
        pool.discard(conn)

    private def poolTryReserve(key: NetAddress)(using AllowUnsafe): Boolean =
        pool.tryReserve(key)

    private def poolUnreserve(key: NetAddress)(using AllowUnsafe): Unit =
        pool.unreserve(key)

    // --- Layer 1: retry ---

    private def retryWith[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: MysqlConnection => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        config.retrySchedule match
            case Absent =>
                poolWith(address, password, config)(f)
            case Present(schedule) =>
                // Wrap each retry attempt: on each re-entry after the first we record a retry.
                // The attempt counter starts at 0 and is incremented atomically before each pool call.
                // The first attempt (prev=0) is NOT a retry; subsequent calls (prev=1, 2, …) record a retry.
                AtomicInt.init(0).flatMap { counter =>
                    Retry[SqlException.Connection](schedule) {
                        counter.getAndUpdate(_ + 1).flatMap { prev =>
                            val recordRetry: Unit < (Async & Abort[SqlException]) =
                                if prev == 0 then ()
                                else
                                    Log.warn(
                                        s"kyo.sql: retrying after connection failure attempt=$prev schedule=$schedule"
                                    ).andThen(metrics.recordRetry)
                            recordRetry.flatMap(_ => poolWith(address, password, config)(f))
                        }
                    }
                }
    end retryWith

    // --- Layer 2: pool ---

    private def poolWith[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: MysqlConnection => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        // Unsafe: getOrCreateSlotChanUnsafe runs Channel.initUnscoped synchronously on the hot path.
        // STEERING case 2: lazy initialisation of a Channel that is pure-Sync.
        Sync.Unsafe.defer(getOrCreateSlotChanUnsafe(address, config.maxConnections)).flatMap { slotCh =>
            withSlot(slotCh, config) {
                acquireAndRun(address, password, config)(f)
            }
        }
    end poolWith

    // --- Layer 3: acquire + execute ---

    private def acquireAndRun[A](
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(f: MysqlConnection => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        val netKey = NetAddress.Tcp(address.host, address.port)
        val timedF: MysqlConnection => A < (Async & Abort[SqlException]) =
            if config.queryTimeout == Duration.Infinity then f
            else
                conn =>
                    Async.timeoutWithError(
                        config.queryTimeout,
                        // Connection, not Request: see the sibling Pg path for the wire-desync rationale
                        // the pool must see this as fatal so the timed-out connection is discarded.
                        Result.Failure(SqlException.Connection(
                            s"Query exceeded timeout of ${config.queryTimeout}",
                            summon[Frame]
                        ))
                    )(f(conn))
        // Unsafe: pool operations require AllowUnsafe (lock-free CAS on ring buffer).
        // STEERING case 2: bridging to kyo-net ConnectionPool whose API requires AllowUnsafe.
        Sync.Unsafe.defer(spinAcquire(netKey)).flatMap {
            case Present(conn) =>
                conn.connectionId.get.flatMap { connId =>
                    metrics.recordAcquire.andThen(releaseOnExit(netKey, conn, connId)(timedF(conn)))
                }
            case Absent =>
                // Per-attempt in-flight slot release, see PgSqlClientBackend.acquireAndRun for the rationale.
                // `Sync.ensure(poolUnreserve)` only fires on the outermost completion, so under `Retry` the
                // `inFlight` counter would saturate at `maxConnections` and `spinAcquire` would enter an
                // unbounded CPU spin (poll Absent → tryReserve false → recurse).
                Abort.run[SqlException](connectAndRun(address, password, netKey, config)(timedF)).flatMap { result =>
                    Sync.Unsafe.defer(poolUnreserve(netKey)).andThen {
                        result match
                            case Result.Success(a) => (a: A < (Async & Abort[SqlException]))
                            case Result.Failure(e) => Abort.fail[SqlException](e)
                            case Result.Panic(t)   => Abort.error(Result.Panic(t))
                    }
                }
        }
    end acquireAndRun

    @annotation.tailrec
    private def spinAcquire(netKey: NetAddress)(using AllowUnsafe): Maybe[MysqlConnection] =
        poolPoll(netKey) match
            case Present(conn) => Present(conn)
            case Absent =>
                if poolTryReserve(netKey) then Absent
                else spinAcquire(netKey)

    // Slot release is owned by the OUTER `withSlot`/Scope.ensure wrapper at poolWith /
    // withMysqlConnection / acquireStreamConn, it fires unconditionally on flow exit.
    // The connection-lifecycle release here only handles pool return/discard. Don't re-offer
    // the slot here or you'll double-release and over-fill the slot channel beyond maxConnections.
    private def releaseOnExit[A](netKey: NetAddress, conn: MysqlConnection, connId: Long)(
        body: A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[SqlException]) =
        // Capture the current logger now (inside any Log.let scope) so the close log fires to the
        // same sink even though Sync.ensure callbacks run after Local context is unwound.
        Log.use { logger =>
            Sync.ensure { (error: Maybe[Result.Error[Any]]) =>
                // Unsafe: pool.release / pool.discard require AllowUnsafe.
                // STEERING case 2: Sync.ensure callback runs outside fiber suspension.
                Sync.Unsafe.defer {
                    if SqlClientBackend.shouldReleaseOnExit(error) then
                        poolRelease(netKey, conn)
                        discard(Sync.Unsafe.evalOrThrow(metrics.recordRelease))
                        logger.unsafe.debug(s"kyo.sql: closed connection id=$connId reason=released")
                    else
                        poolDiscard(conn)
                        discard(Sync.Unsafe.evalOrThrow(metrics.recordDiscard))
                        logger.unsafe.debug(s"kyo.sql: closed connection id=$connId reason=discarded")
                    end if
                }
            }(body)
        }

    private def connectAndRun[A](
        address: SqlAddress,
        password: String,
        netKey: NetAddress,
        config: SqlClientConfig
    )(f: MysqlConnection => A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        myConnect(address, password, config).flatMap { conn =>
            conn.connectionId.get.flatMap { connId =>
                Log.debug(
                    s"kyo.sql: opened connection id=$connId host=${address.host} port=${address.port} tls=${config.tls.isDefined}"
                )
                    .andThen(metrics.recordAcquire)
                    .andThen(releaseOnExit(netKey, conn, connId)(f(conn)))
            }
        }
    end connectAndRun

    private def releaseOnExitS[A, S](netKey: NetAddress, conn: MysqlConnection, connId: Long)(
        body: A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException]) =
        // Capture the current logger now (inside any Log.let scope) so the close log fires to the
        // same sink even though Sync.ensure callbacks run after Local context is unwound.
        Log.use { logger =>
            Sync.ensure { (error: Maybe[Result.Error[Any]]) =>
                // Unsafe: pool.release / pool.discard require AllowUnsafe.
                // STEERING case 2: Sync.ensure callback runs outside fiber suspension.
                Sync.Unsafe.defer {
                    if SqlClientBackend.shouldReleaseOnExit(error) then
                        poolRelease(netKey, conn)
                        discard(Sync.Unsafe.evalOrThrow(metrics.recordRelease))
                        logger.unsafe.debug(s"kyo.sql: closed connection id=$connId reason=released")
                    else
                        poolDiscard(conn)
                        discard(Sync.Unsafe.evalOrThrow(metrics.recordDiscard))
                        logger.unsafe.debug(s"kyo.sql: closed connection id=$connId reason=discarded")
                    end if
                }
            }(body)
        }

    private def connectAndRunS[A, S](
        address: SqlAddress,
        password: String,
        netKey: NetAddress,
        config: SqlClientConfig
    )(f: MysqlConnection => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Abort[SqlException]) =
        myConnect(address, password, config).flatMap { conn =>
            conn.connectionId.get.flatMap { connId =>
                Log.debug(
                    s"kyo.sql: opened connection id=$connId host=${address.host} port=${address.port} tls=${config.tls.isDefined}"
                )
                    .andThen(metrics.recordAcquire)
                    .andThen(releaseOnExitS[A, S](netKey, conn, connId)(f(conn)))
            }
        }
    end connectAndRunS

    private def acquireStreamConn(
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(using Frame): MysqlConnection < (Async & Abort[SqlException] & Scope) =
        val netKey = NetAddress.Tcp(address.host, address.port)
        // Unsafe: getOrCreateSlotChanUnsafe and pool ops need AllowUnsafe.
        // STEERING case 2: bridging to kyo-net ConnectionPool.
        Sync.Unsafe.defer(getOrCreateSlotChanUnsafe(address, config.maxConnections)).flatMap { slotCh =>
            // Take slot then register slot release in Scope.ensure BEFORE attempting connect.
            // If myConnect fails, the surrounding Scope still closes and releases the slot,
            // preventing the leak that caused dead-pool deadlock under server restart.
            Abort.run[SqlException](takeSlotForStream(slotCh, config)).flatMap {
                case Result.Failure(e @ SqlException.Connection(msg, _)) if msg.startsWith("Timed out") =>
                    Log.warn(
                        s"kyo.sql: pool acquire timeout after ${config.acquireTimeout} poolSize=${config.maxConnections}"
                    ).andThen(Abort.fail(e))
                case Result.Failure(e) => Abort.fail(e)
                case Result.Panic(t)   => Abort.error(Result.Panic(t))
                case Result.Success(()) =>
                    Scope.ensure {
                        Sync.Unsafe.defer {
                            discard(Sync.Unsafe.evalOrThrow(Abort.run[Closed](slotCh.offer(()))))
                        }
                    }.andThen(acquireStreamSlot(address, password, netKey, config))
            }
        }
    end acquireStreamConn

    private def takeSlotForStream(slotCh: Channel[Unit], config: SqlClientConfig)(using Frame): Unit < (Async & Abort[SqlException]) =
        if config.acquireTimeout == Duration.Infinity then
            Abort.run[Closed](slotCh.take).flatMap {
                case Result.Success(()) => ()
                case Result.Failure(_)  => Abort.fail(SqlException.Connection("Connection pool is closed", summon[Frame]))
                case Result.Panic(t) =>
                    java.lang.System.err.println(s"[kyo-sql] MySqlClientBackend.acquireStreamConn panic: ${t.getMessage}")
                    Abort.error(Result.Panic(t))
            }
        else
            Async.timeoutWithError(
                config.acquireTimeout,
                Result.Failure(SqlException.Connection(
                    s"Timed out waiting ${config.acquireTimeout} for a connection (pool exhausted)",
                    summon[Frame]
                ))
            )(
                Abort.run[Closed](slotCh.take).flatMap {
                    case Result.Success(()) => ()
                    case Result.Failure(_)  => Abort.fail(SqlException.Connection("Connection pool is closed", summon[Frame]))
                    case Result.Panic(t) =>
                        java.lang.System.err.println(
                            s"[kyo-sql] MySqlClientBackend.acquireStreamConn panic (timeout path): ${t.getMessage}"
                        )
                        Abort.error(Result.Panic(t))
                }
            )

    private def acquireStreamSlot(
        address: SqlAddress,
        password: String,
        netKey: NetAddress,
        config: SqlClientConfig
    )(using Frame): MysqlConnection < (Async & Abort[SqlException] & Scope) =
        // Unsafe: pool ops (spinAcquire, poolRelease, poolUnreserve, poolDiscard) require AllowUnsafe; Scope.ensure callback runs outside fiber suspension.
        // STEERING case 2: bridging to kyo-net ConnectionPool and cleanup path from Scope.ensure.
        // Slot release is owned by the OUTER Scope.ensure in acquireStreamConn, the per-conn
        // Scope.ensure here only handles connection lifecycle.
        // G-Leak-3 fix: on stream abort we check whether the error is protocol-fatal.
        // Protocol-fatal errors (Connection, Decode, Server with sqlState 08*/25*) leave the wire
        // desynchronised, returning such a connection to the pool would corrupt the next borrower.
        // Non-fatal errors (Unsupported, Request, Server with other states) leave the wire intact.
        Sync.Unsafe.defer(spinAcquire(netKey)).flatMap {
            case Present(conn) =>
                Scope.ensure { error =>
                    // Unsafe: pool ops require AllowUnsafe.
                    // STEERING case 2: Scope.ensure callback runs outside fiber suspension.
                    Sync.Unsafe.defer {
                        error match
                            case Absent =>
                                poolRelease(netKey, conn)
                            case Present(Result.Failure(e: SqlException)) if SqlClientBackend.isProtocolFatal(e) =>
                                poolDiscard(conn)
                            case Present(_: Result.Panic) =>
                                // Wire state is unknown after a panic, discard rather than release.
                                poolDiscard(conn)
                            case _ =>
                                poolRelease(netKey, conn)
                    }
                }.andThen(conn)
            case Absent =>
                Sync.ensure(Sync.Unsafe.defer(poolUnreserve(netKey))) {
                    myConnect(address, password, config).flatMap { conn =>
                        Scope.ensure { error =>
                            // Unsafe: pool ops require AllowUnsafe.
                            // STEERING case 2: Scope.ensure callback runs outside fiber suspension.
                            Sync.Unsafe.defer {
                                error match
                                    case Absent =>
                                        poolRelease(netKey, conn)
                                    case Present(Result.Failure(e: SqlException)) if SqlClientBackend.isProtocolFatal(e) =>
                                        poolDiscard(conn)
                                    case Present(_: Result.Panic) =>
                                        // Wire state is unknown after a panic, discard rather than release.
                                        poolDiscard(conn)
                                    case _ =>
                                        poolRelease(netKey, conn)
                            }
                        }.andThen(conn)
                    }
                }
        }
    end acquireStreamSlot

    // --- Mode-aware MySQL connect helper ---

    /** Bounds a MySQL connection-establishment attempt by `acquireTimeout`. See [[PgSqlClientBackend.boundedConnect]] for rationale. */
    private def boundedMyConnect(
        config: SqlClientConfig,
        address: SqlAddress
    )(connect: MysqlConnection < (Async & Abort[SqlException]))(using
        Frame
    ): MysqlConnection < (Async & Abort[SqlException]) =
        if config.acquireTimeout == Duration.Infinity then connect
        else
            Async.timeoutWithError(
                config.acquireTimeout,
                Result.Failure(SqlException.Connection(
                    s"Timed out after ${config.acquireTimeout} establishing connection to ${address.host}:${address.port}",
                    summon[Frame]
                ))
            )(connect)

    /** Connects to MySQL with mode-aware TLS handling.
      *
      *   - `sslmode=disable` / `require` / `verify-ca` / `verify-full`: delegates to [[MysqlConnection.connect]] (strict TLS behaviour).
      *   - `sslmode=prefer`: uses [[MysqlConnection.connectWithMode]] with `preferFallback=true` so [[HandshakeExchange]] falls back to
      *     plaintext if the server does not advertise CLIENT_SSL.
      *   - `sslmode=allow`: first attempts plaintext (`tls=Absent`); if the server responds with ER_SECURE_TRANSPORT_REQUIRED (error 3159),
      *     retries with `tls=Present`.
      *
      * Bounded by `acquireTimeout` to ensure a stuck connect or handshake against a partially-up server cannot stall the retry schedule.
      */
    private def myConnect(
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(using Frame): MysqlConnection < (Async & Abort[SqlException]) = boundedMyConnect(config, address) {
        myConnectInner(address, password, config)
    }

    private def myConnectInner(
        address: SqlAddress,
        password: String,
        config: SqlClientConfig
    )(using Frame): MysqlConnection < (Async & Abort[SqlException]) =
        config.tlsMode match
            case TlsMode.Prefer =>
                // prefer: attempt TLS upgrade if server advertises CLIENT_SSL; fall back to plaintext otherwise.
                MysqlConnection.connectWithMode(
                    address.host,
                    address.port,
                    address.user,
                    Present(password),
                    Present(address.db),
                    config.tls,
                    TlsMode.Prefer,
                    config.preparedStmtCacheSize,
                    config.preparedStmtTtl
                )
            case TlsMode.Allow =>
                // allow: attempt plaintext first; if ER_SECURE_TRANSPORT_REQUIRED (3159), reconnect with TLS.
                val plaintextAttempt =
                    MysqlConnection.connect(
                        address.host,
                        address.port,
                        address.user,
                        Present(password),
                        Present(address.db),
                        Maybe.Absent,
                        config.preparedStmtCacheSize,
                        config.preparedStmtTtl
                    )
                Abort.run[SqlException](plaintextAttempt).flatMap {
                    case Result.Success(conn) =>
                        conn
                    case Result.Failure(e) if myIsSslRequired(e) =>
                        // Server requires SSL, reconnect with TLS using prefer-fallback mode so if the reconnect
                        // server also doesn't advertise CLIENT_SSL, we get a clean error rather than infinite loop.
                        config.tls match
                            case Present(_) =>
                                MysqlConnection.connectWithMode(
                                    address.host,
                                    address.port,
                                    address.user,
                                    Present(password),
                                    Present(address.db),
                                    config.tls,
                                    TlsMode.Require,
                                    config.preparedStmtCacheSize,
                                    config.preparedStmtTtl
                                )
                            case Absent =>
                                Abort.fail(e)
                    case Result.Failure(e) =>
                        Abort.fail(e)
                    case Result.Panic(t) =>
                        java.lang.System.err.println(s"[kyo-sql] MySqlClientBackend.myConnect: allow plaintext panic: ${t.getMessage}")
                        Abort.error(Result.Panic(t))
                }
            case _ =>
                // disable / require / verify-ca / verify-full: strict TLS behaviour.
                MysqlConnection.connect(
                    address.host,
                    address.port,
                    address.user,
                    Present(password),
                    Present(address.db),
                    config.tls,
                    config.preparedStmtCacheSize,
                    config.preparedStmtTtl
                )

    /** Returns true when a [[SqlException]] indicates MySQL requires secure transport (ER_SECURE_TRANSPORT_REQUIRED, error code 3159).
      *
      * MySQL sends error 3159 with message "Connections using insecure transport are prohibited while --require_secure_transport=ON." when
      * the server is configured with `require_secure_transport=ON` and the client connects without TLS.
      */
    private def myIsSslRequired(e: SqlException): Boolean =
        e match
            case SqlException.Connection(message, _) =>
                // Authentication-level connection error containing SSL indicator.
                message.contains("3159") || (message.contains("secure") && message.contains("transport"))
            case SqlException.Server(_, _, message, _, _, _, extra, _, _, _, _) =>
                // Server error code 3159 (ER_SECURE_TRANSPORT_REQUIRED).
                extra.get("code").contains("3159") || message.contains("secure_transport") || message.contains("3159")
            case _ =>
                false

end MySqlClientBackend

// --- Companion object ---

object SqlClientBackend:

    /** Returns true if the exception indicates the connection is protocol-poisoned and must be discarded rather than returned to the pool.
      *
      * Protocol-fatal categories:
      *   - [[SqlException.Connection]], transport-level failure; the socket is already unusable.
      *   - [[SqlException.Decode]], wire-format desync; the framing is out of sync with the server.
      *   - [[SqlException.Server]] with SQLSTATE class `08` (connection exception) or `25` (invalid transaction state), the server
      *     terminated or invalidated the connection at the protocol level.
      *
      * Non-fatal categories (connection is still healthy):
      *   - [[SqlException.Unsupported]], an operation not supported by this backend; the wire is fine.
      *   - [[SqlException.Request]], a client-side encoding error; the wire is fine.
      *   - [[SqlException.Server]] with other SQLSTATE classes, a query-level error; the wire is fine.
      */
    private[kyo] def isProtocolFatal(e: SqlException): Boolean =
        e match
            case _: SqlException.Connection  => true
            case _: SqlException.Decode      => true
            case s: SqlException.Server      => s.sqlState.startsWith("08") || s.sqlState.startsWith("25")
            case _: SqlException.Unsupported => false
            case _: SqlException.Request     => false

    /** Pool-lifecycle policy applied by the `releaseOnExit` finalizers: release a healthy connection back to the pool, discard on anything
      * that would leave the next borrower with a corrupted or half-consumed wire.
      *
      * The default `Absent` case matches a normal exit; a non-SqlException failure, a panic, an interrupt, or a protocol-fatal SqlException
      * all fall through to `discard`. A `SqlException` that is not protocol-fatal (a routine server error like `23505` unique-violation, a
      * client-side `Unsupported` / `Request` that never reached the wire) is releasable.
      */
    private[kyo] def shouldReleaseOnExit(error: Maybe[Result.Error[Any]]): Boolean =
        error match
            case Absent                                   => true
            case Present(Result.Failure(e: SqlException)) => !isProtocolFatal(e)
            case Present(_)                               => false

    /** Converts a [[BoundValue]] to the Postgres-native [[BoundParam]] chunk by invoking the schema's `writePostgres` encoder.
      *
      * A scalar schema produces exactly one [[BoundParam]]; case-class schemas produce one per field. Returning a [[Chunk]] keeps the
      * caller (`flatMap`) backend-agnostic.
      */
    private[kyo] def boundToPostgres(bv: BoundValue[?])(using Frame): Chunk[BoundParam[?]] =
        bv match
            case b: BoundValue[a] => b.schema.writePostgres(b.value)

    /** Converts a [[BoundValue]] to the MySQL-native [[BoundMysqlParam]] chunk by invoking the schema's `writeMysql` encoder. */
    private[kyo] def boundToMysql(bv: BoundValue[?])(using Frame): Chunk[BoundMysqlParam[?]] =
        bv match
            case b: BoundValue[a] => b.schema.writeMysql(b.value)

    // Phase 56 audit (#512), caching encoder dispatch per-SqlSchema does not apply to the current
    // implementation. `writePostgres` / `writeMysql` allocate a fresh PostgresParamWriter /
    // MysqlParamWriter per call and invoke the Schema's closed-over `writeFn` lambda. The lambda
    // itself is allocated ONCE at schema construction (Schema.init) and reused across calls, there
    // is no per-call encoder-lookup walk. Inside the param writer, each primitive method (`int`,
    // `string`, ...) appends a BoundParam wrapping a statically-referenced encoder constant
    // (`PostgresEncoder.int4Binary`, etc.); no Map lookup, no field-by-field dispatch.
    // The audit's caching gain was hypothetical against an implementation that doesn't exist; the
    // actual hot path already runs the minimum work (one writer alloc + the field-walk that's
    // unavoidable because the parameter values differ each call). Closing out #512 as
    // non-applicable.

    /** Creates a Postgres-backed [[SqlClientBackend]] wrapped by a fresh [[ConnectionPool[NetAddress, PostgresConnection]]].
      *
      * @param config
      *   pool parameters; `maxConnections` must be >= 2 (ConnectionPool ring-buffer constraint).
      * @param frame
      *   captured from the call site so the pool's isAlive/discard callbacks carry a real source location.
      */
    def initPg(config: SqlClientConfig, frame: Frame)(using AllowUnsafe): PgSqlClientBackend =
        given capturedFrame: Frame = frame
        val pool = ConnectionPool.init[NetAddress, PostgresConnection](
            maxConnectionsPerHost = config.maxConnections.max(2),
            idleConnectionTimeout = config.idleTimeout,
            // Unsafe: isAlive is called from pool.poll (non-Kyo context, AllowUnsafe already in scope).
            // STEERING case 2: health-check callback invoked outside any fiber suspension.
            isAlive = conn =>
                try
                    if !Sync.Unsafe.evalOrThrow(conn.isOpen) then false
                    else
                        config.connectionTestQuery match
                            case Absent       => true
                            case Present(sql) =>
                                // Unsafe: blocks the pool callback thread to evaluate the test query.
                                // AllowUnsafe already in scope (pool.poll context). queryTimeout caps the wait.
                                KyoApp.Unsafe.runAndBlock(config.queryTimeout)(
                                    Abort.run[Throwable](conn.simpleExecute(sql)).map(_.isSuccess)
                                ).getOrElse(false)
                catch
                    // finalizer-context: stderr because we're in an AllowUnsafe pool callback
                    case t: Throwable =>
                        java.lang.System.err.println(s"[kyo-sql] SqlClientBackend.isAlive: unexpected error: ${t.getMessage}")
                        false,
            // Unsafe: discard is called from pool eviction path (non-Kyo context, AllowUnsafe in scope).
            // STEERING case 2: cleanup callback invoked outside any fiber suspension.
            // Log.live.unsafe.error routes through kyo.Log instead of raw stderr.
            discard = conn =>
                try discard(Sync.Unsafe.evalOrThrow(conn.close))
                catch
                    case t: Throwable =>
                        Log.live.unsafe.error(s"kyo.sql: PgSqlClientBackend.discard: error closing connection: ${t.getMessage}")
        )
        val slotChans = new ConcurrentHashMap[SqlAddress, Channel[Unit]]()
        val metrics   = SqlMetrics(config.metricsEnabled, config.metricsScope)
        new PgSqlClientBackend(pool, slotChans, frame, metrics)
    end initPg

    /** Creates a MySQL-backed [[SqlClientBackend]] wrapped by a fresh [[ConnectionPool[NetAddress, MysqlConnection]]].
      *
      * @param config
      *   pool parameters; `maxConnections` must be >= 2 (ConnectionPool ring-buffer constraint).
      * @param frame
      *   captured from the call site so the pool's isAlive/discard callbacks carry a real source location.
      */
    def initMy(config: SqlClientConfig, frame: Frame)(using AllowUnsafe): MySqlClientBackend =
        given capturedFrame: Frame = frame
        val pool = ConnectionPool.init[NetAddress, MysqlConnection](
            maxConnectionsPerHost = config.maxConnections.max(2),
            idleConnectionTimeout = config.idleTimeout,
            // Unsafe: isAlive is called from pool.poll (non-Kyo context, AllowUnsafe already in scope).
            // STEERING case 2: health-check callback invoked outside any fiber suspension.
            isAlive = conn =>
                try
                    if !Sync.Unsafe.evalOrThrow(conn.isOpen) then false
                    else
                        config.connectionTestQuery match
                            case Absent       => true
                            case Present(sql) =>
                                // Unsafe: blocks the pool callback thread to evaluate the test query.
                                // AllowUnsafe already in scope (pool.poll context). queryTimeout caps the wait.
                                KyoApp.Unsafe.runAndBlock(config.queryTimeout)(
                                    Abort.run[Throwable](conn.simpleExecute(sql)).map(_.isSuccess)
                                ).getOrElse(false)
                catch
                    // finalizer-context: stderr because we're in an AllowUnsafe pool callback
                    case t: Throwable =>
                        java.lang.System.err.println(s"[kyo-sql] SqlClientBackend.isAlive: unexpected error: ${t.getMessage}")
                        false,
            // Unsafe: discard is called from pool eviction path (non-Kyo context, AllowUnsafe in scope).
            // STEERING case 2: cleanup callback invoked outside any fiber suspension.
            // Note: conn.quit() has Async in its effect type and cannot be evaluated synchronously here.
            // We close the TCP socket directly via closeNow, which is sufficient, the server detects the EOF.
            // Log.live.unsafe.error routes through kyo.Log instead of raw stderr.
            discard = conn =>
                try discard(Sync.Unsafe.evalOrThrow(conn.closeNow))
                catch
                    case t: Throwable =>
                        Log.live.unsafe.error(s"kyo.sql: MySqlClientBackend.discard: error closing connection: ${t.getMessage}")
        )
        val slotChans = new ConcurrentHashMap[SqlAddress, Channel[Unit]]()
        val metrics   = SqlMetrics(config.metricsEnabled, config.metricsScope)
        new MySqlClientBackend(pool, slotChans, frame, metrics)
    end initMy

end SqlClientBackend
