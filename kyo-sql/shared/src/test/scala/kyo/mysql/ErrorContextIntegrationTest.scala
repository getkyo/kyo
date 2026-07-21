package kyo.mysql

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for SqlException enriched context fields, MySQL backend.
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared MySQL container (via [[SqlSharedContainers.withFreshSchema]]). Fires a
  * query that fails (SELECT 1 FROM no_such_table) and asserts that the resulting SqlException.Server carries:
  *   - non-empty sqlState (MySQL returns "42S02" for undefined_table)
  *   - sqlText containing the original SQL
  *   - paramCount matching the bind count
  *   - connectionId = Present (thread ID from the MySQL handshake)
  *   - frame.position.fileName pointing to this file
  *
  * Test discipline:
  *   - Wrapped in Async.timeout(60.seconds).
  *   - One probe query after the main assertion verifies connection reusability per STEERING.md.
  */
class ErrorContextIntegrationTest extends kyo.Test:

    override def timeout: Duration = 5.minutes

    private def withMyClient[A, S](
        url: String,
        config: SqlClientConfig = SqlClientConfig.default
    )(f: SqlClient => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlException.Connection](SqlClient.initMy(url, config)).flatMap {
            case Result.Success(client) => SqlClient.let(client)(f(client))
            case Result.Failure(e)      => Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                scala.Console.err.println(s"[kyo-sql] ErrorContextIntegrationTest(MySQL).withMyClient panic: ${t.getMessage}")
                Abort.fail(SqlException.Connection(t.getMessage, summon[Frame]): SqlException)
        }

    // ── Integration leaf: real server error includes sqlState, sqlText, paramCount, connectionId ──

    "real server error includes sqlState, sqlText, paramCount, connectionId" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                Async.timeout(60.seconds) {
                    val url = s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                    val config = SqlClientConfig(
                        maxConnections = 2,
                        acquireTimeout = 15.seconds,
                        queryTimeout = 15.seconds,
                        idleTimeout = 10.minutes
                    )
                    withMyClient(url, config) { client =>
                        val failSql = "SELECT 1 FROM no_such_table"
                        // Frame.derive captures the call site in this file rather than Test.scala:13.
                        Abort.run[SqlException](
                            client.executeRaw(failSql)(using Frame.derive)
                        ).flatMap {
                            case Result.Failure(e: SqlException.Server) =>
                                // MySQL returns SQLSTATE 42S02 for "table doesn't exist" (ER_NO_SUCH_TABLE)
                                assert(e.sqlState.nonEmpty, s"Expected non-empty sqlState, got empty")
                                assert(
                                    e.sqlState == "42S02",
                                    s"Expected SQLSTATE 42S02 (ER_NO_SUCH_TABLE), got: ${e.sqlState}"
                                )
                                // sqlText should contain the original SQL
                                assert(
                                    e.sqlText.exists(_.contains("no_such_table")),
                                    s"Expected sqlText to contain 'no_such_table', got: ${e.sqlText}"
                                )
                                // paramCount 0 for a simple query with no bound parameters
                                assert(
                                    e.paramCount == 0,
                                    s"Expected paramCount == 0 for simple query, got: ${e.paramCount}"
                                )
                                // connectionId should be Present, MySQL always assigns a thread/connection ID
                                assert(
                                    e.connectionId.isDefined,
                                    s"Expected connectionId to be Present, got: ${e.connectionId}"
                                )
                                // frame should point to this test file
                                val fileName = e.frame.position.fileName
                                assert(
                                    fileName.contains("ErrorContextIntegrationTest"),
                                    s"Expected frame to contain 'ErrorContextIntegrationTest', got: $fileName"
                                )
                                // Connection reusability probe, run a trivial query on the same client.
                                client.executeRaw("SELECT 1").map { _ =>
                                    succeed
                                }

                            case Result.Failure(other) =>
                                fail(s"Expected SqlException.Server with SQLSTATE 42S02, got: $other")

                            case Result.Success(_) =>
                                fail("Expected query to fail with SqlException.Server but it succeeded")

                            case Result.Panic(t) =>
                                fail(s"Unexpected panic: ${t.getMessage}")
                        }
                    }
                }
            }
        }
    }

end ErrorContextIntegrationTest
