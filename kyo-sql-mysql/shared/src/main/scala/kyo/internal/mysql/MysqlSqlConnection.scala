package kyo.internal.mysql

import kyo.*
import kyo.Sql.BoundValue
import kyo.SqlConfig.TlsMode
import kyo.db.Connection
import kyo.db.Idiom

/** [[kyo.db.Connection]] over a [[MysqlConnection]].
  *
  * Binds arrive as [[kyo.Sql.BoundValue]] and leave as [[BoundMysqlParam]] through [[MysqlParamWriter]]; rows arrive as [[MysqlRow]] and leave
  * as [[kyo.SqlRow]] through [[MysqlRowCodec]]. Neither wire type appears above this class. The in-flight window the pool's reclaim reads is
  * maintained here, around each delegated call.
  *
  * ==Reclaim on MySQL, and why the drain answers no==
  *
  * MySQL has no out-of-band cancel packet. Stopping a statement means opening a second authenticated connection and running
  * `KILL QUERY <connectionId>` on it, which [[cancelInFlight]] does with a sidecar it closes rather than pools: the sidecar exists for one
  * statement and keeping it would hold a connection open for every cancel that ever fired.
  *
  * What the kill leaves behind cannot be read past. A binary result row begins with `0x00`, the same first byte an `OK` packet has, so a
  * reader that has lost its place in a result set cannot tell one from the other, and there is no `ReadyForQuery` equivalent to resynchronise
  * against. So [[drainToIdle]] reports a session reusable in exactly two cases: nothing was in flight, so the wire was never disturbed (an
  * interrupt that landed between statements inside a transaction, where the rollback is all that is owed); or an interrupted STREAM whose
  * cleanup drained to the result-set terminator and recorded the clean boundary (see [[streamQuery]]). The reader that "lost its place" is
  * the one an interrupt cut mid-statement with nobody draining afterwards; that session carries a raised flag and no record, and the
  * connection is destroyed.
  */
