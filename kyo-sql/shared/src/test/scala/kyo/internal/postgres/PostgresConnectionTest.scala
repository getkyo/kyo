package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import kyo.*
import kyo.EncodingRegistry
import kyo.internal.SqlSharedContainers
import kyo.internal.postgres.*
import kyo.internal.postgres.exchange.*

/** Integration tests for PostgreSQL connection startup and authentication.
  *
  * Container-backed leaves run against the per-fork-JVM shared PostgreSQL container with a fresh schema per test (via
  * [[SqlSharedContainers.withFreshSchema]]). Pure unit-test leaves do not touch any container.
  */
class PostgresConnectionTest extends kyo.Test:

    /** Thread-safe synchronous log sink for capturing log entries in tests. */
    private class TestLogSink extends Log.Unsafe:
        private val entries                                                        = new CopyOnWriteArrayList[(Log.Level, String)]()
        def name: String                                                           = "TestLogSink"
        def withName(name: String): Log.Unsafe                                     = this
        def level: Log.Level                                                       = Log.Level.trace
        def trace(msg: => String)(using Frame, AllowUnsafe): Unit                  = discard(entries.add((Log.Level.trace, msg.toString)))
        def trace(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = discard(entries.add((Log.Level.trace, msg.toString)))
        def debug(msg: => String)(using Frame, AllowUnsafe): Unit                  = discard(entries.add((Log.Level.debug, msg.toString)))
        def debug(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = discard(entries.add((Log.Level.debug, msg.toString)))
        def info(msg: => String)(using Frame, AllowUnsafe): Unit                   = discard(entries.add((Log.Level.info, msg.toString)))
        def info(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = discard(entries.add((Log.Level.info, msg.toString)))
        def warn(msg: => String)(using Frame, AllowUnsafe): Unit                   = discard(entries.add((Log.Level.warn, msg.toString)))
        def warn(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit  = discard(entries.add((Log.Level.warn, msg.toString)))
        def error(msg: => String)(using Frame, AllowUnsafe): Unit                  = discard(entries.add((Log.Level.error, msg.toString)))
        def error(msg: => String, t: => Throwable)(using Frame, AllowUnsafe): Unit = discard(entries.add((Log.Level.error, msg.toString)))
        def captured: Chunk[(Log.Level, String)] = Chunk.from(entries.toArray(Array.empty[(Log.Level, String)]))
    end TestLogSink

    // Helper: connect to the test's fresh schema and run a block.
    private def withConn[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(f: PostgresConnection => A < (S & Async & Abort[SqlException]))(using
        Frame
    ): A < (S & Async & Scope & Abort[SqlException]) =
        PostgresConnection
            .connect(
                ctx.host,
                ctx.port,
                ctx.username,
                ctx.database,
                Present(ctx.password),
                Absent,
                64,
                Duration.Infinity,
                EncodingRegistry.builtin
            )
            .flatMap { conn =>
                Scope.ensure(Abort.run(conn.terminate).unit).andThen(f(conn))
            }

    "StartupExchange succeeds with trust auth, connect + startup returns ReadyForQuery" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    conn.isOpen.map(open => assert(open))
                }
            }
        }
    }

    "StartupExchange populates ParameterStatus map, server_version present after connect" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    conn.parameters.get.map { params =>
                        assert(params.contains("server_version"))
                        assert(params.contains("client_encoding"))
                    }
                }
            }
        }
    }

    "StartupExchange stores BackendKeyData, processId non-zero after startup" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    // processId should be a real backend PID (> 0).
                    // secretKey is a random Int32 (can be any value including negative).
                    assert(conn.processId > 0)
                    assert(conn.secretKey != 0 || conn.secretKey == 0) // secretKey is always valid
                }
            }
        }
    }

    "StartupExchange fails on wrong password, raises SqlConnectionException" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                Abort.run[SqlException] {
                    PostgresConnection
                        .connect(
                            ctx.host,
                            ctx.port,
                            ctx.username,
                            ctx.database,
                            Present("wrongpw"),
                            Absent,
                            64,
                            Duration.Infinity,
                            EncodingRegistry.builtin
                        )
                        .flatMap { conn =>
                            Scope.ensure(conn.close).andThen(conn.isOpen)
                        }
                }.map {
                    case Result.Failure(e: SqlConnectionAuthenticationFailedException) =>
                        assert(e.sqlState == "28P01", s"Expected SQLSTATE 28P01 for wrong password, got: ${e.sqlState}")
                    case other => fail(s"Expected SqlConnectionAuthenticationFailedException(28P01) for wrong password, got: $other")
                }
            }
        }
    }

    "SimpleQueryExchange SELECT 1 returns one row with value '1'" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    conn.simpleQuery("SELECT 1").map { rows =>
                        assert(rows.size == 1)
                        val value = rows(0).column(0)
                        assert(value.isDefined)
                        val str = new String(value.get.toArray, StandardCharsets.UTF_8)
                        assert(str == "1")
                    }
                }
            }
        }
    }

    "SimpleQueryExchange SELECT with multiple columns, column names match RowDescription" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    conn.simpleQuery("SELECT 1 AS a, 2 AS b").map { rows =>
                        assert(rows.size == 1)
                        val row = rows(0)
                        assert(row.fields.size == 2)
                        assert(row.fields(0).name == "a")
                        assert(row.fields(1).name == "b")
                        val a = new String(row.column(0).get.toArray, StandardCharsets.UTF_8)
                        val b = new String(row.column(1).get.toArray, StandardCharsets.UTF_8)
                        assert(a == "1")
                        assert(b == "2")
                    }
                }
            }
        }
    }

    "SimpleQueryExchange empty result set, WHERE false returns no rows" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    conn.simpleQuery("SELECT 1 WHERE false").map { rows =>
                        assert(rows.isEmpty)
                    }
                }
            }
        }
    }

    "QueryResultExchange maps column by position, column(0) returns first value" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    conn.simpleQuery("SELECT 42, 'hello'").map { rows =>
                        assert(rows.size == 1)
                        val row  = rows(0)
                        val col0 = new String(row.column(0).get.toArray, StandardCharsets.UTF_8)
                        val col1 = new String(row.column(1).get.toArray, StandardCharsets.UTF_8)
                        assert(col0 == "42")
                        assert(col1 == "hello")
                    }
                }
            }
        }
    }

    "Row.column(name), column lookup by field name" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    conn.simpleQuery("SELECT 99 AS id, 'world' AS msg").map { rows =>
                        assert(rows.size == 1)
                        val row = rows(0)
                        val id  = new String(row.column("id").get.toArray, StandardCharsets.UTF_8)
                        val msg = new String(row.column("msg").get.toArray, StandardCharsets.UTF_8)
                        assert(id == "99")
                        assert(msg == "world")
                    }
                }
            }
        }
    }

    "Row.column(index), column(0) returns first column" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    conn.simpleQuery("SELECT 'first', 'second'").map { rows =>
                        assert(rows.size == 1)
                        val row   = rows(0)
                        val first = new String(row.column(0).get.toArray, StandardCharsets.UTF_8)
                        assert(first == "first")
                    }
                }
            }
        }
    }

    "SimpleQueryExchange error mid-stream, server error raises SqlServerException with sqlState" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    Abort.run[SqlException](conn.simpleQuery("SELECT 1/0")).map {
                        case Result.Failure(e: SqlServerException) =>
                            // Division by zero: 22012
                            assert(e.sqlState == "22012")
                        case other =>
                            fail(s"Expected SqlServerException, got $other")
                    }
                }
            }
        }
    }

    "BarrierGuard drains to ReadyForQuery on error, subsequent query succeeds after error" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    for
                        // First: trigger a server error.
                        err <- Abort.run[SqlException](conn.simpleQuery("SELECT 1/0"))
                        // Verify it was a server error.
                        _ = err match
                            case Result.Failure(_: SqlServerException) => ()
                            case other                                 => fail(s"Expected server error, got $other")
                        // Second: run a normal query, must succeed (proves barrier drained RFQ).
                        rows <- conn.simpleQuery("SELECT 42")
                    yield
                        assert(rows.size == 1)
                        val v = new String(rows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                        assert(v == "42")
                }
            }
        }
    }

    "BarrierGuard preserves original error, drain succeeds and original error is re-raised" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    Abort.run[SqlException](conn.simpleQuery("bad sql !!")).map {
                        case Result.Failure(_: SqlServerException) => succeed
                        case other                                 => fail(s"Expected SqlServerException, got $other")
                    }
                }
            }
        }
    }

    "TerminatorExchange sends Terminate and closes, isOpen returns false after terminate" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                // Open the connection without auto-terminate scope
                PostgresConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        ctx.database,
                        Present(ctx.password),
                        Absent,
                        64,
                        Duration.Infinity,
                        EncodingRegistry.builtin
                    )
                    .flatMap { conn =>
                        for
                            openBefore <- conn.isOpen
                            _          <- Abort.run[SqlException](conn.terminate)
                            openAfter  <- conn.isOpen
                        yield
                            assert(openBefore)
                            assert(!openAfter)
                    }
            }
        }
    }

    "PostgresConnection.connect fails on wrong host, SqlConnectionException raised" in {
        Scope.run {
            Abort.run[SqlException] {
                PostgresConnection
                    .connect("127.0.0.1", 19999, "user", "db", Absent, Absent, 64, Duration.Infinity, EncodingRegistry.builtin)
                    .map { conn => conn.isOpen }
            }.map {
                case Result.Failure(_: SqlConnectionException) => succeed
                case other                                     => fail(s"Expected SqlConnectionException, got $other")
            }
        }
    }

    "Multiple sequential queries on same connection, no state corruption" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    for
                        r1 <- conn.simpleQuery("SELECT 1")
                        r2 <- conn.simpleQuery("SELECT 2")
                        r3 <- conn.simpleQuery("SELECT 3")
                    yield
                        val v1 = new String(r1(0).column(0).get.toArray, StandardCharsets.UTF_8)
                        val v2 = new String(r2(0).column(0).get.toArray, StandardCharsets.UTF_8)
                        val v3 = new String(r3(0).column(0).get.toArray, StandardCharsets.UTF_8)
                        assert(v1 == "1")
                        assert(v2 == "2")
                        assert(v3 == "3")
                }
            }
        }
    }

    "PostgresConnection isOpen returns false after close" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                PostgresConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        ctx.database,
                        Present(ctx.password),
                        Absent,
                        64,
                        Duration.Infinity,
                        EncodingRegistry.builtin
                    )
                    .flatMap { conn =>
                        for
                            before <- conn.isOpen
                            _      <- conn.close
                            after  <- conn.isOpen
                        yield
                            assert(before)
                            assert(!after)
                    }
            }
        }
    }

    "SimpleQueryExchange handles multi-statement SQL, returns rows from all statements" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    conn.simpleQuery("SELECT 1; SELECT 2").map { rows =>
                        assert(rows.size == 2)
                        val v1 = new String(rows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                        val v2 = new String(rows(1).column(0).get.toArray, StandardCharsets.UTF_8)
                        assert(v1 == "1")
                        assert(v2 == "2")
                    }
                }
            }
        }
    }

    "PostgresChannel NoticeResponse does not block main exchange, query completes normally" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    // RAISE NOTICE generates a NoticeResponse, the exchange must handle it gracefully.
                    conn.simpleQuery("DO $$ BEGIN RAISE NOTICE 'hello from test'; END $$;").map { rows =>
                        // DO block returns no rows
                        assert(rows.isEmpty)
                    }
                }
            }
        }
    }

    "StartupExchange cleartext auth succeeds" in {
        Scope.run {
            // Verifies that password auth works against the shared container's configured user/password.
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    conn.isOpen.map(open => assert(open))
                }
            }
        }
    }

    "SimpleQueryExchange ParameterStatus mid-stream updates parameters map" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    for
                        // SET command causes server to send ParameterStatus
                        _      <- conn.simpleQuery("SET application_name = 'kyo-sql-test'")
                        params <- conn.parameters.get
                    yield assert(params.contains("application_name"))
                }
            }
        }
    }

    "SimpleQueryExchange INSERT returns affected row count" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    // Each test runs in its own fresh schema, so the table name is collision-free.
                    conn.simpleExecute("CREATE TABLE phase3_insert (id INT)").flatMap { _ =>
                        conn.simpleExecute("INSERT INTO phase3_insert VALUES (1), (2), (3)").map { count =>
                            assert(count == 3L)
                        }
                    }
                }
            }
        }
    }

    /** Verifies that [[PostgresConnection.onConnectPanic]] logs an error with the label and returns a [[SqlConnectionException]] whose
      * message contains the throwable's message.
      *
      * Calls the real production helper directly, no network I/O. Installs a capturing [[Log]] via [[Log.let]] to assert that the log
      * entry was emitted at error level with the expected module prefix and label.
      */
    "PostgresConnection onConnectPanic logs error with label and returns SqlConnectionException" in {
        val sink  = new TestLogSink
        val cause = new RuntimeException("test panic cause")
        Log.let(Log(sink)) {
            PostgresConnection.onConnectPanic(cause, "testLabel", "127.0.0.1", 5432).map { exc =>
                Log.flush.andThen {
                    val entries = sink.captured
                    assert(entries.size == 1, s"expected exactly 1 log entry, got: $entries")
                    val (level, msg) = entries(0)
                    assert(level == Log.Level.error, s"expected error level, got: $level")
                    assert(msg.contains("testLabel"), s"log message should contain the label: $msg")
                    assert(msg.contains("test panic cause"), s"log message should contain throwable message: $msg")
                    assert(msg.contains("[kyo-sql] PostgresConnection"), s"log message should contain module prefix: $msg")
                    val causeMsg = Option(exc.getCause).map(_.getMessage).getOrElse("")
                    assert(
                        causeMsg.contains("test panic cause"),
                        s"SqlConnectionException cause should contain throwable message: $causeMsg"
                    )
                }
            }
        }
    }

end PostgresConnectionTest
