package kyo.internal.mysql

import kyo.*
import kyo.SqlClient.IsolationLevel
import kyo.SqlConfig.TlsMode
import kyo.SqlConnectionCancelTimeoutException
import kyo.SqlConnectionConnectFailedException
import kyo.SqlConnectionUnexpectedMessageException
import kyo.SqlException
import kyo.db.Connection
import kyo.internal.mysql.exchange.ExtendedQueryExchange
import kyo.internal.mysql.exchange.HandshakeExchange
import kyo.internal.mysql.exchange.HandshakeResult
import kyo.internal.mysql.exchange.LocalInfileExchange
import kyo.internal.mysql.exchange.MysqlCancelExchange
import kyo.internal.mysql.exchange.MysqlErrors
import kyo.internal.mysql.exchange.MysqlPipelineExchange
import kyo.internal.mysql.exchange.MysqlTransactionExchange
import kyo.internal.mysql.exchange.ResetConnectionExchange
import kyo.internal.mysql.exchange.SimpleQueryExchange
import kyo.internal.mysql.exchange.StreamQueryExchange
import kyo.net.NetPlatform
import kyo.net.NetTlsConfig

/** An active MySQL connection with per-connection state.
  *
  * Wraps a [[MysqlChannel]] and holds the server metadata received during the handshake:
  *   - `connectionId`, the server-assigned thread/connection ID
  *   - `serverCapabilities`, negotiated capability flags
  *   - `serverVersion`, server version string (e.g. "8.0.34")
  *   - `charset`, negotiated charset number
  *   - `statusFlags`, last-seen server status flags
  *   - `preparedStmts`, per-connection LRU cache of server-side prepared statements
  *
  * All public methods are safe. A single [[MysqlConnection]] must NOT be used concurrently, the caller ensures serial access.
  */
