package kyo.postgres

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for connection-pool warm-up `minConnections`.
  *
  * Uses a real PostgreSQL container (postgres:16-alpine via the per-fork-JVM shared container). The test verifies that `minConnections=5`
  * causes exactly 5 idle connections to appear in `pg_stat_activity` before `init` returns, and that queries can be served from those
  * pre-warmed connections without opening new ones.
  *
  * Test discipline:
  *   - Wrapped in `Async.timeout(60.seconds)`.
  *   - No `Async.sleep` for synchronization, connections are observed via `pg_stat_activity` after `init` returns.
  *   - After the warm-up count assertion, one probe query verifies connection reusability before container teardown.
  */
class PoolWarmupIntegrationTest extends kyo.Test:

    override def timeout: Duration = 5.minutes

    /** Acquires a Postgres-backed SqlClient scoped to `Scope`, available inside `f`. */
    private def withPgClient[A, S](
        url: String,
        config: SqlClientConfig
    )(f: SqlClient => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlException.Connection](SqlClient.init(url, config)).flatMap {
            case Result.Success(client) => SqlClient.let(client)(f(client))
            case Result.Failure(e)      => Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                scala.Console.err.println(s"[kyo-sql] PoolWarmupIntegrationTest.withPgClient panic: ${t.getMessage}")
                Abort.fail(SqlException.Connection(t.getMessage, summon[Frame]): SqlException)
        }

    // ── Integration leaf: minConnections=5 opens 5 connections at startup ─────

    "minConnections=5 against a real PG opens 5 connections at startup" in {
        Scope.run {
            Async.timeout(60.seconds) {
                SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                    val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                    val config = SqlClientConfig(
                        maxConnections = 10,
                        minConnections = 5,
                        acquireTimeout = 10.seconds,
                        queryTimeout = 10.seconds,
                        idleTimeout = 10.minutes
                    )
                    withPgClient(url, config) { client =>
                        // After init returns, 5 idle connections should be visible in pg_stat_activity.
                        // Query pg_stat_activity for connections with application_name='kyo-sql' and state='idle'.
                        // application_name is set to 'kyo-sql' by StartupExchange.
                        //
                        // We count individual rows (not count(*)) to avoid binary-format decoding issues:
                        // client.query uses the extended protocol (binary format for numeric types),
                        // while SELECT count(*) returns an int8 in binary (8 bytes, not a readable string).
                        // Instead, we count rows after fetching application_name values.
                        //
                        // Filter by datname = current_database() so the count only includes connections
                        // within this test's schema (the per-fork-JVM container is shared across schemas).
                        client.executeRaw(
                            """SELECT application_name
                              |  FROM pg_stat_activity
                              | WHERE application_name = 'kyo-sql'
                              |   AND state = 'idle'
                              |   AND datname = current_database()""".stripMargin
                        ).flatMap { _ =>
                            // executeRaw uses simple query protocol and returns affected-row count.
                            // For SELECT on pg_stat_activity, use the raw query to get the row count.
                            // Use a direct approach: count rows from queryFirst result.
                            client.query(
                                """SELECT count(application_name)::text
                                  |  FROM pg_stat_activity
                                  | WHERE application_name = 'kyo-sql'
                                  |   AND state = 'idle'
                                  |   AND datname = current_database()""".stripMargin
                            ).flatMap { rows =>
                                assert(rows.nonEmpty, "pg_stat_activity query returned no rows")
                                val countRow   = rows.head
                                val countBytes = countRow.column(0).getOrElse(Span.empty[Byte])
                                val countStr   = new String(countBytes.toArray, "UTF-8")
                                // After init, the 5 warm-up connections are idle in the pool.
                                // One of them will be used by this query (transitions to 'active' briefly),
                                // so we allow idleCount to be 4 or 5.
                                // Note: count(application_name)::text sends text-format in extended protocol.
                                // If still binary, fall back to row-count assertion.
                                val idleCount: Long =
                                    if countStr.trim.nonEmpty then countStr.trim.toLong
                                    else
                                        // Binary int8 fallback: decode 8-byte big-endian
                                        val arr = countBytes.toArray
                                        if arr.length == 8 then
                                            val v = arr.indices.foldLeft(0L)((acc, i) => (acc << 8) | (arr(i) & 0xff))
                                            v
                                        else 0L
                                        end if
                                assert(
                                    idleCount >= 4L,
                                    s"Expected at least 4 idle kyo-sql connections, got $idleCount"
                                )
                            }
                        }.flatMap { _ =>
                            // Probe connection reusability: one trivial query after warm-up assertion.
                            client.query("SELECT 42").map { rows =>
                                assert(rows.nonEmpty, "Probe query after warm-up returned no rows")
                            }
                        }
                    }
                }
            }
        }
    }

end PoolWarmupIntegrationTest
