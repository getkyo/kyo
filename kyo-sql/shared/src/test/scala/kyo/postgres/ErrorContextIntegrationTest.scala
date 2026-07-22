package kyo.postgres

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for SqlException enriched context fields, Postgres backend.
  *
  * Uses a real Postgres container. Fires a query that fails (SELECT 1 FROM no_such_table) and asserts that the resulting
  * SqlServerException carries:
  *   - non-empty sqlState (PG returns "42P01" for undefined_table)
  *   - sqlText containing the original SQL
  *   - paramCount matching the bind count
  *   - connectionId = Present (processId from BackendKeyData)
  *   - frame.position.fileName pointing to this file
  *
  * Test discipline:
  *   - Wrapped in Async.timeout(60.seconds).
  *   - One probe query after the main assertion verifies connection reusability per STEERING.md.
  */
class ErrorContextIntegrationTest extends kyo.Test:

    override def timeout: Duration = 5.minutes

    private def withPgClient[A, S](
        url: String,
        config: SqlConfig = SqlConfig.default
    )(f: SqlClient => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlConnectionException](SqlClient.init(url, config)).flatMap {
            case Result.Success(client) => SqlClient.let(client)(f(client))
            case Result.Failure(e)      => Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                scala.Console.err.println(s"[kyo-sql] ErrorContextIntegrationTest.withPgClient panic: ${t.getMessage}")
                Abort.fail(SqlConnectionConnectFailedException("test", 0, new Exception(t.getMessage)): SqlException)
        }

    // ── Integration leaf: real server error includes sqlState, sqlText, paramCount, connectionId ──

    "real server error includes sqlState, sqlText, paramCount, connectionId" in {
        Scope.run {
            Async.timeout(60.seconds) {
                SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                    val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                    val config = SqlConfig(
                        maxConnections = 2,
                        acquireTimeout = 15.seconds,
                        queryTimeout = 15.seconds,
                        idleTimeout = 10.minutes
                    )
                    withPgClient(url, config) { client =>
                        val failSql = "SELECT 1 FROM no_such_table"
                        // Frame.derive captures the call site in this file rather than Test.scala:13.
                        Abort.run[SqlException](
                            client.executeRaw(failSql)(using Frame.derive)
                        ).flatMap {
                            case Result.Failure(e: SqlServerException) =>
                                // PG returns SQLSTATE 42P01 for "undefined_table"
                                assert(e.sqlState.nonEmpty, s"Expected non-empty sqlState, got empty")
                                assert(
                                    e.sqlState == "42P01",
                                    s"Expected SQLSTATE 42P01 (undefined_table), got: ${e.sqlState}"
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
                                // connectionId should be Present, PG always sends BackendKeyData with processId
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
                                client.query("SELECT 1").map { rows =>
                                    assert(rows.nonEmpty, "Probe query after error assertion returned no rows")
                                }

                            case Result.Failure(other) =>
                                fail(s"Expected SqlServerException with SQLSTATE 42P01, got: $other")

                            case Result.Success(_) =>
                                fail("Expected query to fail with SqlServerException but it succeeded")

                            case Result.Panic(t) =>
                                fail(s"Unexpected panic: ${t.getMessage}")
                        }
                    }
                }
            }
        }
    }

end ErrorContextIntegrationTest
