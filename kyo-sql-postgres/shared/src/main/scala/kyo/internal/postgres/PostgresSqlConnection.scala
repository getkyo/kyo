package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.Sql.BoundValue
import kyo.SqlConfig.TlsMode
import kyo.db.Connection
import kyo.db.Idiom
import kyo.internal.client.SqlConnectionPool
import kyo.net.NetTlsConfig

/** [[kyo.db.Connection]] over a [[PostgresConnection]].
  *
  * Two translations happen here and nowhere else. Binds arrive as [[kyo.Sql.BoundValue]] and leave as [[BoundParam]] through
  * [[PostgresParamWriter]], so nothing above this class names a Postgres wire type. And the in-flight window the pool's reclaim depends on is
  * maintained here, around each delegated call, because this is the narrowest place that sees both the start of a request and the end of its
  * response.
  *
  * ==Reclaim on Postgres==
  *
  * All three reclaim steps are real work on this engine. A `CancelRequest` travels on its own fresh TCP connection carrying the
  * `(processId, secretKey)` pair captured at startup, so it reaches the server even while this connection's socket is mid-response.
  * `ReadyForQuery` then gives the drain an unambiguous barrier: every backend message is self-delimiting behind a type byte, so a reader that
  * has lost its place resynchronises deterministically by reading forward to the next one. That is why [[drainToIdle]] can report a session
  * reusable after a cancel, where the MySQL side cannot.
  */
