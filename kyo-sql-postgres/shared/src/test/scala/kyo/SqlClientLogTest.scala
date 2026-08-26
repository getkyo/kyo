package kyo

import java.util.concurrent.CopyOnWriteArrayList
import kyo.*
import kyo.Log
import kyo.Test
import kyo.net.Connection
import kyo.net.NetPlatform

/** Unit tests for kyo.Log integration in SqlClient.
  *
  * All tests are shared/cross-platform. The leaves that need a socket drive a minimal fake Postgres server (a TCP listener plus
  * wire-protocol responses); the rest are purely in-process with no network I/O.
  *
  * The test log sink is SYNCHRONOUS (CopyOnWriteArrayList) to avoid race conditions.
  */
class SqlClientLogTest extends SqlContainerTest:

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

    /** Minimal Postgres startup response bytes (trust auth, no password required).
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
      * Sent after startup to simulate a server-side query error. Triggers SqlServerException.
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

    /** Minimal SqlConfig for log tests: no TLS, short timeouts, single connection. */
    private def logTestConfig(maxConns: Int = 2, acquireTimeout: Duration = 5.seconds): SqlConfig =
        SqlConfig(
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
                        Abort.run[SqlConnectionException](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                // Run a query to trigger connection open (warm-up is 0).
                                Abort.run[SqlException](
                                    DB.run(client)(
                                        client.executeRaw("SELECT 1")
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

    // ── a resolved lease emits a debug-level log naming the connection ────────

    "a resolved lease emits a debug-level log naming the connection" in {
        // The line this asserts comes from the statement's own lease ending, where the connection goes back into
        // the ring; `closeAll` emits no line here. That release logs "pooled connection", distinct from the
        // destroy branch's "closed connection ... reason=released", which is the wording for a connection that
        // does not stay alive. This leaf pins the "pooled connection" wording so the two cannot be confused.
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                pgTrustHandler(conn)
            }.flatMap { listener =>
                val port = listener.port
                val url  = fakeUrl(port)
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlConnectionException](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                Abort.run[SqlException](
                                    DB.run(client)(
                                        client.executeRaw("SELECT 1")
                                    )
                                ).unit
                            case _ => ()
                        }
                    }
                }.map { case (sink, _) =>
                    val logs = sink.captured
                    assert(
                        logs.exists { case (level, msg) =>
                            level == Log.Level.debug && msg.contains("kyo.sql: pooled connection id=")
                        },
                        s"Expected 'kyo.sql: pooled connection id=' debug log. Captured: ${logs.map(_._2).mkString(", ")}"
                    )
                }
            }
        }
    }

    // ── retry attempt emits a warn-level log with attempt number ─────────────

    "retry attempt emits a warn-level log with attempt number" in {
        // Build a fake PG server that rejects the first TCP connection (closes immediately,
        // triggering SqlConnectionException) and accepts subsequent ones normally.
        // The retry warn log must come from SqlConnectionPool's retry path, not from the test body.
        Scope.run {
            AtomicInt.initWith(0) { connectionCount =>
                kyo.internal.FakeServer.listenPort { conn =>
                    connectionCount.getAndIncrement.flatMap { n =>
                        if n == 0 then
                            // First connection: read the startup message then close without replying.
                            // Explicitly close the connection so the client gets EOF → SqlConnectionException.
                            // Unsafe: kyo-net Connection.close is unsafe-tier; closes the raw socket without suspending.
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
                            Abort.run[SqlConnectionException](
                                SqlClient.init(url, retryConfig)
                            ).flatMap {
                                case Result.Success(client) =>
                                    Abort.run[SqlException](
                                        DB.run(client)(
                                            client.executeRaw("SELECT 1")
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
                            s"Expected warn log with 'kyo.sql: retrying' and 'attempt=' from SqlConnectionPool's retry path. Captured: ${logs.map(_._2).mkString(", ")}"
                        )
                        assert(
                            retryLogs.exists { case (_, msg) => msg.contains("attempt=1") },
                            s"Expected attempt=1 in retry log. Got: ${retryLogs.map(_._2).mkString(", ")}"
                        )
                    }
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
                        Abort.run[SqlConnectionException](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                // Run a full transaction, the fake server responds to BEGIN, SELECT 1, and COMMIT
                                // with CommandComplete+ReadyForQuery so the transaction completes normally.
                                // Use timeoutWithError so the timeout maps to SqlException, kept within Abort.run[SqlException].
                                Abort.run[SqlException](
                                    DB.run(client)(
                                        Async.timeoutWithError(
                                            5.seconds,
                                            Result.Failure(SqlConnectionQueryTimeoutException(5.seconds))
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

    // ── server error is reported to the caller, and logged below ERROR ────────

    /** A server error the caller is handed as a typed value is not an error the library reports on its own initiative.
      *
      * This leaf used to require the ERROR level. What it is really about is the message being available with its sqlState, which DEBUG
      * carries just as well; the level is what decided whether a tool behaving correctly filled an operator's dashboard. A tool that runs
      * user-written SQL gets a syntax error back from the server as its ordinary answer, and on a stdio transport anything the library
      * writes uninvited is a candidate for corrupting the channel.
      */
    "server error is logged below ERROR and still carries its sqlState" in {
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                pgErrorResponseHandler(conn)
            }.flatMap { listener =>
                val port = listener.port
                val url  = fakeUrl(port)
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlConnectionException](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                // executeRaw sends a simple query; the fake server responds with ErrorResponse.
                                Abort.run[SqlException](
                                    DB.run(client)(
                                        client.executeRaw("SELECT 1")
                                    )
                                ).unit
                            case _ => ()
                        }
                    }
                }.map { case (sink, _) =>
                    val logs = sink.captured
                    val serverErrorLogs = logs.filter { case (_, msg) =>
                        msg.contains("kyo.sql: server error") && msg.contains("sqlState=")
                    }
                    assert(
                        serverErrorLogs.nonEmpty,
                        s"Expected 'kyo.sql: server error' log with 'sqlState='. Captured: ${logs.map(_._2).mkString(", ")}"
                    )
                    assert(
                        serverErrorLogs.exists { case (_, msg) => msg.contains("42601") },
                        s"Expected sqlState=42601 in the server error log. Got: ${serverErrorLogs.map(_._2).mkString(", ")}"
                    )
                    assert(
                        serverErrorLogs.forall { case (level, _) => level == Log.Level.debug },
                        s"a failure handed back to the caller must not be logged at ERROR. Got: ${serverErrorLogs.mkString(", ")}"
                    )
                }
            }
        }
    }

    // ── pool acquire timeout emits a warn-level log naming the pool size ──────

    /** Drives the real pool to exhaustion and asserts the warn line SqlConnectionPool emits when a caller gives up waiting.
      *
      * Nothing here writes a log or builds an exception, and that is what makes the leaf able to fail. A leaf that claimed its own slot and
      * emitted the `Log.warn` it then matched would be indistinguishable to the sink from the production line, which is byte-identical at
      * `SqlConnectionPool.scala:118` and `:333`, and it would pass with the pool's logging deleted and with the pool deleted. Instead
      * `maxConnections = 1` plus a transaction holding the only connection makes the second statement's `takeSlot` the thing that times
      * out, and its abort value is asserted alongside the line.
      *
      * The fake server is the fixture rather than a container, for the reason the rest of this file uses one: the pool's timeout is decided
      * by the slot channel, not by the server, so a real database adds a dependency and a JVM-only leaf without adding a witness.
      */
    "pool acquire timeout emits a warn-level log naming the exhausted pool's size" in {
        Scope.run {
            kyo.internal.FakeServer.listenPort { conn =>
                pgTrustHandler(conn)
            }.flatMap { listener =>
                val url = fakeUrl(listener.port)
                // One connection, so a single held lease exhausts the pool, and a short acquireTimeout so the
                // second caller reaches takeSlot's deadline rather than this leaf's own timeout.
                val config = logTestConfig(maxConns = 1, acquireTimeout = 200.millis)
                withLogCapture { sink =>
                    Log.let(Log(sink)) {
                        Abort.run[SqlConnectionException](SqlClient.init(url, config)).flatMap {
                            case Result.Success(client) =>
                                Latch.initWith(1) { held =>
                                    Latch.initWith(1) { release =>
                                        // The transaction pins the pool's only connection for as long as its body
                                        // runs, so the slot is taken by the pool itself.
                                        Fiber.initUnscoped(
                                            Abort.run[Any](
                                                DB.run(client)(client.transaction(held.release.andThen(release.await)))
                                            ).unit
                                        ).flatMap { txFiber =>
                                            held.await.andThen {
                                                Abort.run[SqlException](
                                                    DB.run(client)(client.executeRaw("SELECT 1"))
                                                ).flatMap { blocked =>
                                                    release.release.andThen(txFiber.get).andThen(blocked)
                                                }
                                            }
                                        }
                                    }
                                }
                            case other =>
                                fail(s"the fake server must accept the pool's first connection, got: $other")
                        }
                    }
                }.map { case (sink, blocked) =>
                    // First: the pool raised the timeout, not this test. Without this the log assertion below
                    // could be satisfied by any line the fixture happened to write.
                    blocked match
                        case Result.Failure(_: SqlConnectionAcquireTimeoutException) => ()
                        case other =>
                            fail(s"the second statement must abort with SqlConnectionAcquireTimeoutException from takeSlot, got: $other")
                    end match
                    val logs = sink.captured
                    // poolSize by value, not by presence: both production sites interpolate config.maxConnections,
                    // so the 1 this leaf configured is what must appear.
                    assert(
                        logs.exists { case (level, msg) =>
                            level == Log.Level.warn && msg.contains("kyo.sql: pool acquire timeout") && msg.contains("poolSize=1")
                        },
                        s"Expected a warn 'kyo.sql: pool acquire timeout ... poolSize=1'. Captured: ${logs.map(_._2).mkString(", ")}"
                    )
                }
            }
        }
    }

    // ── no Log calls fire when log level is set above the call site ───────────

    "no Log calls fire when log level is set above the call site" in {
        // Install a Log sink that only captures error-level and above (silent to debug/warn).
        val silentSink = new TestLogSink:
            override def level: Log.Level = Log.Level.error

        Log.let(Log(silentSink)) {
            // Call debug and warn, they should be filtered by the sink's level.
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
                        Abort.run[SqlConnectionException](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                Abort.run[SqlException](
                                    DB.run(client)(
                                        client.executeRaw("SELECT 1")
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
                        Abort.run[SqlConnectionException](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                // The server responds with an error; verify the param value is absent from logs.
                                // We pass paramValue as a "tag" in the SQL string (not as a bound param, since
                                // bound params are serialized into wire messages, not into log strings).
                                Abort.run[SqlException](
                                    DB.run(client)(
                                        client.executeRaw(s"SELECT 1 /* param=$paramValue */")
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
                        Abort.run[SqlConnectionException](
                            SqlClient.init(url, logTestConfig(maxConns = 2, acquireTimeout = 5.seconds))
                        ).flatMap {
                            case Result.Success(client) =>
                                // Successful query path: no retry, no error, no pool timeout.
                                // Only debug-level logs (connection open) should fire, never warn/error.
                                Abort.run[SqlException](
                                    DB.run(client)(
                                        client.executeRaw("SELECT 1")
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
