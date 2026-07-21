package kyo.mysql

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for MySQL COM_RESET_CONNECTION pool hygiene.
  *
  * Tests:
  *   1. Without reset, a session variable set in one use persists to the next borrower (proves the leak).
  *   2. With resetConnection(), a session variable set in one use is cleared for the next borrower.
  *   3. resetConnection does not close the connection, the connection remains usable after reset.
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared MySQL container (via [[SqlSharedContainers.withFreshSchema]]).
  */
class MysqlPoolResetIntegrationTest extends kyo.Test:

    override def timeout: Duration = 3.minutes

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def withMyClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(
        f: SqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException.Connection | SqlException]) =
        SqlClient.initMyWith(
            myUrl(ctx),
            SqlConfig.default.copy(
                maxConnections = 1,
                minConnections = 1
            )
        )(f)

    private def readUserVar(client: SqlClient)(using Frame): String < (Async & Abort[SqlException]) =
        client.query("SELECT @user_var").map { rows =>
            rows.headOption match
                case None => "NULL"
                case Some(row) =>
                    row.column(0) match
                        case Maybe.Present(bytes) => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                        case Maybe.Absent         => "NULL"
        }

    // ── without reset session variable leaks ──────────────────────────────────

    "without resetConnection session variable leaks to next use of the same connection" in {
        // This test simulates pool reuse WITHOUT COM_RESET_CONNECTION by using the same
        // physical connection object twice. After SET @user_var = 1, the variable persists.
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    // First use: set the user variable.
                    client.executeRaw("SET @user_var = 1").flatMap { _ =>
                        // Second use of the SAME connection (simulates returning to pool without reset).
                        readUserVar(client).map { value =>
                            // Without reset, the variable persists across uses on the same connection.
                            assert(value == "1", s"Expected variable to persist without reset, got: '$value'")
                        }
                    }
                }
            }
        }
    }

    // ── with resetConnection session variable is cleared ──────────────────────

    "with resetConnection session variable is unset for next use" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    // First use: set the user variable.
                    client.executeRaw("SET @user_var = 42").flatMap { _ =>
                        // Simulate pool release with reset.
                        client.reset.flatMap { _ =>
                            // After reset, the user variable should be NULL.
                            readUserVar(client).map { value =>
                                assert(
                                    value == "NULL",
                                    s"Expected @user_var to be NULL after COM_RESET_CONNECTION, got: '$value'"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── connection is usable after resetConnection ────────────────────────────

    "connection remains usable after COM_RESET_CONNECTION, subsequent queries succeed" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    // Set a variable, then reset, then run a normal query.
                    client.executeRaw("SET @foo = 'bar'").flatMap { _ =>
                        client.reset.flatMap { _ =>
                            // Connection must still be able to execute queries.
                            client.query("SELECT 99").map { rows =>
                                assert(rows.size == 1, "Expected 1 row after reset")
                                val value = rows(0)
                                    .column(0)
                                    .fold("NULL")(bytes => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8))
                                assert(value == "99", s"Expected '99', got '$value'")
                            }
                        }
                    }
                }
            }
        }
    }

end MysqlPoolResetIntegrationTest
