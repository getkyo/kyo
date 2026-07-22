package kyo.internal.postgres

import kyo.*
import kyo.SqlClient.IsolationLevel
import kyo.SqlClient.Notification
import kyo.SqlConfig.Address
import kyo.SqlException
import kyo.SqlRow
import kyo.internal.client.TypeRegistry
import kyo.internal.postgres.exchange.*
import kyo.internal.postgres.types.EncodingRegistry
import kyo.internal.tls.TlsNegotiator
import kyo.net.Connection
import kyo.net.NetPlatform
import kyo.net.NetTlsConfig

/** An active PostgreSQL connection with per-connection state.
  *
  * Wraps a [[PostgresChannel]] (which wraps the underlying [[kyo.net.Connection]]) and holds:
  *   - [[parameters]], server parameters received during startup and via in-session `ParameterStatus` messages (e.g. `server_version`,
  *     `client_encoding`).
  *   - [[processId]] / [[secretKey]], from [[BackendKeyData]], used to issue a [[CancelRequest]] on a separate connection.
  *   - [[transactionStatus]], last-seen [[ReadyForQuery]] status byte (`'I'`/`'T'`/`'E'`).
  *   - [[notifications]], a bounded [[Channel]] into which async [[NotificationResponse]] messages are deposited.
  *   - [[preparedStmts]], per-connection LRU cache of server-side prepared statements, keyed by SQL hash.
  *
  * All public methods are safe (no [[AllowUnsafe]]). A single [[PostgresConnection]] must NOT be used concurrently, the caller is
  * responsible for ensuring serial access (the connection pool enforces this via acquire/release semantics).
  */
