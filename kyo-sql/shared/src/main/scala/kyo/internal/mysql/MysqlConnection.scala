package kyo.internal.mysql

import kyo.*
import kyo.SqlException
import kyo.SqlIsolationLevel
import kyo.internal.mysql.exchange.ExtendedQueryExchange
import kyo.internal.mysql.exchange.HandshakeExchange
import kyo.internal.mysql.exchange.LocalInfileExchange
import kyo.internal.mysql.exchange.MysqlCancelExchange
import kyo.internal.mysql.exchange.MysqlPipelineExchange
import kyo.internal.mysql.exchange.MysqlTransactionExchange
import kyo.internal.mysql.exchange.ResetConnectionExchange
import kyo.internal.mysql.exchange.SimpleQueryExchange
import kyo.internal.mysql.exchange.StreamQueryExchange
import kyo.internal.tls.TlsMode
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
    private[kyo] val pendingCloses: AtomicRef[Chunk[String]]
):
    // CLIENT_DEPRECATE_EOF: when negotiated, EOF packets between column defs and rows are replaced by OK packets.
    private def hasDeprecateEof(caps: Long): Boolean =
        (caps & Capabilities.CLIENT_DEPRECATE_EOF) != 0L

    /** Sends `ComStmtClose` for each statement ID accumulated in [[pendingCloses]] since the last drain, clearing the queue.
      *
      * Called at the start of every extended-protocol request. The server sends no reply to `ComStmtClose`, so this is fire-and-forget from
      * the protocol perspective and does not block the subsequent request.
      */
    private[kyo] def drainPendingCloses(using Frame): Unit < (Async & Abort[SqlException]) =
        pendingCloses.getAndSet(Chunk.empty).flatMap { ids =>
            Kyo.foreach(ids) { idStr =>
                val stmtId = idStr.toInt
                channel.resetSeq()
                channel.send(ComStmtClose(stmtId))(using channel.marshallers.comStmtClose)
            }.unit
        }

    /** Executes `sql` using the simple-query (text) protocol and returns all result rows. */
    def simpleQuery(sql: String)(using Frame): Chunk[MysqlRow] < (Async & Abort[SqlException]) =
        serverCapabilities.get.flatMap { caps =>
            connectionId.get.flatMap { cid =>
                SimpleQueryExchange.run(channel, sql, hasDeprecateEof(caps), Maybe(cid)).map { case (rows, _) => rows }
            }
        }

    /** Executes `sql` using the simple-query (text) protocol and returns the number of affected rows.
      *
      * For SELECT statements this is 0; for INSERT/UPDATE/DELETE it is the affected-row count from the OK packet.
      */
    def simpleExecute(sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        serverCapabilities.get.flatMap { caps =>
            connectionId.get.flatMap { cid =>
                SimpleQueryExchange.run(channel, sql, hasDeprecateEof(caps), Maybe(cid)).map { case (_, affected) => affected }
            }
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
        drainPendingCloses.andThen(serverCapabilities.get.flatMap { caps =>
            connectionId.get.flatMap { cid =>
                ExtendedQueryExchange.query(channel, preparedStmts, sql, params, hasDeprecateEof(caps), Maybe(cid))
            }
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
        drainPendingCloses.andThen(serverCapabilities.get.flatMap { caps =>
            connectionId.get.flatMap { cid =>
                ExtendedQueryExchange.execute(channel, preparedStmts, sql, params, hasDeprecateEof(caps), Maybe(cid))
            }
        })

    /** Runs an extended INSERT and returns an [[InsertResult]] derived from the server's OK packet.
      *
      * `generatedKey` is [[GeneratedKey.Value]]`(<lastInsertId>)` when the server reported a non-zero auto-increment value, and
      * [[GeneratedKey.Unavailable]] when `lastInsertId == 0` (MySQL's convention for "no auto-increment value generated for this statement"
      * applies even when the target schema does have an `AUTO_INCREMENT` column, e.g. when the caller supplied an explicit non-zero id).
      * The pure-no-auto-column case ([[GeneratedKey.NoAutoKey]]) is emitted by the Postgres path only; MySQL cannot distinguish "no
      * AUTO_INCREMENT column" from "auto-increment suppressed" at the OK-packet level.
      */
    def extendedExecuteInsert(sql: String, params: Chunk[BoundMysqlParam[?]])(using
        Frame
    ): InsertResult < (Async & Abort[SqlException]) =
        drainPendingCloses.andThen(serverCapabilities.get.flatMap { caps =>
            connectionId.get.flatMap { cid =>
                ExtendedQueryExchange.executeInsert(channel, preparedStmts, sql, params, hasDeprecateEof(caps), Maybe(cid)).map {
                    case (affected, lastInsertId) =>
                        val key =
                            if lastInsertId == 0L then GeneratedKey.Unavailable
                            else GeneratedKey.Value(lastInsertId)
                        InsertResult(affected, key)
                }
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
      */
    def streamQuery(
        sql: String,
        params: Chunk[BoundMysqlParam[?]],
        batchSize: Int
    )(using Frame): Stream[MysqlRow, Async & Abort[SqlException] & Scope & Sync] =
        Stream:
            drainPendingCloses.andThen(
                serverCapabilities.get.flatMap { caps =>
                    connectionId.get.flatMap { cid =>
                        val inner = StreamQueryExchange.stream(channel, preparedStmts, sql, params, hasDeprecateEof(caps), Maybe(cid))
                        inner.emit
                    }
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
                    case err: ErrPacket => Abort.fail(mkServerError(err, cid)(using frame))
                    case other          => Abort.fail(SqlException.Connection(s"Unexpected ping response: $other", frame))
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
      * advisory locks) without re-running the auth handshake. Use this when returning a connection to the pool with `resetOnRelease = true`
      * to guarantee that the next borrower sees a clean session.
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
        isolation: Maybe[SqlIsolationLevel],
        readOnly: Boolean
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        serverCapabilities.get.flatMap { caps =>
            val deprecateEof = (caps & Capabilities.CLIENT_DEPRECATE_EOF) != 0L
            MysqlTransactionExchange.begin(channel, deprecateEof, isolation, readOnly)
        }

    /** Commits the current transaction. */
    def commitTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        serverCapabilities.get.flatMap { caps =>
            val deprecateEof = (caps & Capabilities.CLIENT_DEPRECATE_EOF) != 0L
            MysqlTransactionExchange.commit(channel, deprecateEof)
        }

    /** Rolls back the current transaction. */
    def rollbackTransaction(using Frame): Unit < (Async & Abort[SqlException]) =
        serverCapabilities.get.flatMap { caps =>
            val deprecateEof = (caps & Capabilities.CLIENT_DEPRECATE_EOF) != 0L
            MysqlTransactionExchange.rollback(channel, deprecateEof)
        }

    /** Creates a savepoint with `name` inside the current transaction. */
    def savepointTransaction(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        serverCapabilities.get.flatMap { caps =>
            val deprecateEof = (caps & Capabilities.CLIENT_DEPRECATE_EOF) != 0L
            MysqlTransactionExchange.savepoint(channel, deprecateEof, name)
        }

    /** Releases (commits) the savepoint `name`. */
    def releaseSavepointTransaction(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        serverCapabilities.get.flatMap { caps =>
            val deprecateEof = (caps & Capabilities.CLIENT_DEPRECATE_EOF) != 0L
            MysqlTransactionExchange.releaseSavepoint(channel, deprecateEof, name)
        }

    /** Rolls back to savepoint `name` (the savepoint itself is preserved; the outer transaction continues). */
    def rollbackToSavepointTransaction(name: String)(using Frame): Unit < (Async & Abort[SqlException]) =
        serverCapabilities.get.flatMap { caps =>
            val deprecateEof = (caps & Capabilities.CLIENT_DEPRECATE_EOF) != 0L
            MysqlTransactionExchange.rollbackToSavepoint(channel, deprecateEof, name)
        }

    // --- Cancellation ---

    /** Cancels the query running on `this` connection by sending `KILL QUERY <connectionId>` on `cancelConn`.
      *
      * `cancelConn` must be a **separate** already-authenticated [[MysqlConnection]]. `cancelConn` is NOT closed or returned to any pool
      * by this method, the caller is responsible for its lifecycle.
      *
      * Returns `Unit` whether the query was running or had already completed (KILL is idempotent when the target is absent).
      *
      * @param cancelConn
      *   second authenticated MySQL connection used as the cancel vehicle
      */
    def cancelQuery(cancelConn: MysqlConnection)(using Frame): Unit < (Async & Abort[SqlException]) =
        connectionId.get.flatMap { targetId =>
            MysqlCancelExchange.kill(cancelConn, targetId)
        }

    /** Executes multiple statements sequentially on this connection, returning one [[SqlStatementResult]] per statement.
      *
      * Each statement is isolated: a per-statement server error is recorded as [[kyo.SqlStatementResult.Failure]] without aborting
      * subsequent statements. Connection-level errors (socket closed, panic) re-raise and abort the entire pipeline.
      *
      * MySQL does not support the PostgreSQL Sync-barrier batch-write protocol, so statements are executed one at a time in order using the
      * extended (binary) protocol ([[MysqlPipelineExchange.runOnConnection]]).
      *
      * @param stmts
      *   `(sql, params)` pairs in submission order
      * @return
      *   one [[SqlStatementResult]] per statement, in submission order
      */
    def pipelined(
        stmts: Chunk[(String, Chunk[BoundMysqlParam[?]])]
    )(using Frame): Chunk[kyo.SqlStatementResult] < (Async & Abort[SqlException]) =
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
                Async.timeoutWithError(gracePeriod, Result.Failure(SqlException.Connection("quit timed out", summon[Frame])))(quit())
            ).andThen(Sync.Unsafe.defer(channel.conn.close()))

    /** Gracefully closes the connection with a default 30-second grace period. Delegates to [[close(gracePeriod)]]. */
    def close(using Frame): Unit < (Async & Abort[SqlException]) =
        close(30.seconds)

    /** Closes the underlying TCP connection immediately without sending [[ComQuit]].
      *
      * Used by the pool `discard` callback (which runs in an `AllowUnsafe` context and cannot suspend for protocol shutdown) and by
      * `closeAll` shutdown paths where [[quit]] has already been sent. Prefer [[close]] for user-facing graceful shutdown.
      */
    def closeNow(using Frame): Unit < Sync =
        Sync.Unsafe.defer(channel.conn.close())

    private def mkServerError(err: ErrPacket, cid: Long)(using frame: Frame): SqlException.Server =
        SqlException.Server(
            err.sqlState,
            "ERROR",
            err.errorMessage,
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Map("code" -> err.errorCode.toString),
            Maybe.Absent,
            0,
            Maybe(cid),
            frame
        )

end MysqlConnection

object MysqlConnection:

    /** Default prepared-statement cache size per connection. */
    private val DefaultStmtCacheSize = 64

    /** Builds the per-connection prepared-statement cache with eviction wired into `pendingCloses`.
      *
      * A plain `Cache.init` silently drops evicted `MysqlPreparedStmt` values; the server-side
      * statement stays allocated for the life of the session (a bounded but real leak on any
      * connection that churns through more distinct SQL than the cache holds). Wiring `onEvict`,
      * `onExpire`, and `onRemove` into `closesRef` enqueues the evicted `stmtId` so the next
      * `drainPendingCloses` call flushes `COM_STMT_CLOSE` for the released statement.
      *
      * The callback is `(K, V) => Unit`, synchronous and invoked inline on the cache's Sync
      * sweep. It uses the AtomicRef's non-suspending `unsafe.updateAndGet`; the actual
      * `COM_STMT_CLOSE` write happens on the next extended-protocol request (which starts by
      * calling `drainPendingCloses`).
      */
    private[mysql] def mkStmtCache(
        closesRef: AtomicRef[Chunk[String]],
        maxSize: Int,
        ttl: Duration
    )(using Frame): Cache[String, MysqlPreparedStmt] < Sync =
        Sync.Unsafe.defer:
            val onEvict: (String, MysqlPreparedStmt) => Unit = (_, stmt) =>
                discard(closesRef.unsafe.updateAndGet(_ :+ stmt.stmtId.toString))
            Cache.Unsafe.init[String, MysqlPreparedStmt](
                maxSize = maxSize,
                expireAfterAccess = ttl,
                onEvict = onEvict,
                onExpire = onEvict,
                onRemove = onEvict
            ).safe

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
      *   optional password ([[Maybe.Absent]] = empty / "no password" sentinel)
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
        preparedStmtTtl: Duration
    )(using Frame): MysqlConnection < (Async & Abort[SqlException]) =
        Abort.run[kyo.net.NetException](Sync.Unsafe.defer(NetPlatform.transport.connect(
            host,
            port
        ).safe).flatMap(_.use(identity))).flatMap {
            case Result.Failure(_) =>
                Abort.fail(SqlException.Connection(s"Failed to connect to MySQL at $host:$port", summon[Frame]))
            case Result.Panic(t) =>
                java.lang.System.err.println(s"[kyo-sql] MysqlConnection.connect: panic: ${t.getMessage}")
                Abort.fail(SqlException.Connection(s"Failed to connect to MySQL at $host:$port: ${t.getMessage}", summon[Frame]))
            case Result.Success(conn) =>
                MysqlChannel(conn).flatMap { rawChannel =>
                    HandshakeExchange.run(rawChannel, user, password, db, host, port, tls, false).flatMap { result =>
                        // result.channel is the TLS-wrapped channel (or the original if no TLS).
                        val activeChannel = result.channel
                        val ttl           = if preparedStmtTtl == Duration.Infinity then Duration.Zero else preparedStmtTtl
                        for
                            connIdRef  <- AtomicRef.init(result.connectionId)
                            capsRef    <- AtomicRef.init(result.capabilities)
                            versionRef <- AtomicRef.init(result.serverVersion)
                            charsetRef <- AtomicRef.init(result.charset)
                            statusRef  <- AtomicRef.init(result.statusFlags)
                            closesRef  <- AtomicRef.init(Chunk.empty[String])
                            stmtCache  <- MysqlConnection.mkStmtCache(closesRef, preparedStmtCacheSize, ttl)
                        yield new MysqlConnection(
                            activeChannel,
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
                }
        }
    end connect

    /** Like [[connect]] but with an explicit [[TlsMode]] for opportunistic TLS (sslmode=prefer/allow).
      *
      * For `sslmode=prefer`: uses `preferFallback=true` in [[HandshakeExchange.run]], which makes the handshake fall back to plaintext if
      * the server does not advertise CLIENT_SSL (instead of failing).
      *
      * For `sslmode=allow`: connects plaintext first (tls=Absent); the caller is responsible for catching error 3159
      * (ER_SECURE_TRANSPORT_REQUIRED) and reconnecting with `tls=Present`.
      *
      * This method exists so [[kyo.internal.client.MySqlClientBackend]] can wire opportunistic TLS without changing the public [[connect]]
      * signature.
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
        preparedStmtTtl: Duration
    )(using Frame): MysqlConnection < (Async & Abort[SqlException]) =
        Abort.run[kyo.net.NetException](Sync.Unsafe.defer(NetPlatform.transport.connect(
            host,
            port
        ).safe).flatMap(_.use(identity))).flatMap {
            case Result.Failure(_) =>
                Abort.fail(SqlException.Connection(s"Failed to connect to MySQL at $host:$port", summon[Frame]))
            case Result.Panic(t) =>
                java.lang.System.err.println(s"[kyo-sql] MysqlConnection.connectWithMode: panic: ${t.getMessage}")
                Abort.fail(SqlException.Connection(s"Failed to connect to MySQL at $host:$port: ${t.getMessage}", summon[Frame]))
            case Result.Success(conn) =>
                MysqlChannel(conn).flatMap { rawChannel =>
                    val preferFallback = tlsMode == TlsMode.Prefer
                    val ttl            = if preparedStmtTtl == Duration.Infinity then Duration.Zero else preparedStmtTtl
                    HandshakeExchange.run(rawChannel, user, password, db, host, port, tls, preferFallback).flatMap { result =>
                        val activeChannel = result.channel
                        for
                            connIdRef  <- AtomicRef.init(result.connectionId)
                            capsRef    <- AtomicRef.init(result.capabilities)
                            versionRef <- AtomicRef.init(result.serverVersion)
                            charsetRef <- AtomicRef.init(result.charset)
                            statusRef  <- AtomicRef.init(result.statusFlags)
                            closesRef  <- AtomicRef.init(Chunk.empty[String])
                            stmtCache  <- MysqlConnection.mkStmtCache(closesRef, preparedStmtCacheSize, ttl)
                        yield new MysqlConnection(
                            activeChannel,
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
                closesRef  <- AtomicRef.init(Chunk.empty[String])
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
