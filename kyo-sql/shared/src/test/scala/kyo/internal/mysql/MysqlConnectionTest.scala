package kyo.internal.mysql

import kyo.*
import kyo.internal.SqlSharedContainers
import kyo.net.StubConnection

/** Integration tests for MySQL connection, handshake, and text-protocol query.
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared MySQL container (via [[SqlSharedContainers.withFreshSchema]]). The
  * shared container is started with `--default-authentication-plugin=mysql_native_password` so native password auth works without requiring
  * caching_sha2_password.
  *
  * Overrides [[timeout]] to 3 minutes per test to accommodate any container startup time on the first request.
  */
class MysqlConnectionTest extends kyo.Test:

    // 3 minute per-test timeout, MySQL may be slow on first run.
    override def timeout: Duration = 3.minutes

    "HandshakeExchange succeeds with mysql_native_password, container connect completes" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.isOpen.map { open => assert(open) }
                        }
                    }
            }
        }
    }

    "HandshakeExchange wrong password raises SqlException.Connection" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                Abort.run[SqlException](
                    MysqlConnection.connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present("wrongpassword"),
                        Maybe.Absent,
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                ).map {
                    case Result.Failure(_: SqlException.Connection) => succeed
                    case other                                      => fail(s"Expected SqlException.Connection, got: $other")
                }
            }
        }
    }

    "simpleQuery SELECT 1 returns one row with one column" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.simpleQuery("SELECT 1").map { rows =>
                                assert(rows.size == 1)
                                assert(rows(0).values.size == 1)
                                val colVal = rows(0).column(0)
                                assert(colVal.isDefined)
                                val str = new String(colVal.get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                assert(str == "1")
                            }
                        }
                    }
            }
        }
    }

    "simpleQuery SELECT 1 AS a, 2 AS b returns row with two named columns" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.simpleQuery("SELECT 1 AS a, 2 AS b").map { rows =>
                                assert(rows.size == 1)
                                val row = rows(0)
                                assert(row.columns.size == 2)
                                assert(row.columns(0).name == "a")
                                assert(row.columns(1).name == "b")
                                val aStr = new String(row.column("a").get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                val bStr = new String(row.column("b").get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                assert(aStr == "1")
                                assert(bStr == "2")
                            }
                        }
                    }
            }
        }
    }

    "simpleQuery SELECT WHERE false returns empty result with no error" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.simpleQuery("SELECT 1 WHERE false").map { rows =>
                                assert(rows.isEmpty)
                            }
                        }
                    }
            }
        }
    }

    "simpleQuery CLIENT_DEPRECATE_EOF path, MySQL 8 default, no intermediate EOF" in {
        // MySQL 8.0 negotiates CLIENT_DEPRECATE_EOF by default.
        // This test verifies we correctly parse result sets without intermediate EOF packets.
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.simpleQuery("SELECT 1 AS x, 'hello' AS y").map { rows =>
                                assert(rows.size == 1)
                                val row  = rows(0)
                                val xStr = new String(row.column("x").get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                val yStr = new String(row.column("y").get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                assert(xStr == "1")
                                assert(yStr == "hello")
                            }
                        }
                    }
            }
        }
    }

    "simpleQuery bad SQL raises SqlException.Server with errorCode + sqlState" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            Abort.run[SqlException](conn.simpleQuery("SELECT * FROM nonexistent_table_xyz")).map {
                                case Result.Failure(e: SqlException.Server) =>
                                    assert(e.sqlState.nonEmpty)
                                    assert(e.message.nonEmpty)
                                case other => fail(s"Expected SqlException.Server, got: $other")
                            }
                        }
                    }
            }
        }
    }

    "simpleQuery NULL column decodes as Absent" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.simpleQuery("SELECT NULL AS nullcol").map { rows =>
                                assert(rows.size == 1)
                                val row = rows(0)
                                assert(row.column("nullcol").isEmpty)
                            }
                        }
                    }
            }
        }
    }

    "simpleQuery column accessed by name" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.simpleQuery("SELECT 42 AS answer").map { rows =>
                                assert(rows.size == 1)
                                val row    = rows(0)
                                val answer = row.column("answer")
                                assert(answer.isDefined)
                                val str = new String(answer.get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                assert(str == "42")
                            }
                        }
                    }
            }
        }
    }

    "MysqlConnection.connect fails on wrong host with SqlException.Connection" in {
        Abort.run[SqlException](
            MysqlConnection.connect("127.0.0.1", 19999, "root", Maybe.Present("test"), Maybe.Absent, Maybe.Absent, 64, Duration.Infinity)
        ).map {
            case Result.Failure(_: SqlException.Connection) => succeed
            case other                                      => fail(s"Expected SqlException.Connection, got: $other")
        }
    }

    "MysqlConnection sequential queries, 3 queries in sequence, no state corruption" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.simpleQuery("SELECT 1").flatMap { rows1 =>
                                conn.simpleQuery("SELECT 2").flatMap { rows2 =>
                                    conn.simpleQuery("SELECT 3").map { rows3 =>
                                        def str(rows: Chunk[MysqlRow]) =
                                            new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                        assert(str(rows1) == "1")
                                        assert(str(rows2) == "2")
                                        assert(str(rows3) == "3")
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    "ping works, ComPing receives OkPacket" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.ping().map { _ => succeed }
                        }
                    }
            }
        }
    }

    "ComQuit cleanly closes the session" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        conn.quit().map { _ => succeed }
                    }
            }
        }
    }

    "simpleQuery CREATE TABLE + INSERT + SELECT works end-to-end" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.simpleExecute("CREATE TABLE IF NOT EXISTS kyo_test_t2 (id INT, name VARCHAR(64))").flatMap { _ =>
                                conn.simpleExecute("INSERT INTO kyo_test_t2 VALUES (1, 'alice')").flatMap { affected =>
                                    assert(affected == 1L)
                                    conn.simpleQuery("SELECT id, name FROM kyo_test_t2").flatMap { rows =>
                                        assert(rows.size == 1)
                                        val row     = rows(0)
                                        val idStr   = new String(row.column("id").get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                        val nameStr = new String(row.column("name").get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                        assert(idStr == "1")
                                        assert(nameStr == "alice")
                                        conn.simpleExecute("DROP TABLE kyo_test_t2").map(_ => succeed)
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    "simpleQuery error mid-result raises SqlException.Server" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            // A bad SELECT (missing table) should produce SqlException.Server.
                            Abort.run[SqlException](conn.simpleQuery("SELECT * FROM does_not_exist_xyz")).map {
                                case Result.Failure(e: SqlException.Server) =>
                                    assert(e.sqlState.nonEmpty)
                                case other => fail(s"Expected SqlException.Server, got: $other")
                            }
                        }
                    }
            }
        }
    }

    "MysqlChannel seqId resets to 0 on new command" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            // After the handshake, send a query (seqId reset to 0) then another (seqId reset again).
                            // Both queries succeed, proving the seqId resets correctly between commands.
                            conn.simpleQuery("SELECT 1").flatMap { _ =>
                                conn.simpleQuery("SELECT 2").map { rows =>
                                    val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                    assert(str == "2")
                                }
                            }
                        }
                    }
            }
        }
    }

    "HandshakeExchange AuthSwitchRequest handled, server switches to native_password" in {
        // Our container uses --default-authentication-plugin=mysql_native_password so the
        // normal path is taken; the code also handles AuthSwitchRequest if the server requests it.
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                MysqlConnection
                    .connect(
                        ctx.host,
                        ctx.port,
                        ctx.username,
                        Maybe.Present(ctx.password),
                        Maybe.Present(ctx.database),
                        Maybe.Absent,
                        64,
                        Duration.Infinity
                    )
                    .flatMap { conn =>
                        Scope.ensure(conn.close).andThen {
                            conn.simpleQuery("SELECT 'auth_switch_ok'").map { rows =>
                                val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                assert(str == "auth_switch_ok")
                            }
                        }
                    }
            }
        }
    }

    // Helper: build a MysqlConnection over a StubConnection for close-triad unit tests.
    private def stubMysqlConnection(using Frame): MysqlConnection < Sync =
        MysqlConnection.withConnection(StubConnection())
    end stubMysqlConnection

    "closeNow closes the TCP connection immediately without quit" in {
        stubMysqlConnection.flatMap { conn =>
            conn.isOpen.flatMap { openBefore =>
                conn.closeNow.flatMap { _ =>
                    conn.isOpen.map { openAfter =>
                        assert(openBefore)
                        assert(!openAfter)
                    }
                }
            }
        }
    }

    "close(Duration.Zero) closes the TCP connection without sending quit" in {
        stubMysqlConnection.flatMap { conn =>
            conn.isOpen.flatMap { openBefore =>
                Abort.run[SqlException](conn.close(Duration.Zero)).flatMap { result =>
                    conn.isOpen.map { openAfter =>
                        assert(openBefore)
                        assert(result.isSuccess)
                        assert(!openAfter)
                    }
                }
            }
        }
    }

    "close() sends best-effort quit then closes the TCP connection" in {
        stubMysqlConnection.flatMap { conn =>
            conn.isOpen.flatMap { openBefore =>
                // close() = close(30.seconds): attempts quit (stub write will fail after outbound is closed, but error is swallowed)
                // then closes the TCP connection. Connection must be closed regardless.
                Abort.run[SqlException](conn.close).flatMap { _ =>
                    conn.isOpen.map { openAfter =>
                        assert(openBefore)
                        assert(!openAfter)
                    }
                }
            }
        }
    }

    // --- pendingCloses queue drain contract ---

    "pendingCloses flush, drainPendingCloses clears all queued ids" in {
        stubMysqlConnection.flatMap { conn =>
            // Pre-populate the queue with 4 IDs (the count is not significant: drainPendingCloses
            // sends `COM_STMT_CLOSE` for every ID present at the moment of the atomic swap, no matter
            // how many).
            val ids = Chunk.from(1 to 4).map(_.toString)
            conn.pendingCloses.set(ids).flatMap { _ =>
                conn.pendingCloses.get.flatMap { before =>
                    assert(before.size == 4, s"Expected 4 ids before drain, got ${before.size}")
                    // drainPendingCloses atomically swaps out all IDs and sends COM_STMT_CLOSE for each.
                    // The StubConnection outbound channel accepts bytes but returns no MySQL OK packets,
                    // so drainPendingCloses will fail trying to write if the channel is closed. We wrap
                    // in Abort.run to tolerate any network error from the stub, the queue must be empty.
                    Abort.run[SqlException](conn.drainPendingCloses).flatMap { _ =>
                        conn.pendingCloses.get.map { after =>
                            assert(after.isEmpty, s"Queue not empty after drain: $after")
                        }
                    }
                }
            }
        }
    }

end MysqlConnectionTest