final private[kyo] class MysqlSqlConnection private[mysql] (
    private[kyo] val underlying: MysqlConnection,
    private val connectionId: Long,
    private val address: SqlConfig.Address,
    private val password: Maybe[String],
    private val config: SqlConfig,
    private val options: SqlConfig.Url.Options,
    // Unsafe: these three AtomicBoolean.Unsafe flags are read by the pool's non-suspending eviction and
    // health callbacks (see kyo.db.Connection), so every flip below goes through Sync.Unsafe.defer.
    private val requestInFlight: AtomicBoolean.Unsafe,
    private val transactionOpen: AtomicBoolean.Unsafe,
    private val streamDrainedClean: AtomicBoolean.Unsafe
) extends Connection:

    def id: Long = connectionId

    /** Reports this session's own handshake version, read from the version string it captured from its `HandshakeV10` packet. */
    def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException]) =
        underlying.serverVersion.get.flatMap(Connection.parseServerVersion(_))

    // --- Statements ---

    def extendedQuery(sql: String, params: Chunk[BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        tracked(underlying.extendedQuery(sql, native(params)).map(_.map(MysqlRowCodec.row)))

    def extendedExecute(sql: String, params: Chunk[BoundValue[?]])(using Frame): Long < (Async & Abort[SqlException]) =
        tracked(underlying.extendedExecute(sql, native(params)))

    def extendedExecuteInsert(sql: String, params: Chunk[BoundValue[?]])(using
        Frame
    ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) =
        tracked(underlying.extendedExecuteInsert(sql, native(params)))

    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        tracked(underlying.simpleQuery(sql).map(_.map(MysqlRowCodec.row)))

    def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        tracked(underlying.simpleExecute(sql))

    /** Streams rows, supplying the cleanup drain with a way to stop the server rather than only to outlast it, and with the record that
      * makes its success visible to the pool.
      *
      * `cancelInFlight` is the escalation, and it is the same one the pool's reclaim uses, so a killed stream and a killed statement stop the
      * server by the identical path. It is safe to hand over here for the reason the drain needs: its own guard reads `requestInFlight`, which
      * [[trackedScoped]] raises before the body and lowers in a `Scope.ensure` registered BEFORE the stream registers its cleanup. Finalizers
      * run last-registered-first, so the drain runs while the flag is still raised and the escalation can see there is something to kill.
      *
      * The clean-boundary record (`streamDrainedClean`) is set at the successful END of the drain, and it is what distinguishes an
      * interrupted stream from an interrupted statement. An interrupt's exit signal cannot say whether the wire is clean
      * ([[kyo.db.Connection.settle]] keys on the error shape and leaves `requestInFlight` raised for a panic), so this record is the only
      * evidence that the wire ended on a terminator, and an interrupted stream without one is indistinguishable from a dirty session. The
      * quarantine chain the raised flag routes into reads it: [[cancelInFlight]] skips the sidecar kill (the statement already ended) and
      * [[drainToIdle]] answers reusable, so the session is pooled rather than destroyed. The record stays unset when the drain itself
      * failed, which is the case that deserves the destroy.
      */
    def streamQuery(sql: String, params: Chunk[BoundValue[?]], batchSize: Int)(using
        Frame
    ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            trackedScoped(
                underlying.streamQuery(
                    sql,
                    native(params),
                    batchSize,
                    cancelInFlight,
                    Sync.Unsafe.defer(streamDrainedClean.set(true))
                ).mapPure(MysqlRowCodec.row).emit
            )
        )

    def pipelined(stmts: Chunk[(String, Chunk[BoundValue[?]])])(using
        Frame
    ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        tracked(underlying.pipelined(stmts.map { case (sql, params) => (sql, native(params)) }))

    // --- Transactions ---

    def beginTransaction(isolation: Maybe[SqlClient.IsolationLevel], readOnly: Boolean)(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.beginTransaction(isolation, readOnly))
            .andThen(Sync.Unsafe.defer(transactionOpen.set(true)))

    def commitTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.commitTransaction).andThen(Sync.Unsafe.defer(transactionOpen.set(false)))

    def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.rollbackTransaction).andThen(Sync.Unsafe.defer(transactionOpen.set(false)))

    def savepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.savepointTransaction(name))

    def releaseSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.releaseSavepointTransaction(name))

    def rollbackToSavepoint(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.rollbackToSavepointTransaction(name))

    // --- Session ---

    def ping(using Frame): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.ping())

    def resetSession(using Frame): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.resetConnection())

    def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException]) =
        // MySQL's locks are named rather than numeric, and the name is `key.toString`. Not a formatting
        // choice: two deployments running different versions of this library have to agree on the name or
        // they take different locks and both proceed, so the mapping is pinned.
        val name = key.toString
        // GET_LOCK's own encoding for "wait forever" is a negative timeout, which is what Absent means here.
        //
        // A present sub-second wait rounds UP to one second rather than truncating to zero. GET_LOCK takes whole
        // seconds, and `0` is not a short wait, it is try-once-and-fail: truncating would turn `500.millis` into
        // immediate-failure semantics, a different operation from the bounded wait the caller asked for.
        // Rounding up overshoots by under a second and keeps the operation the one that was requested.
        val timeoutSeconds = timeout.fold(-1L)(d => Math.max(1L, d.toSeconds))
        // A simple (text) query on this session: GET_LOCK's BIGINT result arrives as the ASCII "1", "0", or
        // NULL, and the matching RELEASE_LOCK has to reach the same session, which is why the caller pins one.
        tracked(underlying.simpleQuery(s"SELECT GET_LOCK('$name', $timeoutSeconds)")).flatMap { rows =>
            val acquired =
                rows.headMaybe match
                    case Present(row) =>
                        row.column(0) match
                            case Present(bytes) =>
                                new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8) == "1"
                            case Absent => false
                    case Absent => false
            if acquired then () else Abort.fail(SqlRequestAdvisoryLockException(key, timeout))
        }
    end acquireAdvisoryLock

    def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.simpleExecute(s"SELECT RELEASE_LOCK('${key.toString}')").unit)

    // --- Reclaim ---

    // Unsafe: answered synchronously for the pool's non-suspending callbacks, per the kyo.db.Connection contract.
    def inFlight(using AllowUnsafe): Boolean = requestInFlight.get()

    def inOpenTransaction(using AllowUnsafe): Boolean = transactionOpen.get()

    def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(requestInFlight.get() && !streamDrainedClean.get()).flatMap {
            // Nothing is running: either no request was in flight, or a stream's cleanup already drained to the
            // terminator (killing the statement itself if that was faster). No sidecar is worth opening for either.
            case false => ()
            case true =>
                MysqlSqlConnection.connect(address, password, config, options).flatMap { sidecar =>
                    closingOnce(sidecar) {
                        underlying.cancelQuery(sidecar).andThen {
                            // COM_QUIT is a courtesy: the server notices the socket close either way, so a
                            // failure here must not turn a delivered kill into a failed cancel.
                            Abort.run[SqlException](sidecar.quit()).unit
                        }
                    }
                }
        }

    def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(transactionOpen.get()).flatMap {
            case false => ()
            case true  => rollbackTransaction
        }

    def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer {
            if !requestInFlight.get() then
                // A stream that completed normally lowers the flag in its settle while the clean-boundary record
                // is still raised. Clearing the record here keeps "the record never describes an older request
                // than the flag" a local invariant rather than a whole-file argument over raise order.
                streamDrainedClean.set(false)
                Maybe(true)
            else if streamDrainedClean.get() then
                // An interrupted stream's cleanup drained to the result-set terminator and recorded it (see
                // [[streamQuery]]). The wire is on a clean boundary, so the in-flight window closes here, where
                // the reclaim consumes the record.
                streamDrainedClean.set(false)
                requestInFlight.set(false)
                Maybe(true)
            else Maybe.empty
        }.flatMap {
            case Present(_) =>
                // The wire is on a clean boundary (never disturbed, or drained to a terminator), so the session
                // is usable if its transport is.
                underlying.isOpen
            case Absent =>
                // See the class scaladoc: a killed statement's unread response cannot be read past on this
                // protocol, so the only safe answer is that the connection has to go.
                false
        }

    // --- Lifecycle ---

    def isOpen(using Frame): Boolean < Sync = underlying.isOpen

    def close(using Frame): Unit < Async = Abort.run[SqlException](underlying.close).unit

    // Unsafe: closes without suspending, for callers that cannot park (pool discard callbacks).
    def closeNow(using Frame, AllowUnsafe): Unit = Sync.Unsafe.evalOrThrow(underlying.closeNow)

    // --- Internals ---

    /** Runs `body` and closes `sidecar` exactly once, on whichever edge ends it.
      *
      * Two paths, because neither reaches every edge. `Sync.ensure` fires on an interrupt or a panic, including the interrupt the enclosing
      * cancel budget delivers when it expires; it does NOT fire on a typed `Abort` whose handler sits outside it, because the failure is a
      * suspension that skips the continuation. A `KILL QUERY` the server refuses is exactly such a failure, so trusting the finalizer alone
      * would leak the sidecar's socket on every refused cancel. The typed and successful edges are therefore resolved here by handling the
      * abort and re-raising it, and a flag makes the close idempotent so both paths firing costs nothing.
      *
      * The same shape as [[kyo.internal.client.SqlConnectionPool]]'s lease resolution, for the same reason.
      */
    private def closingOnce(sidecar: MysqlConnection)(body: Unit < (Async & Abort[SqlException]))(using
        Frame
    ): Unit < (Async & Abort[SqlException]) =
        // Unsafe: a plain flag, read and written only by the two resolution paths below.
        Sync.Unsafe.defer(AtomicBoolean.Unsafe.init(false)).flatMap { closed =>
            def once(using Frame): Unit < Sync =
                Sync.Unsafe.defer(closed.compareAndSet(false, true)).flatMap {
                    case true  => sidecar.closeNow
                    case false => ()
                }
            Sync.ensure(once) {
                Abort.run[SqlException](body).flatMap { outcome =>
                    once.andThen {
                        outcome match
                            case Result.Success(_) => ()
                            case Result.Failure(e) => Abort.fail[SqlException](e)
                            case Result.Panic(t)   => Abort.error(Result.Panic(t))
                    }
                }
            }
        }

    private def native(params: Chunk[BoundValue[?]])(using Frame): Chunk[BoundMysqlParam[?]] =
        params.flatMap {
            case b: BoundValue[a] => MysqlParamWriter.write(b.schema, b.value)
        }

    /** Marks a request in flight for the duration of `body`. Raises the in-flight flag here; the shared settle-on-exit is
      * [[kyo.db.Connection.tracked]].
      */
    private def tracked[A](body: A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(raiseInFlight()).andThen(Connection.tracked(requestInFlight)(body))

    /** Opens the in-flight window, clearing any previous stream's clean-boundary record so the record can never describe an older
      * request than the flag does: both are read together by the reclaim, and a stale record would pool a wire this new request dirtied.
      */
    private def raiseInFlight()(using AllowUnsafe): Unit =
        streamDrainedClean.set(false)
        requestInFlight.set(true)

    /** [[tracked]] for a stream, whose request stays in flight across every batch until the enclosing [[Scope]] exits. */
    private def trackedScoped[A, S](body: A < (S & Async & Abort[SqlException] & Scope))(using
        Frame
    ): A < (S & Async & Abort[SqlException] & Scope) =
        Sync.Unsafe.defer(raiseInFlight()).andThen(Connection.trackedScoped(requestInFlight)(body))

end MysqlSqlConnection

private[kyo] object MysqlSqlConnection:

    /** Opens MySQL connections for the pool, resolving TLS the way the URL's `sslmode` asked for.
      *
      *   - `disable` / `require` / `verify-ca` / `verify-full`: [[MysqlConnection.connect]], strict TLS behavior.
      *   - `prefer`: [[MysqlConnection.connectWithMode]] with prefer-fallback, so [[exchange.HandshakeExchange]] drops to plaintext when the
      *     server does not advertise `CLIENT_SSL`.
      *   - `allow`: plaintext first, and on `ER_SECURE_TRANSPORT_REQUIRED` (3159) a second attempt with TLS.
      */
    private[kyo] def factory(options: SqlConfig.Url.Options): Connection.Factory[MysqlSqlConnection] =
        new Connection.Factory[MysqlSqlConnection]:
            def open(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
                Frame
            ): MysqlSqlConnection < (Async & Abort[SqlException]) =
                connect(address, password, config, options).flatMap { conn =>
                    conn.connectionId.get.flatMap { cid =>
                        // Unsafe: three plain flags backing the in-flight window and the stream drain's
                        // clean-boundary record; initialised before the connection is visible to any caller.
                        Sync.Unsafe.defer(
                            new MysqlSqlConnection(
                                conn,
                                cid,
                                address,
                                password,
                                config,
                                options,
                                AtomicBoolean.Unsafe.init(false),
                                AtomicBoolean.Unsafe.init(false),
                                AtomicBoolean.Unsafe.init(false)
                            )
                        )
                    }
                }

    private[mysql] def connect(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig, options: SqlConfig.Url.Options)(using
        Frame
    ): MysqlConnection < (Async & Abort[SqlException]) =
        // The handshake response carries the user name as a mandatory field, so it is resolved before any branch below
        // opens a socket. Resolving here rather than per branch is what keeps every arm carrying the same user name.
        Connection.requireUser(address).flatMap { user =>
            val readTimeout = options.socketTimeout.getOrElse(Duration.Infinity)
            config.tlsMode match
                case TlsMode.Prefer =>
                    MysqlConnection.connectWithMode(
                        address.host,
                        address.port,
                        user,
                        password,
                        Present(address.database),
                        config.tls,
                        TlsMode.Prefer,
                        config.preparedStatementCacheSize,
                        config.preparedStatementTtl,
                        socketTimeout = readTimeout
                    )
                case TlsMode.Allow =>
                    Abort.run[SqlException](plainConnect(address, user, password, config, Absent, readTimeout)).flatMap {
                        case Result.Success(conn) => conn
                        case Result.Failure(e) if requiresSecureTransport(e) =>
                            config.tls match
                                case Present(_) =>
                                    // Require rather than Prefer on the retry: if this server also fails to advertise
                                    // CLIENT_SSL the caller gets one clean error instead of a loop between the two modes.
                                    MysqlConnection.connectWithMode(
                                        address.host,
                                        address.port,
                                        user,
                                        password,
                                        Present(address.database),
                                        config.tls,
                                        TlsMode.Require,
                                        config.preparedStatementCacheSize,
                                        config.preparedStatementTtl,
                                        socketTimeout = readTimeout
                                    )
                                case Absent => Abort.fail(e)
                        case Result.Failure(e) => Abort.fail(e)
                        case Result.Panic(t)   => Abort.error(Result.Panic(t))
                    }
                case _ =>
                    plainConnect(address, user, password, config, config.tls, readTimeout)
            end match
        }
    end connect

    private def plainConnect(
        address: SqlConfig.Address,
        user: String,
        password: Maybe[String],
        config: SqlConfig,
        tls: Maybe[kyo.net.NetTlsConfig],
        socketTimeout: Duration
    )(using Frame): MysqlConnection < (Async & Abort[SqlException]) =
        MysqlConnection.connect(
            address.host,
            address.port,
            user,
            password,
            Present(address.database),
            tls,
            config.preparedStatementCacheSize,
            config.preparedStatementTtl,
            socketTimeout = socketTimeout
        )

    /** Whether `e` is MySQL's `ER_SECURE_TRANSPORT_REQUIRED` (error 3159), which is what `sslmode=allow` retries on.
      *
      * The server sends it with "Connections using insecure transport are prohibited while --require_secure_transport=ON." when it is
      * configured that way and the client arrived in the clear. It arrives as an `ErrPacket` in the connection phase reporting SQLSTATE
      * `HY000`, so `HandshakeExchange.mkAuthError` classifies it as a [[kyo.SqlServerException]] whose `extra("code")` carries the error
      * number. Every MySQL server-error factory populates that entry, so the number is the whole signal and no message text is consulted.
      *
      * The match is on the error code alone, with no [[kyo.SqlConnectionException]] arm and no bare message match. A message-text match
      * carries no error code to gate on, so any plaintext connect failure whose text merely names secure transport would become a TLS retry
      * under `sslmode=allow`, and the caller would then see the retry's failure in place of the real one.
      */
    private[mysql] def requiresSecureTransport(e: SqlException): Boolean =
        e match
            case s: SqlServerException => s.extra.get("code").contains("3159")
            case _                     => false

end MysqlSqlConnection