final private[kyo] class PostgresSqlConnection private[postgres] (
    private[kyo] val underlying: PostgresConnection,
    private val address: SqlConfig.Address,
    // The mode travels with the settings because the reclaim's cancel opens a second socket to the same server and has
    // to make the same encryption decision this one was opened under. Given only the settings it cannot: `Absent` would
    // be indistinguishable from "nothing to negotiate with" under a mode that demands encryption.
    private val tlsMode: TlsMode,
    private val tls: Maybe[NetTlsConfig],
    // The session's pg_type oid lookup for the names PostgresConfig.typeNames declared, resolved at startup
    // by populateTypeRegistry. Immutable once the connection is exposed; the bind path reads it per statement.
    private val typeRegistry: TypeRegistry,
    // Unsafe: these three AtomicBoolean.Unsafe flags are read by the pool's non-suspending eviction and
    // health callbacks (see kyo.db.Connection), so every flip below goes through Sync.Unsafe.defer.
    private val requestInFlight: AtomicBoolean.Unsafe,
    private val exchangeResyncable: AtomicBoolean.Unsafe,
    private val transactionOpen: AtomicBoolean.Unsafe
) extends Connection:

    def id: Long = underlying.processId.toLong

    /** Reports this session's own handshake version, read from the `server_version` parameter it captured at startup. */
    def serverVersion(using Frame): Idiom.ServerVersion < (Async & Abort[SqlException]) =
        underlying.parameters.get.flatMap { params =>
            Maybe.fromOption(params.get("server_version")) match
                case Present(reported) => Connection.parseServerVersion(reported)
                case Absent =>
                    Abort.fail(SqlConnectionProtocolDecodeException(
                        "ParameterStatus",
                        "startup reported no server_version parameter"
                    ))
        }

    // --- Statements ---

    def extendedQuery(sql: String, params: Chunk[BoundValue[?]])(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        tracked(underlying.extendedQuery(sql, native(params)))

    def extendedExecute(sql: String, params: Chunk[BoundValue[?]])(using Frame): Long < (Async & Abort[SqlException]) =
        tracked(underlying.extendedExecute(sql, native(params)))

    def extendedExecuteInsert(sql: String, params: Chunk[BoundValue[?]])(using
        Frame
    ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) =
        tracked(underlying.extendedExecuteInsert(sql, native(params)))

    def simpleQuery(sql: String)(using Frame): Chunk[SqlRow] < (Async & Abort[SqlException]) =
        tracked(underlying.simpleQuery(sql))

    def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        tracked(underlying.simpleExecute(sql))

    def streamQuery(sql: String, params: Chunk[BoundValue[?]], batchSize: Int)(using
        Frame
    ): Stream[SqlRow, Async & Abort[SqlException] & Scope] =
        Stream[SqlRow, Async & Abort[SqlException] & Scope](
            trackedScoped(underlying.streamQuery(sql, native(params), batchSize).emit)
        )

    def pipelined(stmts: Chunk[(String, Chunk[BoundValue[?]])])(using
        Frame
    ): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        val translated = stmts.map { case (sql, params) => (sql, native(params)) }
        // A pipeline puts one Sync barrier per statement on the wire in a single write, so an interrupted pipeline
        // leaves as many ReadyForQuery messages queued as it had statements left. Reading forward to the next one
        // would resynchronise to the wrong place and hand the next borrower the rest of this batch's responses, and
        // there is no cheaper resynchronisation either: after the cancelled statement the server still runs the ones
        // behind it, so waiting for the last barrier is waiting for the whole batch. Marked unresyncable, so the
        // reclaim destroys the connection instead of pretending it can put it back.
        trackedAs(resyncable = false)(underlying.pipelined(translated))
    end pipelined

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
        tracked(underlying.ping)

    def resetSession(using Frame): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.simpleExecute("DISCARD ALL").unit)

    def acquireAdvisoryLock(key: Long, timeout: Maybe[Duration])(using Frame): Unit < (Async & Abort[SqlException]) =
        // `pg_advisory_lock` blocks until the lock is granted and takes no timeout argument, so a caller's
        // timeout has nowhere to go on this engine. It is not silently dropped: the wait is bounded by
        // whatever the caller wrapped the whole operation in, which is where a Postgres timeout belongs.
        val _ = timeout
        tracked(underlying.simpleExecute(s"SELECT pg_advisory_lock($key)").unit)
    end acquireAdvisoryLock

    def releaseAdvisoryLock(key: Long)(using Frame): Unit < (Async & Abort[SqlException]) =
        tracked(underlying.simpleExecute(s"SELECT pg_advisory_unlock($key)").unit)

    // --- Reclaim ---

    // Unsafe: answered synchronously for the pool's non-suspending callbacks, per the kyo.db.Connection contract.
    def inFlight(using AllowUnsafe): Boolean = requestInFlight.get()

    def inOpenTransaction(using AllowUnsafe): Boolean = transactionOpen.get()

    def cancelInFlight(using Frame): Unit < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(requestInFlight.get()).flatMap {
            case false => () // nothing is running, so there is nothing to ask the server to stop
            case true  => underlying.cancel(address, tlsMode, tls)
        }

    def rollbackIfOpenTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(transactionOpen.get()).flatMap {
            case false => ()
            case true  => rollbackTransaction
        }

    def drainToIdle(using Frame): Boolean < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer(requestInFlight.get()).flatMap {
            case false =>
                // The interrupt landed between statements, so the wire is already idle and there is nothing
                // to read. Reading anyway would block until the cancel budget expired and then destroy a
                // connection that was never damaged.
                underlying.isOpen
            case true =>
                Sync.Unsafe.defer(exchangeResyncable.get()).flatMap {
                    case false => false // see `pipelined`: more than one barrier is outstanding
                    case true =>
                        underlying.isOpen.flatMap {
                            case false => false
                            case true  => drainToReadyForQuery
                        }
                }
        }

    def isOpen(using Frame): Boolean < Sync = underlying.isOpen

    def close(using Frame): Unit < Async = Abort.run[SqlException](underlying.terminate).unit

    // Unsafe: closes without suspending, for callers that cannot park (pool discard callbacks).
    def closeNow(using Frame, AllowUnsafe): Unit = Sync.Unsafe.evalOrThrow(underlying.close)

    // --- Internals ---

    /** Reads forward to the next `ReadyForQuery`, which is where every Postgres command ends.
      *
      * The read loop itself is `PostgresConnection.drainToReadyForQuery`, so a `ParameterStatus` or a `NotificationResponse` passed on
      * this path lands in the connection's own state rather than being discarded: a statement that changed a session variable and then
      * failed is exactly what a reclaim drain reads past, and dropping its report would leave [[PostgresConnection.parameters]] stale for the
      * rest of the connection's life.
      *
      * The status byte the message carries is the server's own account of whether a transaction is open, so it is recorded here: it is more
      * authoritative than this adapter's own flag, which only knows about transactions this adapter began.
      */
    private def drainToReadyForQuery(using Frame): Boolean < (Async & Abort[SqlException]) =
        underlying.drainToReadyForQuery.flatMap { rfq =>
            underlying.transactionStatus.set(rfq.status).andThen {
                Sync.Unsafe.defer {
                    requestInFlight.set(false)
                    // 'I' is idle, 'T' is in a transaction, 'E' is a failed transaction. The latter two
                    // both mean a transaction is still open and owed a rollback.
                    transactionOpen.set(rfq.status != 'I'.toByte)
                    true
                }
            }
        }

    // --- Lifecycle ---

    private def native(params: Chunk[BoundValue[?]])(using Frame): Chunk[BoundParam[?]] =
        params.flatMap {
            case b: BoundValue[a] => PostgresParamWriter.write(b.schema, b.value, typeRegistry)
        }

    /** Marks a request in flight for the duration of `body`, resyncable, the default for a single-barrier exchange. */
    private def tracked[A](body: A < (Async & Abort[SqlException]))(using Frame): A < (Async & Abort[SqlException]) =
        trackedAs(resyncable = true)(body)

    /** [[tracked]] for an exchange that says whether a drain can resynchronise it.
      *
      * `resyncable` is false for an exchange that leaves more than one protocol barrier outstanding, where reading forward to the next one
      * would stop in the wrong place. Raises the in-flight flags here, including the Postgres-only `exchangeResyncable` knob; the shared
      * settle-on-exit is [[kyo.db.Connection.tracked]].
      */
    private def trackedAs[A](resyncable: Boolean)(body: A < (Async & Abort[SqlException]))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        Sync.Unsafe.defer {
            requestInFlight.set(true)
            exchangeResyncable.set(resyncable)
        }.andThen(Connection.tracked(requestInFlight)(body))

    /** [[tracked]] for a stream, whose request stays in flight across every batch until the enclosing [[Scope]] exits. */
    private def trackedScoped[A, S](body: A < (S & Async & Abort[SqlException] & Scope))(using
        Frame
    ): A < (S & Async & Abort[SqlException] & Scope) =
        Sync.Unsafe.defer {
            requestInFlight.set(true)
            // A drain will find exactly one ReadyForQuery, and NOT because the stream left one outstanding: the
            // portal loop pipelines Bind, Execute and Flush and sends no Sync, so it keeps ZERO barriers of its own
            // (`StreamQueryExchange`, "no Sync was sent so no ReadyForQuery"). The barrier the drain needs is
            // manufactured on the dirty edge by the portal cleanup, which sends Close plus Sync and deliberately
            // does not read the reply, or is the prepare phase's own already-outstanding Sync when the exit happens
            // before the portal loop owns the wire. Either way the count is one rather than two, which is why the
            // cleanup is keyed on which phase it is in. So this flag rests on that send: drop the Sync
            // `sendCloseAndSync` writes and a drain has nothing to find.
            exchangeResyncable.set(true)
        }.andThen(Connection.trackedScoped(requestInFlight)(body))