final class MysqlConnection(
    private[mysql] val channel: MysqlChannel,
    val connectionId: AtomicRef[Long],
    val serverCapabilities: AtomicRef[Long],
    val serverVersion: AtomicRef[String],
    val charset: AtomicRef[Int],
    val statusFlags: AtomicRef[Int],
    private[mysql] val preparedStmts: Cache[String, MysqlPreparedStmt],
    private[kyo] val pendingCloses: AtomicRef[Chunk[Int]]
):
    /** Executes `sql` using the simple-query (text) protocol and returns all result rows. */
    def simpleQuery(sql: String)(using Frame): Chunk[MysqlRow] < (Async & Abort[SqlException]) =
        withCapsAndId { (deprecateEof, cid) =>
            SimpleQueryExchange.run(channel, sql, deprecateEof, cid).map { case (rows, _) => rows }
        }

    /** Executes `sql` using the simple-query (text) protocol and returns the number of affected rows.
      *
      * For SELECT statements this is 0; for INSERT/UPDATE/DELETE it is the affected-row count from the OK packet.
      */
    def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        withCapsAndId { (deprecateEof, cid) =>
            SimpleQueryExchange.run(channel, sql, deprecateEof, cid).map { case (_, affected) => affected }
        }

    /** Executes a parameterised query using the extended (binary) protocol and returns all result rows.
      *
      * Prepares the statement on first use (caches it in [[preparedStmts]]) then binds parameters via [[ComStmtExecute]] with binary
      * encoding. BinaryResultsetRow packets are decoded per-column using the column type metadata from [[StmtPrepareOk]].
      *
      * @param sql
      *   parameterised SQL text with `?` placeholders
      * @param params
      *   parameter values to bind
      */
    def extendedQuery(sql: String, params: Chunk[BoundMysqlParam[?]])(using
        Frame
    ): Chunk[MysqlRow] < (Async & Abort[SqlException]) =
        drainPendingCloses.andThen(withCapsAndId { (deprecateEof, cid) =>
            ExtendedQueryExchange.query(channel, preparedStmts, sql, params, deprecateEof, cid)
        })

    /** Executes a parameterised DML statement using the extended (binary) protocol and returns affected rows.
      *
      * @param sql
      *   parameterised SQL text with `?` placeholders
      * @param params
      *   parameter values to bind
      */
    def extendedExecute(sql: String, params: Chunk[BoundMysqlParam[?]])(using
        Frame
    ): Long < (Async & Abort[SqlException]) =
        drainPendingCloses.andThen(withCapsAndId { (deprecateEof, cid) =>
            ExtendedQueryExchange.execute(channel, preparedStmts, sql, params, deprecateEof, cid)
        })

    /** Runs an extended INSERT and returns an [[SqlClient.InsertOutcome]] derived from the server's OK packet.
      *
      * `generatedKey` is [[SqlClient.InsertOutcome.GeneratedKey.Value]]`(<lastInsertId>)` when the server reported a non-zero auto-increment value, and
      * [[SqlClient.InsertOutcome.GeneratedKey.Unavailable]] when `lastInsertId == 0` (MySQL's convention for "no auto-increment value generated for this statement"
      * applies even when the target schema does have an `AUTO_INCREMENT` column, e.g. when the caller supplied an explicit non-zero id).
      * The pure-no-auto-column case ([[SqlClient.InsertOutcome.GeneratedKey.NoAutoKey]]) is emitted by the Postgres path only; MySQL cannot distinguish "no
      * AUTO_INCREMENT column" from "auto-increment suppressed" at the OK-packet level.
      */
    def extendedExecuteInsert(sql: String, params: Chunk[BoundMysqlParam[?]])(using
        Frame
    ): SqlClient.InsertOutcome < (Async & Abort[SqlException]) =
        drainPendingCloses.andThen(withCapsAndId { (deprecateEof, cid) =>
            ExtendedQueryExchange.executeInsert(channel, preparedStmts, sql, params, deprecateEof, cid).map {
                // MySQL cannot report GeneratedKey.NoAutoKey: lastInsertId == 0 is ambiguous between "no
                // auto-increment column" and "caller supplied the key", so this reports Unavailable where the
                // PostgreSQL path distinguishes the two.
                case (affected, lastInsertId) =>
                    val key =
                        if lastInsertId == 0L then SqlClient.InsertOutcome.GeneratedKey.Unavailable
                        else SqlClient.InsertOutcome.GeneratedKey.Value(lastInsertId)
                    SqlClient.InsertOutcome(affected, key)
            }
        })

    /** Streams rows from a parameterised query using per-row wire reads (approach 2, no cursor).
      *
      * Prepares the statement (or uses a cached one), binds parameters via [[ComStmtExecute]] (flags=0, no cursor), then reads
      * [[BinaryResultsetRow]] packets one at a time from the wire, yielding each decoded [[MysqlRow]] into the stream. On stream completion
      * or [[Scope]] exit, sends [[ComStmtClose]] to free the server-side statement.
      *
      * @param sql
      *   parameterised SQL text with `?` placeholders
      * @param params
      *   parameter values to bind
      * @param batchSize
      *   informational only (rows are read one-by-one from the wire without cursor batching)
      * @param escalate
      *   sends a server-side `KILL QUERY` for this connection's in-flight statement, used by the cleanup drain when an early-terminated
      *   stream leaves more rows on the wire than it is worth reading. Defaults to doing nothing, because stopping the statement needs a
      *   SIDECAR connection and this class holds no address, password or config to open one with. Only [[MysqlSqlConnection]] has them, so
      *   only that path can supply a real escalation; a caller holding a bare [[MysqlConnection]] gets the drain alone. Threading it as a
      *   parameter rather than reaching for it keeps that asymmetry visible here.
      * @param onCleanBoundary
      *   runs when the cleanup has the wire on a result-set terminator, consumed or drained. Threaded for the same reason as `escalate`:
      *   the in-flight window lives in [[MysqlSqlConnection]], and this is how the drain's outcome reaches it, so the pool can tell an
      *   interrupted stream whose wire was cleaned from one whose drain failed.
      */
    def streamQuery(
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        batchSize: Int,
        escalate: Unit < (Async & Abort[SqlException]) = (),
        onCleanBoundary: Unit < Sync = ()
    )(using Frame): Stream[MysqlRow, Async & Abort[SqlException] & Scope & Sync] =
        Stream:
            drainPendingCloses.andThen(
                withCapsAndId { (deprecateEof, cid) =>
                    StreamQueryExchange.stream(channel, preparedStmts, sql, params, deprecateEof, cid, escalate, onCleanBoundary).emit
                }
            )

    /** Executes a `LOAD DATA LOCAL INFILE` statement, streaming `data` bytes to the server.
      *
      * Sends a COM_QUERY with `sql`, reads the LOCAL_INFILE_REQUEST (0xFB), then uploads the byte stream as a sequence of LOCAL_INFILE_DATA
      * packets (each up to 16 MB - 1 bytes). Terminates with an empty data packet and reads the server's OK packet for the affected-row
      * count.
      *
      * The CLIENT_LOCAL_FILES capability is negotiated automatically during the handshake.
      *
      * @param sql
      *   a `LOAD DATA LOCAL INFILE 'filename' INTO TABLE ...` statement
      * @param data
      *   the byte stream to upload (the caller supplies this: in-memory, Path.readBytes, HTTP-backed, etc.)
      * @return
      *   the affected-row count from the server's OK packet
      */
    def loadLocalInfile[S](sql: String, data: Stream[Byte, S])(using Frame): Long < (Async & Abort[SqlException] & S) =
        connectionId.get.flatMap { cid =>
            Scope.run(SimpleQueryExchange.runLocalInfile(channel, sql, data, Maybe(cid)))
        }

    /** Sends [[ComPing]] and waits for the OK response. */
    def ping()(using frame: Frame): Unit < (Async & Abort[SqlException]) =
        channel.resetSeq()
        channel.send(ComPing)(using channel.marshallers.comPing).flatMap { _ =>
            connectionId.get.flatMap { cid =>
                channel.receive(false).flatMap {
                    case _: OkPacket    => ()
                    case err: ErrPacket => Abort.fail(MysqlErrors.mkServerError(err, Maybe.Absent, 0, Maybe(cid))(using frame))
                    case other =>
                        Abort.fail(SqlConnectionUnexpectedMessageException(
                            "ping",
                            "OkPacket / ErrPacket",
                            other.toString
                        )(using frame))
                }
            }
        }
    end ping

    /** Sends [[ComQuit]] to gracefully terminate the session. No response is expected. */
    def quit()(using Frame): Unit < (Async & Abort[SqlException]) =
        channel.resetSeq()
        channel.send(ComQuit)(using channel.marshallers.comQuit)

    /** Sends [[ComResetConnection]] and waits for OK.
      *
      * Resets all per-session state (user variables, prepared statements on the server, open transactions, last-insert-id, current schema,
      * advisory locks) without re-running the auth handshake. Callers use this to guarantee the next borrower sees a clean session.
      *
      * Delegates to [[ResetConnectionExchange]].
      */
    def resetConnection()(using Frame): Unit < (Async & Abort[SqlException]) =
        ResetConnectionExchange.run(channel)

    // --- Transaction methods ---

    /** Begins a transaction.
      *
      * MySQL InnoDB DDL caveat: DDL statements (`CREATE TABLE`, `ALTER TABLE`, `DROP TABLE`, etc.) inside a transaction cause an implicit
      * commit before and after the statement. This is a MySQL/InnoDB limitation, kyo-sql does not attempt to detect implicit commits.
      *
      * @param isolation
      *   optional isolation level; [[Absent]] uses the server default (`REPEATABLE READ` for InnoDB)
      * @param readOnly
      *   if `true`, opens a `READ ONLY` transaction (INSERT/UPDATE/DELETE will fail)
      */
    def beginTransaction(
        isolation: Maybe[SqlClient.IsolationLevel],
        readOnly: Boolean
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        withDeprecateEof(deprecateEof => MysqlTransactionExchange.begin(channel, deprecateEof, isolation, readOnly))

    /** Commits the current transaction. */
    def commitTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        withDeprecateEof(deprecateEof => MysqlTransactionExchange.commit(channel, deprecateEof))

    /** Rolls back the current transaction. */
    def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        withDeprecateEof(deprecateEof => MysqlTransactionExchange.rollback(channel, deprecateEof))

    /** Creates a savepoint with `name` inside the current transaction. */
    def savepointTransaction(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        withDeprecateEof(deprecateEof => MysqlTransactionExchange.savepoint(channel, deprecateEof, name))

    /** Releases (commits) the savepoint `name`. */
    def releaseSavepointTransaction(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        withDeprecateEof(deprecateEof => MysqlTransactionExchange.releaseSavepoint(channel, deprecateEof, name))

    /** Rolls back to savepoint `name` (the savepoint itself is preserved; the outer transaction continues). */
    def rollbackToSavepointTransaction(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        withDeprecateEof(deprecateEof => MysqlTransactionExchange.rollbackToSavepoint(channel, deprecateEof, name))

    // --- Cancellation ---

    /** Cancels the query running on `this` connection by sending `KILL QUERY <connectionId>` on `cancelConn`.
      *
      * `cancelConn` must be a **separate** already-authenticated [[MysqlConnection]], the sidecar. This method does not close it and never
      * pools it; the caller closes it on every exit edge.
      *
      * Returns `Unit` whether the query was running or had already completed (KILL is idempotent when the target is absent).
      *
      * @param cancelConn
      *   the sidecar MySQL connection used as the cancel vehicle
      */
    def cancelQuery(cancelConn: MysqlConnection)(using Frame): Unit < (Async & Abort[SqlException]) =
        connectionId.get.flatMap { targetId =>
            MysqlCancelExchange.kill(cancelConn, targetId)
        }

    /** Executes multiple statements sequentially on this connection, returning one pipeline result per statement.
      *
      * Each statement is isolated: a per-statement server error is recorded as [[kyo.Result.Failure]] without aborting
      * subsequent statements. Connection-level errors (socket closed, panic) re-raise and abort the entire pipeline.
      *
      * MySQL does not support the PostgreSQL Sync-barrier batch-write protocol, so statements are executed one at a time in order using the
      * extended (binary) protocol ([[MysqlPipelineExchange.runOnConnection]]).
      *
      * @param stmts
      *   `(sql, params)` pairs in submission order
      * @return
      *   one pipeline result per statement, in submission order
      */
    def pipelined(
        stmts: Chunk[(String, Chunk[BoundMysqlParam[?]])]
    )(using Frame): Chunk[kyo.Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < (Async & Abort[SqlException]) =
        val pipelineStmts = stmts.map { case (sql, params) =>
            MysqlPipelineExchange.PipelineStmt(sql, params)
        }
        MysqlPipelineExchange.runOnConnection(this, pipelineStmts)
    end pipelined

    /** Returns `true` if the underlying [[kyo.net.Connection]] is still open. */
    def isOpen(using Frame): Boolean < Sync =
        Sync.Unsafe.defer(channel.conn.isOpen)

    /** Closes the MySQL connection.
      *
      * @param gracePeriod
      *   If `Duration.Zero`, the socket is closed immediately without sending `COM_QUIT`. If `> Duration.Zero`, `COM_QUIT` is sent and the
      *   implementation waits up to `gracePeriod` for the server's acknowledgement before forcing the socket closed. Quit-timeout errors
      *   are swallowed, the socket is closed regardless. Diverges from PostgreSQL, which always issues a graceful `Terminate` message
      *   before closing. Typical callers should pass a short duration (e.g. `30.seconds`) so in-flight queries have a chance to complete;
      *   pass `Duration.Zero` only when an immediate hard close is required (e.g. pool eviction on error). This method does not throw, any
      *   `SqlException` raised by `COM_QUIT` is discarded before the socket close.
      */
    def close(gracePeriod: Duration)(using Frame): Unit < (Async & Abort[SqlException]) =
        if gracePeriod == Duration.Zero then
            Sync.Unsafe.defer(channel.conn.close())
        else
            // Best-effort quit bounded by gracePeriod: ignore errors and timeout (connection may already be closing).
            Abort.run[SqlException](
                Async.timeoutWithError(gracePeriod, Result.Failure(SqlConnectionCancelTimeoutException(gracePeriod)))(quit())
            ).andThen(Sync.Unsafe.defer(channel.conn.close()))

    /** Gracefully closes the connection with a default 30-second grace period. Delegates to the `close(gracePeriod)` overload. */
    def close(using Frame): Unit < (Async & Abort[SqlException]) =
        close(30.seconds)

    /** Closes the underlying TCP connection immediately without sending [[ComQuit]].
      *
      * Used by the pool `discard` callback (which runs in an `AllowUnsafe` context and cannot suspend for protocol shutdown) and by
      * `closeAll` shutdown paths where [[quit]] has already been sent. Prefer [[close]] for user-facing graceful shutdown.
      */
    def closeNow(using Frame): Unit < Sync =
        Sync.Unsafe.defer(channel.conn.close())

    // CLIENT_DEPRECATE_EOF: when negotiated, EOF packets between column defs and rows are replaced by OK packets.
    private def hasDeprecateEof(caps: Long): Boolean =
        (caps & Capabilities.CLIENT_DEPRECATE_EOF) != 0L

    /** Reads `serverCapabilities` and `connectionId` once, then runs `f` with the negotiated CLIENT_DEPRECATE_EOF flag and the wrapped
      * connection id: the prologue every query and stream on this connection shares.
      */
    private def withCapsAndId[A, S](f: (Boolean, Maybe[Long]) => A < (S & Async & Abort[SqlException]))(using
        Frame
    ): A < (S & Async & Abort[SqlException]) =
        serverCapabilities.get.flatMap { caps =>
            connectionId.get.flatMap { cid =>
                f(hasDeprecateEof(caps), Maybe(cid))
            }
        }

    /** Reads `serverCapabilities` once and runs `f` with the negotiated CLIENT_DEPRECATE_EOF flag: the prologue every transaction method on
      * this connection shares.
      */
    private def withDeprecateEof[A](f: Boolean => A < (Async & Abort[SqlException]))(using
        Frame
    ): A < (Async & Abort[SqlException]) =
        serverCapabilities.get.flatMap { caps =>
            f(hasDeprecateEof(caps))
        }

    /** Sends `ComStmtClose` for each statement ID accumulated in [[pendingCloses]] since the last drain, clearing the queue.
      *
      * Called at the start of every extended-protocol request. The server sends no reply to `ComStmtClose`, so this is fire-and-forget from
      * the protocol perspective and does not block the subsequent request.
      *
      * The queue holds the ids as `Int`, which is what `ComStmtClose` takes and what `MysqlPreparedStmt.stmtId` is. The MySQL and Postgres
      * queues differ in element type because a PostgreSQL prepared statement is identified by a NAME, so `PostgresConnection.pendingCloses`
      * is `Chunk[String]` while this one is `Chunk[Int]`.
      */
    private[kyo] def drainPendingCloses(using Frame): Unit < (Async & Abort[SqlException]) =
        pendingCloses.getAndSet(Chunk.empty).flatMap { ids =>
            Kyo.foreach(ids) { stmtId =>
                channel.resetSeq()
                channel.send(ComStmtClose(stmtId))(using channel.marshallers.comStmtClose)
            }.unit
        }

end MysqlConnection

object MysqlConnection:

    /** Establishes a MySQL connection (plaintext or TLS).
      *
      * Sequence:
      *   1. Connect via [[NetPlatform.transport]].
      *   2. Build a plaintext [[MysqlChannel]].
      *   3. Run [[HandshakeExchange]], reads HandshakeV10, optionally upgrades to TLS, sends HandshakeResponse41, handles auth.
      *   4. Use the channel returned by [[HandshakeResult]] (may be TLS-wrapped).
      *   5. Populate per-connection state from [[HandshakeResult]].
      *   6. Return a [[MysqlConnection]].
      *
      * @param host
      *   hostname or IP address
      * @param port
      *   TCP port (default MySQL: 3306)
      * @param user
      *   database user
      * @param password
      *   the credential to authenticate with, or [[Maybe.Absent]] when there is none. [[Maybe.Present]] with an empty string
      *   also arrives, from a URL whose userinfo ended in a bare `:`, and MySQL treats the two alike by design: every plugin
      *   path in [[HandshakeExchange]] produces the same bytes for both, which is the zero-length auth response a genuinely
      *   passwordless account authenticates with.
      * @param db
      *   optional initial database
      * @param tls
      *   optional TLS configuration; [[Maybe.Absent]] = plaintext, [[Maybe.Present]] = TLS required (CLIENT_SSL mid-handshake upgrade)
      * @param preparedStmtCacheSize
      *   maximum number of prepared statements to cache per connection (default 64)
      * @param preparedStmtTtl
      *   TTL for cached prepared statements (default Infinity = bounded only by size)
      */
    def connect(
        host: String,
        port: Int,
        user: String,
        password: Maybe[String],
        db: Maybe[String],
        tls: Maybe[NetTlsConfig],
        preparedStmtCacheSize: Int,
        preparedStmtTtl: Duration,
        socketTimeout: Duration = Duration.Infinity
    )(using Frame): MysqlConnection < (Async & Abort[SqlException]) =
        Connection.openSocket(host, port, t => SqlConnectionConnectFailedException(host, port, t)) { conn =>
            MysqlChannel(conn, socketTimeout).flatMap { rawChannel =>
                HandshakeExchange.run(rawChannel, user, password, db, host, port, tls, false).flatMap { result =>
                    // result.channel is the TLS-wrapped channel (or the original if no TLS).
                    val ttl = if preparedStmtTtl == Duration.Infinity then Duration.Zero else preparedStmtTtl
                    mkConnection(result.channel, result, preparedStmtCacheSize, ttl)
                }
            }
        }
    end connect

    /** Builds this connection's prepared-statement cache, wiring eviction to enqueue the released server-side statement id into `closesRef`
      * so the next `drainPendingCloses` flushes `COM_STMT_CLOSE` for it. The shared body lives in [[kyo.db.Connection.mkStmtCache]]; the key
      * a MySQL prepared statement is closed by is its numeric id.
      */
    private[mysql] def mkStmtCache(
        closesRef: AtomicRef[Chunk[Int]],
        maxSize: Int,
        ttl: Duration
    )(using Frame): Cache[String, MysqlPreparedStmt] < Sync =
        Connection.mkStmtCache(closesRef, maxSize, ttl, (stmt: MysqlPreparedStmt) => stmt.stmtId)

    /** Initialises per-connection mutable state from `result` and wraps its channel in a [[MysqlConnection]].
      *
      * The shared per-connection init for [[connect]] and [[connectWithMode]]: the only varying inputs are the handshake `result`, the
      * prepared-statement cache capacity, and the pre-computed `ttl` (callers normalise [[Duration.Infinity]] to [[Duration.Zero]] before
      * calling here). The test-only [[withConnection]] has its own block because it seeds zeroed metadata rather than a handshake result.
      */
    private def mkConnection(
        channel: MysqlChannel,
        result: HandshakeResult,
        preparedStmtCacheSize: Int,
        ttl: Duration
    )(using Frame): MysqlConnection < Sync =
        for
            connIdRef  <- AtomicRef.init(result.connectionId)
            capsRef    <- AtomicRef.init(result.capabilities)
            versionRef <- AtomicRef.init(result.serverVersion)
            charsetRef <- AtomicRef.init(result.charset)
            statusRef  <- AtomicRef.init(result.statusFlags)
            closesRef  <- AtomicRef.init(Chunk.empty[Int])
            stmtCache  <- MysqlConnection.mkStmtCache(closesRef, preparedStmtCacheSize, ttl)
        yield new MysqlConnection(
            channel,
            connIdRef,
            capsRef,
            versionRef,
            charsetRef,
            statusRef,
            stmtCache,
            closesRef
        )
        end for
    end mkConnection

    /** Like [[connect]] but with an explicit [[TlsMode]] for opportunistic TLS (sslmode=prefer/allow).
      *
      * For `sslmode=prefer`: uses `preferFallback=true` in [[HandshakeExchange.run]], which makes the handshake fall back to plaintext if
      * the server does not advertise CLIENT_SSL (instead of failing).
      *
      * For `sslmode=allow`: connects plaintext first (tls=Absent); the caller is responsible for catching error 3159
      * (ER_SECURE_TRANSPORT_REQUIRED) and reconnecting with `tls=Present`.
      *
      * Carries the mode so [[MysqlSqlConnection]] can request opportunistic TLS while [[connect]] stays the strict-mode entry point.
      */
    private[internal] def connectWithMode(
        host: String,
        port: Int,
        user: String,
        password: Maybe[String],
        db: Maybe[String],
        tls: Maybe[NetTlsConfig],
        tlsMode: TlsMode,
        preparedStmtCacheSize: Int,
        preparedStmtTtl: Duration,
        socketTimeout: Duration = Duration.Infinity
    )(using Frame): MysqlConnection < (Async & Abort[SqlException]) =
        Connection.openSocket(host, port, t => SqlConnectionConnectFailedException(host, port, t)) { conn =>
            MysqlChannel(conn, socketTimeout).flatMap { rawChannel =>
                val preferFallback = tlsMode == TlsMode.Prefer
                val ttl            = if preparedStmtTtl == Duration.Infinity then Duration.Zero else preparedStmtTtl
                HandshakeExchange.run(rawChannel, user, password, db, host, port, tls, preferFallback).flatMap { result =>
                    mkConnection(result.channel, result, preparedStmtCacheSize, ttl)
                }
            }
        }
    end connectWithMode

    /** Creates a [[MysqlConnection]] backed by `conn` with zeroed-out server metadata.
      *
      * Intended for unit tests that need a [[MysqlConnection]] instance without running the full handshake. The prepared-statement cache is
      * initialised with a minimal capacity and no TTL.
      */
    private[kyo] def withConnection(conn: kyo.net.Connection)(using Frame): MysqlConnection < Sync =
        MysqlChannel(conn).flatMap { channel =>
            for
                connIdRef  <- AtomicRef.init(0L)
                capsRef    <- AtomicRef.init(0L)
                versionRef <- AtomicRef.init("")
                charsetRef <- AtomicRef.init(0)
                statusRef  <- AtomicRef.init(0)
                closesRef  <- AtomicRef.init(Chunk.empty[Int])
                stmtCache  <- MysqlConnection.mkStmtCache(closesRef, 8, Duration.Zero)
            yield new MysqlConnection(
                channel,
                connIdRef,
                capsRef,
                versionRef,
                charsetRef,
                statusRef,
                stmtCache,
                closesRef
            )
            end for
        }

end MysqlConnection
