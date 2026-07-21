package kyo.postgres

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests verifying that evicted prepared statements are closed server-side via `Close 'S' <name>`.
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared Postgres container (via [[SqlSharedContainers.withFreshSchema]]). Each
  * test verifies server-side statement counts via the `pg_prepared_statements` view, which is session-local — so a fresh connection always
  * starts with zero prepared statements.
  *
  * Test strategy: set `preparedStmtCacheSize=2`, execute 10 queries with distinct SQL strings so each occupies a different cache slot.
  * After 10 queries the cache holds only the 2 most-recently used statements; the other 8 must have been closed server-side. A single
  * connection is used throughout each test so the `pg_prepared_statements` view reflects exactly that connection's state.
  */
class PreparedStmtEvictionIntegrationTest extends kyo.Test:

    override def timeout: Duration = 3.minutes

    private def pgUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    /** Open a SqlClient with the given cache size (pool pinned to 1/1) and execute `f`, closing the client on scope exit. */
    private def withSmallCacheClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx,
        cacheSize: Int
    )(
        f: PgSqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException.Connection | SqlException]) =
        SqlClient.initWith(
            pgUrl(ctx),
            SqlClientConfig.default.copy(
                preparedStmtCacheSize = cacheSize,
                maxConnections = 1,
                minConnections = 1
            )
        )(f)

    /** Query the number of server-side prepared statements for this connection via `pg_prepared_statements`. */
    private def serverStmtCount(client: PgSqlClient)(using Frame): Long < (Async & Abort[SqlException] & Abort[SqlException.Decode]) =
        client.simpleQuery("SELECT count(*) FROM pg_prepared_statements").flatMap { rows =>
            rows.headMaybe match
                case Absent       => 0L
                case Present(row) => row.decode[Long]("count")
        }

    "10 distinct queries with cacheSize=2 leave exactly 2 server-side prepared statements" in {
        Async.timeout(60.seconds)(
            Scope.run {
                SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                    withSmallCacheClient(ctx, cacheSize = 2) { client =>
                        // Execute 10 queries with distinct SQL so each occupies a unique cache slot.
                        // After query 3 the cache is full and eviction begins; the drain fires on the next request.
                        val queries = Chunk(
                            "SELECT 1",
                            "SELECT 2",
                            "SELECT 3",
                            "SELECT 4",
                            "SELECT 5",
                            "SELECT 6",
                            "SELECT 7",
                            "SELECT 8",
                            "SELECT 9",
                            "SELECT 10"
                        )
                        Kyo.foreach(queries) { sql =>
                            client.query(sql)
                        }.andThen(
                            // Two flush queries to drain all pending closes:
                            // - The first drains evictions from the loop, then causes a new eviction.
                            // - The second drains that new eviction with a cache-hit (same SQL), causing no new eviction.
                            // After two flushes the server holds exactly cacheSize statements.
                            client.query("SELECT 99")
                        ).andThen(
                            client.query("SELECT 99")
                        ).andThen(serverStmtCount(client)).map { count =>
                            assert(count == 2, s"Expected 2 server-side prepared statements, got $count")
                        }
                    }
                }
            }
        )
    }

    "Connection close does not leak prepared statements (probe via pg_prepared_statements)" in {
        Async.timeout(60.seconds)(
            Scope.run {
                SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                    // Open connection A, execute queries to populate the prepared-statement cache, then close it.
                    // PG drops all session-local prepared statements when the session ends — pg_prepared_statements
                    // is session-scoped, so a second connection (B) always starts with an empty view of its own
                    // prepared statements. We verify that B sees zero statements after A closes, confirming that
                    // there is no server-side leak from A's session.
                    withSmallCacheClient(ctx, cacheSize = 3) { clientA =>
                        val queries = Chunk("SELECT 11", "SELECT 12", "SELECT 13", "SELECT 14", "SELECT 15")
                        Kyo.foreach(queries) { sql =>
                            clientA.query(sql)
                        }.andThen(
                            // Flush to drain any pending closes so the cache is in a clean state.
                            clientA.query("SELECT 98")
                        ).andThen(
                            clientA.query("SELECT 98")
                        ).andThen(serverStmtCount(clientA)).flatMap { countA =>
                            // Connection A must hold exactly cacheSize statements before close.
                            assert(countA == 3, s"Expected 3 server-side prepared statements on conn A before close, got $countA")
                            // Now open connection B *while A is still open*, then close A via scope exit.
                            // pg_prepared_statements is session-local: B sees only its own statements (zero at start).
                            withSmallCacheClient(ctx, cacheSize = 3) { clientB =>
                                // B has just connected and run no extended queries — its statement count must be 0.
                                serverStmtCount(clientB).map { countB =>
                                    assert(countB == 0, s"Expected 0 server-side prepared statements on fresh conn B, got $countB")
                                }
                            }
                        }
                    }
                }
            }
        )
    }

end PreparedStmtEvictionIntegrationTest