final class PostgresConnection(
    private[postgres] val channel: PostgresChannel,
    val parameters: AtomicRef[Map[String, String]],
    val processId: Int,
    val secretKey: Int,
    val transactionStatus: AtomicRef[Byte],
    val notifications: Channel[NotificationResponse],
    private[postgres] val preparedStmts: Cache[String, PreparedStmt],
    private[kyo] val pendingCloses: AtomicRef[Chunk[String]],
    private[kyo] val typeRegistryRef: AtomicRef[TypeRegistry],
    private[postgres] val encodingRegistry: EncodingRegistry,
    // Per-connection monotonic counter used to synthesise a UNIQUE server-side prepared statement
    // name on every `Parse`. Keying stmt names on `s_$hash` alone would collide with a still-live
    // server statement when the local cache evicts an entry whose `Close 'S'` is still pending in
    // the drain queue (or when the `expireAfterAccess` TTL expires locally while the server-side
    // entry is intact): the Parse for the "same SQL" re-uses `s_$hash`, hits an already-registered
    // name, and the server rejects it with SQLSTATE 42P05. Appending a monotonic suffix here makes
    // every Parse target a fresh name, so eviction-then-re-Parse never collides with an in-flight
    // Close.
    private[kyo] val stmtCounter: AtomicLong
):

    /** Sends `Close 'S' <name>` for each name accumulated in [[pendingCloses]] since the last drain, clearing the queue.
      *
      * Called at the start of every extended-protocol request so evicted server-side prepared statements are released before the next
      * round-trip. Each `Close` produces a `CloseComplete` response; a trailing `Sync` flushes all responses and produces a `ReadyForQuery`
      * which is consumed here so the connection is in a clean state for the subsequent exchange.
      */
    private[kyo] def drainPendingCloses(using Frame): Unit < (Async & Abort[SqlException]) =
        pendingCloses.getAndSet(Chunk.empty).flatMap { names =>
            if names.isEmpty then ()
            else
                Kyo.foreach(names) { name =>
                    channel.send(Close('S'.toByte, name))(using channel.marshallers.close)
                }.andThen(
                    channel.send(kyo.internal.postgres.Sync)(using channel.marshallers.sync)
                ).andThen(drainCloseResponses(names.size))
            end if
        }

    /** Reads [[CloseComplete]] messages followed by [[ReadyForQuery]] after a batch of `Close 'S'` + `Sync`.
      *
      * The server sends one `CloseComplete` per `Close` message sent, then a `ReadyForQuery` in response to the trailing `Sync`. Accepts
      * and re-dispatches `ParameterStatus` and `NotificationResponse` messages that may interleave.
      */
    private def drainCloseResponses(remaining: Int)(using Frame): Unit < (Async & Abort[SqlException]) =
        if remaining == 0 then
            // All CloseCompletes consumed; wait for the ReadyForQuery from the Sync.
            channel.receive.flatMap {
                case _: ReadyForQuery        => ()
                case ParameterStatus(n, v)   => updateParam(n, v).andThen(drainCloseResponses(0))
                case n: NotificationResponse => sendNotification(n).andThen(drainCloseResponses(0))
                case NoticeResponse(_)       => drainCloseResponses(0)
                case other => Abort.fail(SqlException.Connection(s"Unexpected message waiting for ReadyForQuery: $other", summon[Frame]))
            }
        else
            channel.receive.flatMap {
                case CloseComplete           => drainCloseResponses(remaining - 1)
                case _: ReadyForQuery        => ()
                case ParameterStatus(n, v)   => updateParam(n, v).andThen(drainCloseResponses(remaining))
                case n: NotificationResponse => sendNotification(n).andThen(drainCloseResponses(remaining))
                case NoticeResponse(_)       => drainCloseResponses(remaining)
                case other => Abort.fail(SqlException.Connection(s"Unexpected message during Close drain: $other", summon[Frame]))
            }
        end if
    end drainCloseResponses

    /** Executes `sql` using the simple-query protocol and returns all rows.
      *
      * Uses [[BarrierGuard]] to drain to [[ReadyForQuery]] even on error, ensuring the connection stays in a clean state for subsequent
      * calls.
      *
      * Note: [[SimpleQueryExchange.run]] already reads ReadyForQuery internally (it continues until RFQ), so we do NOT wrap it with
      * BarrierGuard here, the exchange itself acts as the barrier.
      */
    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        SimpleQueryExchange.run(channel, sql, processId.toLong, updateParam, sendNotification).map { case (rows, _) => rows }

    /** Executes `sql` using the simple-query protocol and returns the number of affected rows.
      *
      * For SELECT statements, returns the row count. For DML (INSERT/UPDATE/DELETE), returns affected rows.
      */
    def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        SimpleQueryExchange.run(channel, sql, processId.toLong, updateParam, sendNotification).map { case (_, count) => count }

    /** Sends a minimal empty simple-query (`;`) and waits for the server's [[ReadyForQuery]].
      *
      * Postgres has no dedicated PING command; an empty simple-query is the cheapest round-trip, the server replies with
      * [[EmptyQueryResponse]] followed by [[ReadyForQuery]], confirming the wire is live without parsing or planning any SQL.
      */
    def ping(using frame: Frame): Unit < (Async & Abort[SqlException]) =
        simpleExecute(";").unit

    /** Executes a parameterised query using the extended protocol and returns all rows.
      *
      * Uses the per-connection prepared-statement cache: if the SQL has been parsed before, skips the Parse/Describe round trip and goes
      * directly to Bind/Execute. Binary format is requested for result columns where a registered decoder exists.
      *
      * @param sql
      *   parameterised SQL text with `$1`, `$2`, ... placeholders
      * @param params
      *   parameter values, one per placeholder, in order
      */
    def extendedQuery(sql: String, params: Chunk[BoundParam[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        drainPendingCloses.andThen(ExtendedQueryExchange.query(
            channel,
            preparedStmts,
            stmtCounter,
            sql,
            params,
            encodingRegistry,
            processId.toLong,
            updateParam,
            sendNotification
        ))

    /** Streams rows from a parameterised query using the Postgres portal protocol.
      *
      * Uses the per-connection prepared-statement cache (shared with [[extendedQuery]]). Binds a named portal and repeatedly calls
      * `Execute(batchSize)` until `CommandComplete`, emitting one [[kyo.Chunk]] of [[kyo.SqlRow]] per `Execute` response.
      *
      * The portal is guaranteed to be closed (and the connection left in `ReadyForQuery` state) on every exit path: normal completion,
      * [[SqlException]] abort, or fiber interruption.
      *
      * @param sql
      *   parameterised SQL text with `$1`, `$2`, ... placeholders
      * @param params
      *   parameter values, one per placeholder, in order
      * @param batchSize
      *   rows per `Execute` call (default 64); must be > 0
      */
    def streamQuery(
        sql: String,
        params: Chunk[BoundParam[?]],
        batchSize: Int
    )(using Frame): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream:
            drainPendingCloses.andThen(
                StreamQueryExchange.stream(
                    channel,
                    preparedStmts,
                    stmtCounter,
                    sql,
                    params,
                    batchSize,
                    processId.toLong,
                    updateParam,
                    sendNotification
                ).emit
            )

    /** Executes a parameterised DML statement using the extended protocol and returns the number of affected rows.
      *
      * @param sql
      *   parameterised SQL text with `$1`, `$2`, ... placeholders
      * @param params
      *   parameter values, one per placeholder, in order
      */
    def extendedExecute(sql: String, params: Chunk[BoundParam[?]])(using Frame): Long < (Async & Abort[SqlException]) =
        drainPendingCloses.andThen(ExtendedQueryExchange.execute(
            channel,
            preparedStmts,
            stmtCounter,
            sql,
            params,
            encodingRegistry,
            processId.toLong,
            updateParam,
            sendNotification
        ))

    /** Runs an extended INSERT and returns an [[SqlClient.InsertOutcome]].
      *
      * When `sql` ends with a `RETURNING` clause (auto-emitted by `SqlRender` for tables with an auto-key column), this issues a query and
      * decodes the single-column response as a `Long`, the generated key. The `affectedRows` count equals the number of rows returned.
      * When `sql` carries no `RETURNING` (target table has no auto-key column), this falls through to `extendedExecute` and reports
      * `SqlClient.InsertOutcome(affected, SqlClient.InsertOutcome.GeneratedKey.NoAutoKey)`.
      *
      * Multi-row INSERTs with `RETURNING <pk>` yield multiple DataRows; this method retains the LAST decoded key as the `generatedKey`. See
      * `SqlClient.InsertOutcome` scaladoc.
      */
    def extendedExecuteInsert(sql: String, params: Chunk[BoundParam[?]])(using
        Frame
    ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) =
        if sqlHasReturning(sql) then
            extendedQuery(sql, params).map { rows =>
                if rows.isEmpty then SqlClient.InsertOutcome(0L, SqlClient.InsertOutcome.GeneratedKey.NoAutoKey)
                else
                    val last = rows.last
                    val key = last.column(0) match
                        case Maybe.Present(_) => SqlClient.InsertOutcome.GeneratedKey.Value(decodeFirstColumnAsLong(last))
                        case Maybe.Absent     => SqlClient.InsertOutcome.GeneratedKey.Unavailable
                    SqlClient.InsertOutcome(rows.size.toLong, key)
            }
        else
            extendedExecute(sql, params).map(affected => SqlClient.InsertOutcome(affected, SqlClient.InsertOutcome.GeneratedKey.NoAutoKey))

    private def sqlHasReturning(sql: String): Boolean =
        // Trailing-whitespace-tolerant detection of the auto-emitted RETURNING clause.
        // The renderer always produces ` RETURNING ...` (single space before keyword); the broader
        // `.contains` check would also match user-written sql"..." fragments with embedded RETURNING.
        sql.toUpperCase.contains(" RETURNING ")

    private def decodeFirstColumnAsLong(row: SqlRow)(using Frame): Long =
        val reader = PostgresRowReader(row)
        reader.long()

    /** Executes `COPY ... FROM STDIN` and streams `data` to the server.
      *
      * Sends `sql` as a simple Query, awaits [[CopyInResponse]], pumps all [[CopyData]] packets from `data`, sends [[CopyDone]], and reads
      * [[CommandComplete]] + [[ReadyForQuery]]. Returns the affected-row count from the server's "COPY N" command tag.
      *
      * @param sql
      *   a `COPY ... FROM STDIN` statement
      * @param data
      *   byte stream to send; each element becomes one or more [[CopyData]] packets
      */
    def copyIn[S](sql: String, data: Stream[Span[Byte], S], cleanupTimeout: Duration)(using
        Frame
    ): Long < (Async & Abort[SqlException] & S) =
        Scope.run(CopyExchange.copyIn(channel, sql, processId.toLong, data, cleanupTimeout))

    /** Executes `COPY ... TO STDOUT` and returns a lazy stream of raw data chunks.
      *
      * @param sql
      *   a `COPY ... TO STDOUT` statement
      * @param cleanupTimeout
      *   maximum time for the uninterruptible CopyFail + ReadyForQuery drain on early cancellation
      * @return
      *   a [[Stream]] of [[Span[Byte]]] COPY data payloads; the stream ends when the server sends [[CopyDone]]
      */
    def copyOut(sql: String, cleanupTimeout: Duration)(using Frame): Stream[Span[Byte], Async & Abort[SqlException] & Scope] =
        CopyExchange.copyOut(channel, sql, processId.toLong, cleanupTimeout)

    /** Executes multiple statements in pipeline mode.
      *
      * Sends all `Bind`/`Execute`/`Sync` triples in one TCP write and reads all responses in order. Returns one pipeline result per
      * statement. Per-statement errors are recorded in-place; subsequent statements still execute.
      *
      * @param stmts
      *   `(sql, params)` pairs; each SQL is resolved via the prepared-statement cache
      */
    def pipelined(
        stmts: Chunk[(String, Chunk[BoundParam[?]])]
    )(using Frame): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        drainPendingCloses.andThen(PipelineExchange.prepare(
            channel,
            preparedStmts,
            stmtCounter,
            stmts,
            encodingRegistry,
            processId.toLong,
            updateParam,
            sendNotification
        ))

    // --- Transaction control ---

    /** Sends `BEGIN` (optionally with an isolation level and/or `READ ONLY`). */
    def beginTransaction(
        isolation: Maybe[SqlClient.IsolationLevel],
        readOnly: Boolean
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        TransactionExchange.begin(channel, processId.toLong, isolation, readOnly)

    /** Sends `COMMIT`. */
    def commitTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        TransactionExchange.commit(channel, processId.toLong)

    /** Sends `ROLLBACK`. */
    def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        TransactionExchange.rollback(channel, processId.toLong)

    /** Sends `SAVEPOINT <name>`. */
    def savepointTransaction(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        TransactionExchange.savepoint(channel, name, processId.toLong)

    /** Sends `RELEASE SAVEPOINT <name>`. */
    def releaseSavepointTransaction(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        TransactionExchange.releaseSavepoint(channel, name, processId.toLong)

    /** Sends `ROLLBACK TO SAVEPOINT <name>`. */
    def rollbackToSavepointTransaction(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        TransactionExchange.rollbackToSavepoint(channel, name, processId.toLong)

    // --- Message pump ---

    /** Reads the next [[BackendMessage]] from the underlying TCP connection.
      *
      * Intended for use by the notification pump in [[kyo.internal.client.SqlClientBackend]]: a dedicated listener connection has no active
      * exchange, so the caller must drive the read loop manually. Other callers must NOT call this while an exchange is in progress,
      * concurrent reads from the same connection violate the protocol framing.
      */
    def receive(using Frame): BackendMessage < (Async & Abort[SqlException]) =
        channel.receive

    // --- Cancel ---

    /** Sends a [[CancelRequest]] on a fresh TCP connection to the same server.
      *
      * Opens a brand-new connection (does NOT acquire from the pool, that would risk deadlocking on the very connection being cancelled).
      * The server matches by `(processId, secretKey)` and cancels the running query, causing it to fail with SQLSTATE `57014`.
      *
      * @param address
      *   the same [[SqlConfig.Address]] used by this connection
      * @param tls
      *   optional TLS config (cancel will attempt TLS if [[Present]]; falls back to plaintext on refusal)
      */
    def cancel(address: SqlConfig.Address, tls: Maybe[NetTlsConfig])(using Frame): Unit < (Async & Abort[SqlException]) =
        CancelExchange.cancel(address, tls, processId, secretKey)

    /** Drains all pending [[NotificationResponse]] messages from the notification channel and converts them to public [[SqlClient.Notification]]
      * values.
      *
      * Non-blocking: returns only notifications already buffered; does NOT wait for new ones.
      */
    def drainNotifications(using Frame): Chunk[SqlClient.Notification] < Sync =
        def loop(acc: Chunk[SqlClient.Notification]): Chunk[SqlClient.Notification] < Sync =
            Abort.run[Closed](notifications.poll).flatMap {
                case Result.Success(Present(n)) =>
                    loop(acc.appended(SqlClient.Notification(n.channel, n.payload, n.processId)))
                case _ => acc
            }
        loop(Chunk.empty)
    end drainNotifications

    // --- Lifecycle ---

    /** Sends the [[Terminate]] message and closes the connection. Idempotent. */
    def terminate(using Frame): Unit < (Async & Abort[SqlException]) =
        TerminatorExchange.run(channel)

    /** Returns the SHA-256 hash of the server's TLS leaf certificate (RFC 5929 tls-server-end-point), or Absent if the connection is not
      * TLS.
      *
      * Used by SCRAM-SHA-256-PLUS tests to verify that channel binding was used. Exposed via `private[kyo]` so integration tests in
      * `kyo.internal.postgres` or `kyo.sql` packages can read it without going through `channel.conn` directly.
      */
    private[kyo] def serverCertificateHash(using Frame): Maybe[Span[Byte]] < Sync =
        channel.conn.serverCertificateHash

    /** Returns true if the underlying [[Connection]] is still open. */
    def isOpen(using Frame): Boolean < Sync =
        Sync.Unsafe.defer(channel.conn.isOpen)

    /** Closes the underlying connection without sending [[Terminate]]. Prefer [[terminate]] for graceful shutdown. */
    def close(using Frame): Unit < Sync =
        Sync.Unsafe.defer(channel.conn.close())

    private def updateParam(name: String, value: String)(using Frame): Unit < Async =
        parameters.getAndUpdate(_ + (name -> value)).unit

    private def sendNotification(n: NotificationResponse)(using Frame): Unit < Async =
        // If the notification channel is closed, silently discard the notification.
        Abort.run[Closed](notifications.offerDiscard(n)).unit

end PostgresConnection

object PostgresConnection:

    // --- Internal helpers ---

    private[kyo] def onConnectPanic(t: Throwable, label: String)(using Frame): SqlException.Connection < Sync =
        Log.error(s"[kyo-sql] PostgresConnection.$label: panic: ${t.getMessage}").andThen(
            SqlException.Connection(s"Failed to connect: ${t.getMessage}", summon[Frame])
        )

    /** Builds the per-connection prepared-statement cache with eviction wired into `pendingCloses`.
      *
      * A plain `Cache.init` silently drops evicted `PreparedStmt` values; the server-side statement stays allocated for the life of the
      * session (a bounded but real leak on any connection that churns through more distinct SQL than the cache holds). Wiring `onEvict`,
      * `onExpire`, and `onRemove` into `closesRef` enqueues the evicted PG statement name so the next `drainPendingCloses` call flushes
      * `Close 'S'` for the released statement.
      *
      * The callback is `(K, V) => Unit`, synchronous and invoked inline on the cache's Sync sweep. It uses the AtomicRef's non-suspending
      * `unsafe.updateAndGet`; the actual `Close 'S'` write happens on the next extended-protocol request (which starts by calling
      * `drainPendingCloses`). Mirrors [[kyo.internal.mysql.MysqlConnection.mkStmtCache]].
      */
    private[postgres] def mkStmtCache(
        closesRef: AtomicRef[Chunk[String]],
        maxSize: Int,
        ttl: Duration
    )(using Frame): Cache[String, PreparedStmt] < Sync =
        Sync.Unsafe.defer:
            val onEvict: (String, PreparedStmt) => Unit = (_, stmt) =>
                discard(closesRef.unsafe.updateAndGet(_ :+ stmt.name))
            Cache.Unsafe.init[String, PreparedStmt](
                maxSize = maxSize,
                expireAfterAccess = ttl,
                onEvict = onEvict,
                onExpire = onEvict,
                onRemove = onEvict
            ).safe

    // --- Connection factories ---

    /** Establishes a Postgres connection to `address` using plaintext or TLS authentication.
      *
      * Sequence:
      *   1. Connect via [[NetPlatform.transport]].
      *   2. If `tls` is [[Present]]: send SSLRequest via [[InitSSLExchange]]; upgrade the connection to TLS on 'S'; fail on 'N'.
      *   3. Run [[StartupExchange]] over the (optionally TLS-wrapped) connection.
      *   4. Initialise the per-connection prepared-statement cache.
      *   5. Return a [[PostgresConnection]] populated from the startup result.
      *
      * The returned connection is NOT scope-managed here; the caller (pool or [[SqlClient]]) is responsible for calling [[terminate]] /
      * [[close]].
      *
      * @param host
      *   hostname or IP address
      * @param port
      *   TCP port (default Postgres: 5432)
      * @param user
      *   database user
      * @param db
      *   database name
      * @param password
      *   optional password (Absent for trust auth)
      * @param tls
      *   optional TLS configuration; [[Absent]] = plaintext, [[Present]] = TLS required
      * @param preparedStmtCacheSize
      *   maximum number of prepared statements to cache per connection (default 64)
      * @param preparedStmtTtl
      *   TTL for cached prepared statements (default Infinity = bounded only by size)
      */
    def connect(
        host: String,
        port: Int,
        user: String,
        db: String,
        password: Maybe[String],
        tls: Maybe[NetTlsConfig],
        preparedStmtCacheSize: Int,
        preparedStmtTtl: Duration,
        encodingRegistry: EncodingRegistry
    )(using Frame): PostgresConnection < (Async & Abort[SqlException]) =
        Abort.run[kyo.net.NetException](Sync.Unsafe.defer(NetPlatform.transport.connect(
            host,
            port
        ).safe).flatMap(_.use(identity))).flatMap {
            case Result.Failure(_) =>
                Abort.fail(SqlException.Connection(s"Failed to connect to $host:$port", summon[Frame]))
            case Result.Panic(t) =>
                onConnectPanic(t, "connect").flatMap(Abort.fail(_))
            case Result.Success(rawConn) =>
                // If TLS is requested, perform the SSLRequest dance and upgrade; otherwise use the raw connection.
                val connEffect: Connection < (Async & Abort[SqlException]) = tls match
                    case Absent             => rawConn
                    case Present(tlsConfig) => InitSSLExchange.run(rawConn, host, port, tlsConfig)
                connEffect.flatMap { conn =>
                    PostgresChannel(conn).flatMap { channel =>
                        StartupExchange.run(channel, user, db, password, Absent, Absent).flatMap { result =>
                            // Duration.Infinity means "no time-based expiry"; pass Duration.Zero to Cache.init.
                            val ttl = if preparedStmtTtl == Duration.Infinity then Duration.Zero else preparedStmtTtl
                            mkConnection(channel, result, preparedStmtCacheSize, ttl, encodingRegistry)
                        }
                    }
                }
        }
    end connect

    /** Like [[connect]] but with an optional [[TlsNegotiator]] for opportunistic TLS (sslmode=prefer/allow).
      *
      * When `negotiator` is [[Present]], it is called with the raw plaintext [[Connection]] (before startup) and may either upgrade it to
      * TLS or return it unchanged. The [[StartupExchange]] runs on the result.
      *
      * This method exists so [[kyo.internal.client.PostgresSqlClientBackend]] can wire opportunistic TLS without changing the public [[connect]]
      * signature.
      *
      * @param host
      *   hostname or IP address
      * @param port
      *   TCP port
      * @param user
      *   database user
      * @param db
      *   database name
      * @param password
      *   optional password
      * @param tls
      *   TLS configuration passed to [[connect]] for strict modes; for negotiated modes this may be [[Absent]]
      * @param negotiator
      *   optional opportunistic TLS negotiator; if [[Present]], it pre-processes the raw connection before startup
      * @param preparedStmtCacheSize
      *   prepared-statement cache capacity
      * @param preparedStmtTtl
      *   prepared-statement cache TTL
      */
    private[internal] def connectWithNegotiator(
        host: String,
        port: Int,
        user: String,
        db: String,
        password: Maybe[String],
        tls: Maybe[NetTlsConfig],
        negotiator: Maybe[TlsNegotiator],
        preparedStmtCacheSize: Int,
        preparedStmtTtl: Duration,
        encodingRegistry: EncodingRegistry
    )(using Frame): PostgresConnection < (Async & Abort[SqlException]) =
        Abort.run[kyo.net.NetException](Sync.Unsafe.defer(NetPlatform.transport.connect(
            host,
            port
        ).safe).flatMap(_.use(identity))).flatMap {
            case Result.Failure(_) =>
                Abort.fail(SqlException.Connection(s"Failed to connect to $host:$port", summon[Frame]))
            case Result.Panic(t) =>
                onConnectPanic(t, "connectWithNegotiator").flatMap(Abort.fail(_))
            case Result.Success(rawConn) =>
                // Step 1: apply negotiator (prefer/allow) or strict TLS upgrade (require/verify-*).
                val connEffect: Connection < (Async & Abort[SqlException]) = negotiator match
                    case Present(neg) =>
                        // Opportunistic: the negotiator decides whether to upgrade.
                        neg.negotiate(rawConn)
                    case Absent =>
                        // Strict: TLS required or plaintext.
                        tls match
                            case Absent             => rawConn
                            case Present(tlsConfig) => InitSSLExchange.run(rawConn, host, port, tlsConfig)
                connEffect.flatMap { conn =>
                    PostgresChannel(conn).flatMap { channel =>
                        StartupExchange.run(channel, user, db, password, Absent, Absent).flatMap { result =>
                            val ttl = if preparedStmtTtl == Duration.Infinity then Duration.Zero else preparedStmtTtl
                            mkConnection(channel, result, preparedStmtCacheSize, ttl, encodingRegistry)
                        }
                    }
                }
        }
    end connectWithNegotiator

    /** Like [[connect]] but injects `certHashOverride` into the SCRAM startup, bypassing the real TLS cert hash.
      *
      * `certHashOverride` semantics:
      *   - `Absent`, no override; use the real TLS cert hash from the connection (same as [[connect]]).
      *   - `Present(Absent)`, force Absent: client refuses PLUS even when TLS is active and cert is known.
      *   - `Present(Present(hash))`, inject `hash`: client uses PLUS with this fabricated hash (MITM simulation).
      *
      * `mechanismCapture`, when `Present(ref)`, the selected SASL mechanism name ("SCRAM-SHA-256" or "SCRAM-SHA-256-PLUS") is stored in
      * `ref` before the `SASLInitialResponse` is sent. Used by tests to verify the on-wire mechanism selection.
      *
      * Used by SCRAM-SHA-256-PLUS integration tests. Not exposed publicly.
      */
    private[kyo] def connectWithCertHashOverride(
        host: String,
        port: Int,
        user: String,
        db: String,
        password: Maybe[String],
        tls: Maybe[NetTlsConfig],
        certHashOverride: Maybe[Maybe[Span[Byte]]],
        mechanismCapture: Maybe[AtomicRef[String]],
        preparedStmtCacheSize: Int
    )(using Frame): PostgresConnection < (Async & Abort[SqlException]) =
        Abort.run[kyo.net.NetException](Sync.Unsafe.defer(NetPlatform.transport.connect(
            host,
            port
        ).safe).flatMap(_.use(identity))).flatMap {
            case Result.Failure(_) =>
                Abort.fail(SqlException.Connection(s"Failed to connect to $host:$port", summon[Frame]))
            case Result.Panic(t) =>
                onConnectPanic(t, "connectWithCertHashOverride").flatMap(Abort.fail(_))
            case Result.Success(rawConn) =>
                val connEffect: Connection < (Async & Abort[SqlException]) = tls match
                    case Absent             => rawConn
                    case Present(tlsConfig) => InitSSLExchange.run(rawConn, host, port, tlsConfig)
                connEffect.flatMap { conn =>
                    PostgresChannel(conn).flatMap { channel =>
                        StartupExchange.run(channel, user, db, password, certHashOverride, mechanismCapture).flatMap { result =>
                            // connectWithCertHashOverride is test-only; no TTL parameter, use Duration.Zero directly.
                            mkConnection(channel, result, preparedStmtCacheSize, Duration.Zero, EncodingRegistry.builtin)
                        }
                    }
                }
        }
    end connectWithCertHashOverride

    /** Initialises per-connection mutable state and wraps it in a [[PostgresConnection]].
      *
      * Extracted from the three `connect*` factories to eliminate duplication of the five-atom init + Cache.init block. Every factory
      * produces identical wiring; the only varying inputs are the already-established `channel`, the `result` from [[StartupExchange]], the
      * prepared-statement cache capacity, and the pre-computed `ttl` (callers normalise [[Duration.Infinity]] → [[Duration.Zero]] before
      * calling here).
      */
    private def mkConnection(
        channel: PostgresChannel,
        result: StartupResult,
        preparedStmtCacheSize: Int,
        ttl: Duration,
        registry: EncodingRegistry
    )(using Frame): PostgresConnection < (Async & Abort[SqlException]) =
        for
            params      <- AtomicRef.init(result.parameters)
            txStatus    <- AtomicRef.init('I'.toByte)
            notifChan   <- Channel.initUnscoped[NotificationResponse](128)
            closesRef   <- AtomicRef.init(Chunk.empty[String])
            typeRegRef  <- AtomicRef.init[TypeRegistry](TypeRegistry.empty)
            stmtCounter <- AtomicLong.init(0L)
            stmtCache   <- PostgresConnection.mkStmtCache(closesRef, preparedStmtCacheSize, ttl)
        yield new PostgresConnection(
            channel,
            params,
            result.processId,
            result.secretKey,
            txStatus,
            notifChan,
            stmtCache,
            closesRef,
            typeRegRef,
            registry,
            stmtCounter
        )
        end for
    end mkConnection

end PostgresConnection