end PostgresSqlConnection

private[kyo] object PostgresSqlConnection:

    /** Opens Postgres connections for the pool, resolving TLS the way the URL's `sslmode` asked for.
      *
      *   - `disable` / `require` / `verify-ca` / `verify-full`: [[PostgresConnection.connect]], strict TLS behavior.
      *   - `prefer`: [[PostgresConnection.connectWithNegotiator]] with a [[TlsNegotiator.postgres]] that sends `SSLRequest` and falls back to
      *     plaintext when the server answers `N`.
      *   - `allow`: plaintext first, and on a "server requires SSL" rejection a second attempt with TLS.
      *
      * A connection is not handed back until any configured type names have been resolved to OIDs on it, so no caller can observe a
      * connection whose type registry is still empty.
      *
      * `options` is what the URL declared, and two of its values reach the wire from here: `application_name`, which the server reports back
      * through `pg_stat_activity`, and `socketTimeout`, which bounds each read on the connection.
      */
    private[kyo] def factory(options: SqlConfig.Url.Options): Connection.Factory[PostgresSqlConnection] =
        new Connection.Factory[PostgresSqlConnection]:
            def open(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig)(using
                Frame
            ): PostgresSqlConnection < (Async & Abort[SqlException]) =
                connect(address, password, config, options).flatMap { conn =>
                    populateTypeRegistry(PostgresConfig.of(config).typeNames, conn).flatMap { registry =>
                        // Unsafe: two plain flags backing the in-flight window; initialised before the
                        // connection is visible to any caller.
                        Sync.Unsafe.defer(
                            new PostgresSqlConnection(
                                conn,
                                address,
                                config.tlsMode,
                                config.tls,
                                registry,
                                AtomicBoolean.Unsafe.init(false),
                                AtomicBoolean.Unsafe.init(true),
                                AtomicBoolean.Unsafe.init(false)
                            )
                        )
                    }
                }

    private def connect(address: SqlConfig.Address, password: Maybe[String], config: SqlConfig, options: SqlConfig.Url.Options)(using
        Frame
    ): PostgresConnection < (Async & Abort[SqlException]) =
        // The startup packet carries `user` as a mandatory parameter, so it is resolved before any branch below opens a
        // socket. Resolving here rather than per branch is what keeps every arm carrying the same user name.
        Connection.requireUser(address).flatMap { user =>
            val readTimeout = options.socketTimeout.getOrElse(Duration.Infinity)
            config.tlsMode match
                case TlsMode.Prefer =>
                    config.tls match
                        case Present(tlsConfig) =>
                            val neg = TlsNegotiator.postgres(TlsMode.Prefer, tlsConfig, address.host, address.port)
                            PostgresConnection.connectWithNegotiator(
                                address.host,
                                address.port,
                                user,
                                address.database,
                                password,
                                tls = Absent,
                                negotiator = Present(neg),
                                preparedStmtCacheSize = config.preparedStatementCacheSize,
                                preparedStmtTtl = config.preparedStatementTtl,
                                applicationName = options.applicationName,
                                socketTimeout = readTimeout
                            )
                        case Absent =>
                            // No TLS settings to negotiate with, so plaintext is all `prefer` can mean here.
                            plainConnect(address, user, password, config, Absent, options)
                case TlsMode.Allow =>
                    Abort.run[SqlException](plainConnect(address, user, password, config, Absent, options)).flatMap {
                        case Result.Success(conn) => conn
                        case Result.Failure(e) if requiresSsl(e) =>
                            config.tls match
                                case Present(tlsConfig) =>
                                    PostgresConnection.connectWithNegotiator(
                                        address.host,
                                        address.port,
                                        user,
                                        address.database,
                                        password,
                                        tls = Present(tlsConfig),
                                        negotiator = Absent, // allow: negotiate is a no-op, use the strict TLS upgrade
                                        config.preparedStatementCacheSize,
                                        config.preparedStatementTtl,
                                        applicationName = options.applicationName,
                                        socketTimeout = readTimeout
                                    )
                                case Absent => Abort.fail(e)
                        case Result.Failure(e) => Abort.fail(e)
                        case Result.Panic(t)   => Abort.error(Result.Panic(t))
                    }
                case _ =>
                    plainConnect(address, user, password, config, config.tls, options)
            end match
        }
    end connect

    private def plainConnect(
        address: SqlConfig.Address,
        user: String,
        password: Maybe[String],
        config: SqlConfig,
        tls: Maybe[NetTlsConfig],
        options: SqlConfig.Url.Options
    )(using Frame): PostgresConnection < (Async & Abort[SqlException]) =
        PostgresConnection.connect(
            address.host,
            address.port,
            user,
            address.database,
            password,
            tls,
            config.preparedStatementCacheSize,
            config.preparedStatementTtl,
            applicationName = options.applicationName,
            socketTimeout = options.socketTimeout.getOrElse(Duration.Infinity)
        )

    /** Whether `e` says the server refused a plaintext connection and wants TLS, which is what `sslmode=allow` retries on.
      *
      * PostgreSQL answers SQLSTATE 28000 ("invalid_authorization_specification") when a `hostssl` rule in `pg_hba.conf` requires TLS and the
      * client arrived in the clear. `StartupExchange` wraps the server's `ErrorResponse` as a [[kyo.SqlConnectionException]] carrying the
      * message field, which is why the match below is on message text: the wrap loses the SQLSTATE, and the wire protocol offers no cleaner
      * signal at this point in the handshake. Observed wordings, all locale-sensitive: "no pg_hba.conf entry for host ..., no encryption" on
      * postgres:16-alpine with only `hostssl` rules, "... SSL off" on older versions, and "SSL connection is required" after
      * `ALTER ROLE ... REQUIRE SSL`.
      */
    private def requiresSsl(e: SqlException): Boolean =
        // Only the three documented wordings are matched; a catch-all on `ssl` would turn any plaintext-connect
        // failure that merely mentions SSL into a spurious TLS retry under `sslmode=allow`, hiding the real failure.
        def messageMatches(message: String): Boolean =
            message.contains("no encryption") ||
                message.contains("SSL off") ||
                (message.contains("SSL") && message.contains("required"))
        e match
            case s: SqlServerException     => s.sqlState == "28000" && messageMatches(s.serverMessage)
            case c: SqlConnectionException => messageMatches(c.getMessage())
            case _                         => false
        end match
    end requiresSsl

    /** Resolves the configured type names to OIDs on `conn` and records them in its type registry.
      *
      * Any configured name missing from `pg_type` fails the connect, because a decoder registered against a name the server does not have
      * would otherwise fail later, on a query, with no hint that the registration was the problem. No round-trip happens when no names are
      * configured.
      */
    private def populateTypeRegistry(typeNames: Set[String], conn: PostgresConnection)(using
        Frame
    ): TypeRegistry < (Async & Abort[SqlException]) =
        if typeNames.isEmpty then TypeRegistry.empty
        else
            val nameList = typeNames.map(n => s"'$n'").mkString(", ")
            conn.simpleQuery(s"SELECT typname, oid::int4 FROM pg_type WHERE typname IN ($nameList)").flatMap { rows =>
                val foundMap: Map[String, Int] = rows.foldLeft(Map.empty[String, Int]) { (acc, row) =>
                    val nameBytes = row.column(0).getOrElse(Span.empty[Byte])
                    val oidBytes  = row.column(1).getOrElse(Span.empty[Byte])
                    val name      = new java.lang.String(nameBytes.toArray, StandardCharsets.UTF_8)
                    // The simple-query protocol reports the oid as decimal text. A non-numeric value can only come
                    // from a misbehaving server or proxy; converting it here keeps the failure on the typed
                    // Abort[SqlException] channel instead of escaping as a NumberFormatException panic.
                    val oidText = new java.lang.String(oidBytes.toArray, StandardCharsets.UTF_8).trim
                    val oid = oidText.toIntOption.getOrElse(
                        throw SqlConnectionProtocolDecodeException("pg_type oid", s"non-numeric oid '$oidText' for type '$name'")
                    )
                    acc.updated(name, oid)
                }
                val missing = typeNames -- foundMap.keySet
                if missing.nonEmpty then Abort.fail(SqlConnectionTypeLookupMissingException(Chunk.from(missing)))
                else foundMap
                end if
            }
        end if
    end populateTypeRegistry

    /** Opens a dedicated connection, subscribes it to `channel`, and emits one value per `NOTIFY` it receives.
      *
      * The connection is never pooled: a session running `LISTEN` has no active exchange, so its inbound messages have to be pumped by a fiber
      * of their own, and a pooled connection cannot carry that pump between borrows. Both the pump and the connection are bound to the
      * enclosing [[kyo.Scope]], and that scope is the only thing that ends them. The connection holds no slot and sits in no ring, so
      * [[kyo.SqlClient.close]] has no reference to it: a subscription open at that point is neither closed by `close` nor waited for by it, and
      * keeps delivering until its own scope exits.
      *
      * A connection that dies fails the stream with the pump's own [[kyo.SqlException]]. A listener exists to be long-lived, so the server
      * restart is its most ordinary failure, and a subscriber cannot distinguish a quiet channel from a dead one unless the stream says so.
      *
      * Opened through [[kyo.internal.client.SqlConnectionPool.openDedicated]], which is what keeps this path under the same guards a pooled
      * statement gets: not leasing is not a reason to skip the mandatory-encryption refusal, the connect budget, the engine's TLS-mode
      * routing, the type registry, or the refusal on a closed pool. Reaching past the returned adapter for the `LISTEN` and the pump is
      * deliberate, and the reason is the same one that makes this connection unpoolable: the in-flight window the adapter maintains exists so
      * the pool can reclaim an interrupted lease, and a connection that is never returned has no reclaim to inform. The finalizer stays on the
      * adapter, because the adapter is what the pool handed out and its close is the one documented never to fail.
      *
      * The URL's `socketTimeout` reaches this connection like any other the factory opens, and is then removed by
      * [[PostgresConnection.disableReadTimeout]]: a listener's entire job is to wait for a message that may not come for hours, so a read
      * budget would close a healthy subscription rather than bound a stall. Removed once, while the connection still has a single owner and
      * before the pump's first read. The `application_name` carries over untouched, since a listening session is one a reader of
      * `pg_stat_activity` most wants to be able to name.
      */
    private[kyo] def notificationStream(
        pool: SqlConnectionPool[PostgresSqlConnection],
        address: SqlConfig.Address,
        password: Maybe[String],
        channel: String,
        config: SqlConfig
    )(using Frame): Stream[PostgresClient.Notification, Async & Abort[SqlException] & Scope] =
        // Java's `indexOf` takes a codepoint, so 0 is the NUL. Checked here rather than alongside the quoting
        // below because quoting cannot help: the statement travels as a cstring, so this byte would end it at the
        // server instead of sitting inside the identifier. Refused before a connection is opened for it.
        val nulAt = channel.indexOf(0)
        Stream[PostgresClient.Notification, Async & Abort[SqlException] & Scope](
            if nulAt >= 0 then Abort.fail(SqlRequestNotificationChannelNulException(nulAt))
            else
                // Own the dedicated connection across the openDedicated->Scope.ensure handover: a custody (openDedicated claims into it
                // via custodyLocal) closes the listener if an interrupt drops it before the exit finalizer; take() hands ownership to Scope.ensure on success.
                Sync.Unsafe.defer(new kyo.db.Connection.Custody).map { custody =>
                    Scope.ensure { _ =>
                        Sync.Unsafe.defer(custody.orphan()).map {
                            case Present(close) => close()
                            case Absent         => ()
                        }
                    }.andThen {
                        kyo.db.Connection.custodyLocal.let(Present(custody)) {
                            pool.openDedicated(address, password, config)
                        }.map { adapter =>
                            Scope.ensure(adapter.close).andThen(Sync.Unsafe.defer(custody.take())).andThen {
                                val conn = adapter.underlying
                                conn.disableReadTimeout.andThen {
                                    AtomicRef.init(Maybe.empty[SqlException]).flatMap { pumpCause =>
                                        // Postgres identifier quoting: each `"` inside the name is doubled so a channel called
                                        // `foo"; DROP TABLE users; --` cannot break out of the quoted identifier and smuggle in
                                        // a second simple-query statement.
                                        val quoted = channel.replace("\"", "\"\"")
                                        conn.simpleExecute(s"""LISTEN "$quoted"""").andThen {
                                            Fiber.init(pump(conn, pumpCause)).flatMap { pumpFiber =>
                                                Scope.ensure(pumpFiber.interrupt.unit).andThen(emit(conn, pumpCause).emit)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
        )
    end notificationStream

    /** Reads the dedicated connection forever, routing notifications into its channel.
      *
      * The pump is the only reader on this connection, so its death is the stream's death: a receive failure (the server restarted, the
      * transport dropped) records its cause and closes the notification channel, which turns the consumer's suspended take into a typed
      * failure instead of eternal silence. A full channel suspends the pump rather than dropping the delivery: the unread socket then
      * backpressures the server, which queues undelivered notifications per session, so one value per `NOTIFY` holds for a slow consumer
      * too.
      */
    private def pump(conn: PostgresConnection, cause: AtomicRef[Maybe[SqlException]])(using Frame): Unit < Async =
        Abort.run[SqlException](conn.receive).flatMap {
            case Result.Failure(e) => failStream(conn, cause, e)
            case Result.Panic(t)   => failStream(conn, cause, SqlConnectionNotificationPanicException(t))
            case Result.Success(msg) =>
                msg match
                    case n: NotificationResponse =>
                        Abort.run[Closed](conn.notifications.put(n)).flatMap {
                            case Result.Success(_) => pump(conn, cause)
                            case Result.Failure(_) => () // closed: the stream is already tearing down
                            case Result.Panic(t)   => failStream(conn, cause, SqlConnectionNotificationPanicException(t))
                        }
                    case _ => pump(conn, cause)
        }

    /** Records why the pump died, then closes the channel so the emit loop fails typed instead of waiting forever. The order is what
      * makes the read on the other side safe: the emit loop sees the closure only after the cause is set.
      */
    private def failStream(conn: PostgresConnection, cause: AtomicRef[Maybe[SqlException]], e: SqlException)(using
        Frame
    ): Unit < Async =
        cause.set(Maybe.Present(e)).andThen(conn.notifications.close.unit)

    private def emit(conn: PostgresConnection, cause: AtomicRef[Maybe[SqlException]])(using
        Frame
    ): Stream[PostgresClient.Notification, Async & Abort[SqlException]] =
        Stream[PostgresClient.Notification, Async & Abort[SqlException]]:
            Loop.foreach:
                Abort.run[Closed](conn.notifications.take).flatMap {
                    case Result.Success(n) =>
                        Emit.valueWith(Chunk(PostgresClient.Notification(n.channel, n.payload, n.processId)))(Loop.continue)
                    case Result.Failure(_) =>
                        // The channel is closed only by the pump on its way out, so closure IS the failure
                        // signal: surface the recorded cause rather than ending a stream the caller still
                        // believes is subscribed.
                        cause.get.flatMap {
                            case Maybe.Present(e) => Abort.fail(e)
                            case Maybe.Absent     => Abort.fail(SqlConnectionClosedException("notification connection"))
                        }
                    case Result.Panic(t) =>
                        Abort.fail(SqlConnectionNotificationPanicException(t))
                }

end PostgresSqlConnection
