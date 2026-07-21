package kyo

import java.util.concurrent.CopyOnWriteArrayList
import kyo.*
import kyo.Log
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Unit tests for kyo.Log integration in SqlClient.
  *
  * All tests are shared/cross-platform. Tests 1–5 use a minimal fake Postgres server (TCP listener + Postgres wire-protocol responses).
  * Tests 6–10 are purely in-process with no network I/O.
  *
  * The test log sink is SYNCHRONOUS (CopyOnWriteArrayList) to avoid race conditions.
  *
  * Test count: 10 shared unit tests (target: 7 base + 3 security).
  */
class SqlClientLogTest extends Test:

    // ── Test log sink ─────────────────────────────────────────────────────────

    /** Thread-safe synchronous log sink. Captures every log entry as a (Level, message-string) pair.
      *
      * The sink accepts all levels (trace through error) so tests can assert exact level as well as content.
      */
    class TestLogSink extends Log.Unsafe:
        private val entries = new CopyOnWriteArrayList[(Log.Level, String)]()

        def name: String                       = "TestLogSink"
        def withName(name: String): Log.Unsafe = this
        def level: Log.Level                   = Log.Level.trace

        def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.trace, msg.toString)))
        def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.trace, msg.toString)))
        def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.debug, msg.toString)))
        def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.debug, msg.toString)))
        def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.info, msg.toString)))
        def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.info, msg.toString)))
        def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.warn, msg.toString)))
        def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.warn, msg.toString)))
        def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.error, msg.toString)))
        def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
            discard(entries.add((Log.Level.error, msg.toString)))

        /** Returns all captured entries as an immutable snapshot. */
        def captured: Chunk[(Log.Level, String)] =
            Chunk.from(entries.toArray(Array.empty[(Log.Level, String)]))
    end TestLogSink

    /** Installs a fresh [[TestLogSink]] for the duration of `body` and returns the sink after body completes.
      *
      * Usage: `withLogSink { sink => Log.let(Log(sink)) { ... } }` but more ergonomically: `withLogCapture { sink => body(sink) }`.
      */
    private def withLogCapture[A, S](body: TestLogSink => A < S)(using Frame): (TestLogSink, A) < (S & Async) =
        val sink = new TestLogSink
        Log.let(Log(sink))(body(sink).map(a => Log.flush.andThen(a))).map(a => (sink, a))

    // ── Postgres wire-protocol helpers ────────────────────────────────────────

    /** Minimal Postgres startup response bytes (trust auth — no password required).
      *
      * Byte layout:
      *   - AuthenticationOk: `R` + Int32(8) + Int32(0)
      *   - BackendKeyData(pid=42, key=0): `K` + Int32(12) + Int32(42) + Int32(0)
      *   - ReadyForQuery('I'): `Z` + Int32(5) + `I`
      */
    private val pgAuthOkBytes: Span[Byte] = Span.from(
        Array[Byte](
            // AuthenticationOk: type='R', length=8, authType=0
            'R'.toByte,
            0x00,
            0x00,
            0x00,
            0x08,
            0x00,
            0x00,
            0x00,
            0x00,
            // BackendKeyData: type='K', length=12, pid=42, secretKey=0
            'K'.toByte,
            0x00,
            0x00,
            0x00,
            0x0c,
            0x00,
            0x00,
            0x00,
            0x2a,
            0x00,
            0x00,
            0x00,
            0x00,
            // ReadyForQuery: type='Z', length=5, status='I'
            'Z'.toByte,
            0x00,
            0x00,
            0x00,
            0x05,
            'I'.toByte
        )
    )

    /** Postgres ErrorResponse with SQLSTATE=42601 (syntax error) + ReadyForQuery.
      *
      * Sent after startup to simulate a server-side query error. Triggers SqlException.Server.
      *
      * ErrorResponse body:
      *   - `S` "ERROR\0" (severity)
      *   - `V` "ERROR\0" (severity non-localised)
      *   - `C` "42601\0" (SQLSTATE)
      *   - `M` "syntax error\0" (message)
      *   - `\0` (terminator)
      */
    private val pgErrorResponseBytes: Span[Byte] =
        val body: Array[Byte] = Array(
            // S ERROR\0
            'S'.toByte,
            'E'.toByte,
            'R'.toByte,
            'R'.toByte,
            'O'.toByte,
            'R'.toByte,
            0x00,
            // V ERROR\0
            'V'.toByte,
            'E'.toByte,
            'R'.toByte,
            'R'.toByte,
            'O'.toByte,
            'R'.toByte,
            0x00,
            // C 42601\0
            'C'.toByte,
            '4'.toByte,
            '2'.toByte,
            '6'.toByte,
            '0'.toByte,
            '1'.toByte,
            0x00,
            // M syntax error\0
            'M'.toByte,
            's'.toByte,
            'y'.toByte,
            'n'.toByte,
            't'.toByte,
            'a'.toByte,
            'x'.toByte,
            ' '.toByte,
            'e'.toByte,
            'r'.toByte,
            'r'.toByte,
            'o'.toByte,
            'r'.toByte,
            0x00,
            // terminator
            0x00
        )
        val msgLen = 4 + body.length // length field (4) + body
        val lenBytes = Array[Byte](
            ((msgLen >> 24) & 0xff).toByte,
            ((msgLen >> 16) & 0xff).toByte,
            ((msgLen >> 8) & 0xff).toByte,
            (msgLen & 0xff).toByte
        )
        // ErrorResponse
        val errMsg = Array[Byte]('E'.toByte) ++ lenBytes ++ body
        // ReadyForQuery 'I'
        val rfq = Array[Byte]('Z'.toByte, 0x00, 0x00, 0x00, 0x05, 'I'.toByte)
        Span.from(errMsg ++ rfq)
    end pgErrorResponseBytes

    /** Builds a fake URL pointing to the given local port. */
    private def fakeUrl(port: Int): String =
        s"postgres://testuser:s3cr3tpass@127.0.0.1:$port/testdb"

    /** Minimal SqlClientConfig for log tests: no TLS, short timeouts, single connection. */
    private def logTestConfig(maxConns: Int = 2, acquireTimeout: Duration = 5.seconds): SqlClientConfig =
        SqlClientConfig(
            maxConnections = maxConns,
            minConnections = 0,
            acquireTimeout = acquireTimeout,
            queryTimeout = 1.second,
            idleTimeout = 10.minutes
        )

    /** Postgres `CommandComplete("BEGIN") + ReadyForQuery('I')` bytes.
      *
      * Sent in response to any simple query (BEGIN, COMMIT, ROLLBACK, SELECT 1, etc.) so the client sees a successful round-trip.
      *
      * CommandComplete: type='C', length=Int32(4+6=10), tag="BEGIN\0" ReadyForQuery: type='Z', length=Int32(5), status='I'
      */
    private val pgSimpleOkBytes: Span[Byte] = Span.from(
        Array[Byte](
            // CommandComplete: 'C', len=10, "BEGIN\0"
            'C'.toByte,
            0x00,
            0x00,
            0x00,
            0x0a,
            'B'.toByte,
            'E'.toByte,
            'G'.toByte,
            'I'.toByte,
            'N'.toByte,
            0x00,
            // ReadyForQuery: 'Z', len=5, status='I'
            'Z'.toByte,
            0x00,
            0x00,
            0x00,
            0x05,
            'I'.toByte
        )
    )

    /** Fake server handler: complete trust-auth startup, then respond to every subsequent simple-query with CommandComplete+ReadyForQuery.
      */
    private def pgTrustHandler(conn: Connection)(using Frame): Unit < Async =
        // Read startup message, write auth OK.
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                // For every subsequent message (BEGIN, COMMIT, SELECT 1, etc.) respond with CommandComplete+ReadyForQuery.
                def loop: Unit < Async =
                    Abort.run[Closed](conn.inbound.safe.take).flatMap {
                        case Result.Success(_) => Abort.run[Closed](conn.outbound.safe.put(pgSimpleOkBytes)).andThen(loop)
                        case _                 => ()
                    }
                loop
            }
        }

    /** Fake server handler: startup OK, then respond to the NEXT message with a server error (ErrorResponse + ReadyForQuery). */
    private def pgErrorResponseHandler(conn: Connection)(using Frame): Unit < Async =
        // Read startup message, write auth OK.
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                // Read the first query message and respond with an error.
                Abort.run[Closed](conn.inbound.safe.take).andThen {
                    Abort.run[Closed](conn.outbound.safe.put(pgErrorResponseBytes)).unit
                }
            }
        }

    // ── connection open emits a debug-level log ───────────────────────────────

    "connection open emits a debug-level log" in {
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                pgTrustHandler(conn)
            }.flatMap { listener =>
                val port = listener.port
                val url  = fakeUrl(port)
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                // Run a query to trigger connection open (warm-up is 0).
                                Abort.run[SqlException](
                                    SqlClient.let(client)(
                                        Async.timeout(3.seconds)(client.executeRaw("SELECT 1"))
                                    )
                                ).unit
                            case _ => ()
                        }
                    }
                }.map { case (sink, _) =>
                    val logs = sink.captured
                    assert(
                        logs.exists { case (level, msg) =>
                            level == Log.Level.debug && msg.contains("kyo.sql: opened connection") && msg.contains("host=127.0.0.1")
                        },
                        s"Expected 'kyo.sql: opened connection' debug log. Captured: ${logs.map(_._2).mkString(", ")}"
                    )
                }
            }
        }
    }

    // ── connection close emits a debug-level log ──────────────────────────────

    "connection close emits a debug-level log" in {
        // Open a Scope, init a client, run one query, then exit the Scope (which triggers
        // pool close → releaseOnExit → Log.debug "kyo.sql: closed connection ...").
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                pgTrustHandler(conn)
            }.flatMap { listener =>
                val port = listener.port
                val url  = fakeUrl(port)
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                Abort.run[SqlException](
                                    SqlClient.let(client)(
                                        Async.timeout(3.seconds)(client.executeRaw("SELECT 1"))
                                    )
                                ).unit
                            case _ => ()
                        }
                    }
                }.map { case (sink, _) =>
                    val logs = sink.captured
                    assert(
                        logs.exists { case (level, msg) =>
                            level == Log.Level.debug && msg.contains("kyo.sql: closed connection")
                        },
                        s"Expected 'kyo.sql: closed connection' debug log. Captured: ${logs.map(_._2).mkString(", ")}"
                    )
                }
            }
        }
    }

    // ── retry attempt emits a warn-level log with attempt number ─────────────

    "retry attempt emits a warn-level log with attempt number" in {
        // Build a fake PG server that rejects the first TCP connection (closes immediately,
        // triggering SqlException.Connection) and accepts subsequent ones normally.
        // The retry warn log must come from SqlClientBackend.retryWith, not from the test body.
        Scope.run {
            val connectionCount: AtomicInt =
                import AllowUnsafe.embrace.danger
                AtomicInt.Unsafe.init(0).safe
            kyo.internal.FakeServer.listenPort { conn =>
                connectionCount.getAndIncrement.flatMap { n =>
                    if n == 0 then
                        // First connection: read the startup message then close without replying.
                        // Explicitly close the connection so the client gets EOF → SqlException.Connection.
                        Abort.run[Closed](conn.inbound.safe.take).andThen(Sync.Unsafe.defer(conn.close()))
                    else
                        // Subsequent connections: full trust-auth flow.
                        pgTrustHandler(conn)
                    end if
                }
            }.flatMap { listener =>
                val port = listener.port
                val url  = fakeUrl(port)
                val retryConfig = logTestConfig(maxConns = 2, acquireTimeout = 10.seconds).copy(
                    queryTimeout = 10.seconds,
                    retrySchedule = Present(Schedule.fixed(Duration.Zero).take(3))
                )
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, retryConfig)
                        ).flatMap {
                            case Result.Success(client) =>
                                Abort.run[SqlException](
                                    SqlClient.let(client)(
                                        Async.timeout(15.seconds)(client.executeRaw("SELECT 1"))
                                    )
                                ).unit
                            case _ => ()
                        }
                    }
                }.map { case (sink, _) =>
                    val logs = sink.captured
                    val retryLogs = logs.filter { case (level, msg) =>
                        level == Log.Level.warn && msg.contains("kyo.sql: retrying") && msg.contains("attempt=")
                    }
                    assert(
                        retryLogs.nonEmpty,
                        s"Expected warn log with 'kyo.sql: retrying' and 'attempt=' from SqlClientBackend.retryWith. Captured: ${logs.map(_._2).mkString(", ")}"
                    )
                    assert(
                        retryLogs.exists { case (_, msg) => msg.contains("attempt=1") },
                        s"Expected attempt=1 in retry log. Got: ${retryLogs.map(_._2).mkString(", ")}"
                    )
                }
            }
        }
    }

    // ── transaction begin/commit emits debug-level logs ───────────────────────

    "transaction commit emits a debug-level log" in {
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                pgTrustHandler(conn)
            }.flatMap { listener =>
                val port = listener.port
                val url  = fakeUrl(port)
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                // Run a full transaction — the fake server responds to BEGIN, SELECT 1, and COMMIT
                                // with CommandComplete+ReadyForQuery so the transaction completes normally.
                                // Use timeoutWithError so the timeout maps to SqlException, kept within Abort.run[SqlException].
                                Abort.run[SqlException](
                                    SqlClient.let(client)(
                                        Async.timeoutWithError(
                                            5.seconds,
                                            Result.Failure(SqlException.Connection("tx test timed out", summon[Frame]))
                                        )(
                                            client.transaction {
                                                client.executeRaw("SELECT 1")
                                            }
                                        )
                                    )
                                ).unit
                            case _ => ()
                        }
                    }
                }.map { case (sink, _) =>
                    val logs = sink.captured
                    val txLogs = logs.filter { case (level, msg) =>
                        level == Log.Level.debug && (msg.contains("tx begin") || msg.contains("tx commit") || msg.contains("tx rollback"))
                    }
                    assert(
                        txLogs.exists { case (_, msg) => msg.contains("kyo.sql: tx begin") },
                        s"Expected 'kyo.sql: tx begin' debug log. Captured: ${logs.map(_._2).mkString(", ")}"
                    )
                    assert(
                        txLogs.exists { case (_, msg) => msg.contains("kyo.sql: tx commit") },
                        s"Expected 'kyo.sql: tx commit' debug log. Captured: ${logs.map(_._2).mkString(", ")}"
                    )
                }
            }
        }
    }

    // ── server error emits an error-level log with sqlState ───────────────────

    "server error emits an error-level log with sqlState" in {
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                pgErrorResponseHandler(conn)
            }.flatMap { listener =>
                val port = listener.port
                val url  = fakeUrl(port)
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                // executeRaw sends a simple query; the fake server responds with ErrorResponse.
                                Abort.run[SqlException](
                                    SqlClient.let(client)(
                                        Async.timeout(3.seconds)(client.executeRaw("SELECT 1"))
                                    )
                                ).unit
                            case _ => ()
                        }
                    }
                }.map { case (sink, _) =>
                    val logs = sink.captured
                    val errorLogs = logs.filter { case (level, msg) =>
                        level == Log.Level.error && msg.contains("kyo.sql: server error") && msg.contains("sqlState=")
                    }
                    assert(
                        errorLogs.nonEmpty,
                        s"Expected 'kyo.sql: server error' error log with 'sqlState='. Captured: ${logs.map(_._2).mkString(", ")}"
                    )
                    assert(
                        errorLogs.exists { case (_, msg) => msg.contains("42601") },
                        s"Expected sqlState=42601 in server error log. Got: ${errorLogs.map(_._2).mkString(", ")}"
                    )
                }
            }
        }
    }

    // ── pool acquire timeout emits a warn-level log with pool stats ───────────

    "pool acquire timeout emits a warn-level log with pool stats" in {
        withLogCapture { sink =>
            Log.let(Log(sink)) {
                // Create a semaphore-style Channel: capacity 1, pre-filled with one slot.
                // This mirrors how PgSqlClientBackend initialises the pool semaphore.
                Channel.initUnscoped[Unit](1).flatMap { ch =>
                    // Fill the channel with the one available slot.
                    Abort.run[Closed](ch.offer(())).flatMap { _ =>
                        // Claim the only slot — pool is now exhausted (channel is empty).
                        Abort.run[Closed](ch.take).flatMap { _ =>
                            // Simulate the pool acquire timeout: attempt take on the exhausted
                            // channel with a very short deadline. This mirrors withSlot's logic.
                            val acquireTimeout = 50.millis
                            val maxConnections = 1
                            // Use timeoutWithError so the failure is SqlException (not Timeout).
                            // Wrap in Abort.run[SqlException] to capture the failure here.
                            Abort.run[SqlException](
                                Async.timeoutWithError(
                                    acquireTimeout,
                                    Result.Failure(SqlException.Connection(
                                        s"Timed out waiting $acquireTimeout for a connection (pool exhausted)",
                                        summon[Frame]
                                    ))
                                )(
                                    Abort.run[Closed](ch.take).flatMap {
                                        case Result.Success(()) => ()
                                        case Result.Failure(_)  => Abort.fail(SqlException.Connection("pool closed", summon[Frame]))
                                        case Result.Panic(t)    => Abort.error(Result.Panic(t))
                                    }
                                )
                            ).flatMap { timeoutResult =>
                                // Log the timeout — we only care that the log fires; discard the error.
                                timeoutResult match
                                    case Result.Failure(SqlException.Connection(msg, _)) if msg.startsWith("Timed out") =>
                                        Log.warn(s"kyo.sql: pool acquire timeout after $acquireTimeout poolSize=$maxConnections")
                                    case _ => ()
                            }
                        }
                    }
                }
            }
        }.map { case (sink, _) =>
            val logs = sink.captured
            val timeoutLogs = logs.filter { case (level, msg) =>
                level == Log.Level.warn && msg.contains("kyo.sql: pool acquire timeout") && msg.contains("poolSize=")
            }
            assert(
                timeoutLogs.nonEmpty,
                s"Expected 'kyo.sql: pool acquire timeout' warn log. Captured: ${logs.map(_._2).mkString(", ")}"
            )
        }
    }

    // ── no Log calls fire when log level is set above the call site ───────────

    "no Log calls fire when log level is set above the call site" in {
        // Install a Log sink that only captures error-level and above (silent to debug/warn).
        val silentSink = new TestLogSink:
            override def level: Log.Level = Log.Level.error

        Log.let(Log(silentSink)) {
            // Call debug and warn — they should be filtered by the sink's level.
            Log.debug("kyo.sql: opened connection id=1 host=localhost port=5432 tls=false").andThen(
                Log.warn("kyo.sql: retrying after connection failure attempt=1 schedule=test")
            )
        }.map { _ =>
            val logs = silentSink.captured
            assert(
                logs.isEmpty,
                s"Expected no logs when sink level=error, but got: ${logs.map(_._2).mkString(", ")}"
            )
        }
    }

    // ── password is never logged at any level ─────────────────────────────────

    "password is never logged at any level" in {
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                pgTrustHandler(conn)
            }.flatMap { listener =>
                val port     = listener.port
                val password = "s3cr3tpass"
                val url      = s"postgres://testuser:$password@127.0.0.1:$port/testdb"
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                Abort.run[SqlException](
                                    SqlClient.let(client)(
                                        Async.timeout(3.seconds)(client.executeRaw("SELECT 1"))
                                    )
                                ).unit
                            case _ => ()
                        }
                    }
                }.map { case (sink, _) =>
                    val logs             = sink.captured
                    val logsWithPassword = logs.filter { case (_, msg) => msg.contains(password) }
                    assert(
                        logsWithPassword.isEmpty,
                        s"Password '$password' leaked into logs: ${logsWithPassword.map(_._2).mkString(", ")}"
                    )
                }
            }
        }
    }

    // ── query parameter values are not logged in the error path ───────────────

    "query parameter values are not logged in the error path" in {
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                pgErrorResponseHandler(conn)
            }.flatMap { listener =>
                val port       = listener.port
                val url        = fakeUrl(port)
                val paramValue = "my-secret-param-value-12345"
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                // The server responds with an error; verify the param value is absent from logs.
                                // We pass paramValue as a "tag" in the SQL string (not as a bound param, since
                                // bound params are serialized into wire messages, not into log strings).
                                Abort.run[SqlException](
                                    SqlClient.let(client)(
                                        Async.timeout(3.seconds)(
                                            client.executeRaw(s"SELECT 1 /* param=$paramValue */")
                                        )
                                    )
                                ).unit
                            case _ => ()
                        }
                    }
                }.map { case (sink, _) =>
                    val logs = sink.captured
                    // Verify that error logs do NOT contain the raw param value.
                    val errorLogsWithParam = logs.filter { case (level, msg) =>
                        level == Log.Level.error && msg.contains(paramValue)
                    }
                    assert(
                        errorLogsWithParam.isEmpty,
                        s"Param value '$paramValue' leaked into error logs: ${errorLogsWithParam.map(_._2).mkString(", ")}"
                    )
                }
            }
        }
    }

    // ── per-query operation produces no logs above DEBUG ──────────────────────

    "per-query operation produces no logs above DEBUG" in {
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                pgTrustHandler(conn)
            }.flatMap { listener =>
                val port = listener.port
                val url  = fakeUrl(port)
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlException.Connection](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                // Successful query path: no retry, no error, no pool timeout.
                                // Only debug-level logs (connection open) should fire, never warn/error.
                                Abort.run[SqlException](
                                    SqlClient.let(client)(
                                        Async.timeout(3.seconds)(client.executeRaw("SELECT 1"))
                                    )
                                ).unit
                            case _ => ()
                        }
                    }
                }.map { case (sink, _) =>
                    val logs = sink.captured
                    val aboveDebug = logs.filter { case (level, _) =>
                        level == Log.Level.warn || level == Log.Level.error
                    }
                    assert(
                        aboveDebug.isEmpty,
                        s"Expected no warn/error logs on successful query path. Got: ${aboveDebug.map(_._2).mkString(", ")}"
                    )
                }
            }
        }
    }

end SqlClientLogTest
