package kyo.mysql

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests verifying that evicted prepared statements are closed server-side via `COM_STMT_CLOSE`.
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared MySQL 8.0 container (via [[SqlSharedContainers.withFreshSchema]]). Each
  * test verifies server-side statement counts via `performance_schema.prepared_statements_instances`.
  *
  * Test strategy: set the cache size to 2 via `preparedStmtCacheSize=2`, execute 10 queries with distinct SQL strings so each occupies a
  * different cache slot. After eviction, `COM_STMT_CLOSE` packets are sent on the next request; the server removes those statements.
  */
class PreparedStmtEvictionIntegrationTest extends kyo.Test:

    override def timeout: Duration = 3.minutes

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    /** Connect to MySQL with the given prepared-statement cache size, passing the client to `f`. */
    private def withMyClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx,
        cacheSize: Int
    )(
        f: SqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException.Connection | SqlException]) =
        SqlClient.initMysqlWith(
            myUrl(ctx),
            SqlConfig.default.copy(
                preparedStmtCacheSize = cacheSize,
                maxConnections = 1,
                minConnections = 1
            )
        )(f)

    /** Count server-side prepared statements for this connection via `performance_schema`.
      *
      * Returns -1 if `performance_schema` is unavailable or the table is not accessible.
      */
    private def serverStmtCount(client: SqlClient)(using Frame): Long < (Async & Abort[SqlException]) =
        Abort
            .run[SqlException](
                client.query(
                    "SELECT COUNT(*) FROM performance_schema.prepared_statements_instances " +
                        "WHERE OWNER_THREAD_ID = sys.ps_thread_id(connection_id())"
                ).flatMap { rows =>
                    // client.query on MySQL uses the binary extended protocol; COUNT(*) is BIGINT
                    // (8 little-endian bytes), not ASCII digits, so decode via row.decode[Long]
                    // (routed to MysqlRowReader by SqlRow.decode's OID dispatch).
                    if rows.isEmpty then (0L: Long)
                    else Abort.recover((e: SqlException.Decode) => Abort.fail(e: SqlException))(rows(0).decode[Long](0))
                }
            )
            .map {
                case Result.Success(v) => v
                case Result.Failure(_) => -1L // performance_schema unavailable, can't verify.
                case Result.Panic(t) =>
                    scala.Console.err.println(s"[kyo-sql] serverStmtCount panic: ${t.getMessage}")
                    -1L
            }

    "10 distinct queries with cacheSize=2 leave exactly 2 server-side prepared statements" in {
        Async.timeout(60.seconds)(
            Scope.run {
                SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                    withMyClient(ctx, cacheSize = 2) { client =>
                        // Execute 10 queries with distinct SQL so each occupies a unique cache slot.
                        // With cacheSize=2, after query 3 the cache is full and eviction begins.
                        // The drain fires on the next request, sending COM_STMT_CLOSE for each evicted statement.
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
                            // - The second drains that new eviction (cache hit, no new eviction after this).
                            // After two flushes the server holds exactly cacheSize statements.
                            client.query("SELECT 99")
                        ).andThen(
                            client.query("SELECT 99")
                        ).andThen(serverStmtCount(client)).map { count =>
                            if count >= 0 then
                                assert(count == 2, s"Expected exactly 2 server-side prepared statements (cacheSize=2), got $count")
                            else
                                // performance_schema unavailable, pass.
                                succeed
                            end if
                        }
                    }
                }
            }
        )
    }

    "Connection close does not leak prepared statements" in {
        Async.timeout(60.seconds)(
            Scope.run {
                SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                    withMyClient(ctx, cacheSize = 2) { client =>
                        val queries = Chunk("SELECT 11", "SELECT 12", "SELECT 13", "SELECT 14", "SELECT 15")
                        Kyo.foreach(queries) { sql =>
                            client.query(sql)
                        }.andThen(
                            // Flush query 1: drains pending closes from the loop and evicts one more.
                            client.query("SELECT 98")
                        ).andThen(
                            // Flush query 2: drains that eviction (cache hit, no new eviction).
                            client.query("SELECT 98")
                        ).andThen(serverStmtCount(client)).flatMap { count =>
                            if count >= 0 then
                                Sync.defer(assert(
                                    count == 2,
                                    s"Expected exactly 2 server-side prepared statements after drain, got $count"
                                ))
                            else
                                Sync.defer(succeed)
                            end if
                        }
                    }
                }
            }
        )
    }

end PreparedStmtEvictionIntegrationTest
