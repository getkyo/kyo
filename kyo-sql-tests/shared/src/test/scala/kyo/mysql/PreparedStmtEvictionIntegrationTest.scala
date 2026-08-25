package kyo.mysql

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests verifying that evicted prepared statements are closed server-side via `COM_STMT_CLOSE`.
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared MySQL 8.0 container (via [[SqlSharedContainers.withFreshSchema]]). Each
  * test verifies server-side statement counts via `performance_schema.prepared_statements_instances`.
  *
  * Test strategy: set the cache size to 2 via `preparedStatementCacheSize=2`, execute 10 queries with distinct SQL strings so each occupies
  * a different cache slot. After eviction, `COM_STMT_CLOSE` packets are sent on the next request; the server removes those statements.
  */
class PreparedStmtEvictionIntegrationTest extends SqlContainerTest:

    override def timeout: Duration = 3.minutes

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    /** Connect to MySQL with the given prepared-statement cache size, passing the client to `f`. */
    private def withMyClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx,
        cacheSize: Int
    )(
        f: SqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlConnectionException | SqlException]) =
        MysqlClient.initWith(
            myUrl(ctx),
            SqlConfig.default.copy(
                preparedStatementCacheSize = cacheSize,
                maxConnections = 1,
                minConnections = 1
            )
        )(f)

    /** Counts this connection's server-side prepared statements via `performance_schema`.
      *
      * Nothing is caught here, deliberately. A probe that answered a `-1L` sentinel on failure would let each leaf gate on `if count >= 0
      * then assert(...) else succeed`, and a probe that cannot read the count proves nothing about eviction. Letting the abort propagate
      * makes an unreadable count a red leaf, and kyo-test renders the server's own error with its frame, which is strictly more than any
      * string this suite could rebuild. The stream path sends no `COM_STMT_CLOSE`, so cache eviction is MySQL's only server-side close path
      * and this suite is the only thing that watches it.
      *
      * Two fixture conditions have to hold. The probe needs SELECT on `performance_schema` and EXECUTE on `sys`, which the mysql
      * entrypoint's `MYSQL_USER` does not get; `SqlSharedContainers.withFreshMysqlSchemaBody` grants both as root, and without them the
      * probe fails with 1142 and 1370. It also needs `performance_schema` itself, which `ContainerPredef.MySQL.defaultServerArgs` turns OFF
      * to keep fixtures small; `SqlSharedContainers` turns it back on for this fixture alone. Satisfying only the grants is the quiet
      * failure: the table exists, is empty for every session, and the count reads 0.
      *
      * Hence the shape of the SQL. Written the obvious way, as `COUNT(*) ... WHERE OWNER_THREAD_ID = sys.ps_thread_id(connection_id())`,
      * MySQL never evaluates the predicate against an empty table, so `sys.ps_thread_id` is never called and its error 1683 never fires.
      * Selecting the thread id in a derived table forces it, so a disabled `performance_schema` surfaces as a red leaf naming the engine
      * instead of a zero that looks like an answer. The probe cannot report a number it did not observe.
      */
    private def serverStmtCount(client: SqlClient)(using Frame): Long < (Async & Abort[SqlException]) =
        countOf(
            client,
            "SELECT (SELECT COUNT(*) FROM performance_schema.prepared_statements_instances " +
                "WHERE OWNER_THREAD_ID = t.tid) FROM (SELECT sys.ps_thread_id(connection_id()) AS tid) t"
        )

    /** Every server-side prepared statement, across all sessions.
      *
      * Reported alongside the per-connection count when an assertion fails, because the two numbers separate the diagnoses that a bare zero
      * cannot: a total of zero means the statements are genuinely gone, while a nonzero total with a zero per-connection count means the
      * `OWNER_THREAD_ID` correlation is not selecting this session and the probe, not the eviction path, is what is wrong.
      */
    private def serverStmtCountAllSessions(client: SqlClient)(using Frame): Long < (Async & Abort[SqlException]) =
        countOf(client, "SELECT COUNT(*) FROM performance_schema.prepared_statements_instances")

    /** Reads a one-column count over the simple-query protocol, so that asking does not change the answer.
      *
      * `simpleQuery` rather than `query`, and the distinction is the whole measurement. `query` goes out as `COM_STMT_PREPARE` +
      * `COM_STMT_EXECUTE`, so the probe prepares a statement of its own, which the server then holds and counts. Worse, that statement
      * enters the same size-2 cache and evicts a neighbour, and `COM_STMT_CLOSE` for an eviction is not sent until the next extended
      * request, which never comes. A probe built on `query` therefore reads `cacheSize + 1` and reports the eviction path as broken by
      * exactly one when it is working: measured here as 3 against an expected 2, on both leaves.
      *
      * `simpleQuery` sends `COM_QUERY`. It prepares nothing, caches nothing, evicts nothing, and does not run
      * `MysqlConnection.drainPendingCloses`, so the count it returns is the count that was there before it was asked for.
      */
    private def countOf(client: SqlClient, sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        client.simpleQuery(sql).flatMap(oneLong)

    /** The same read over the extended protocol, which is the protocol the workload uses. Only for identifying the workload's session. */
    private def viaExtended(client: SqlClient, sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        client.query(sql).flatMap(oneLong)

    private def oneLong(rows: Chunk[SqlRow])(using Frame): Long < Abort[SqlException] =
        if rows.isEmpty then (0L: Long)
        else Abort.recover((e: SqlDecodeException) => Abort.fail(e: SqlException))(rows(0).decode[Long](0))

    /** Asserts the probe reads the session the workload wrote to, which nothing else in this suite checks.
      *
      * `serverStmtCount` correlates on `OWNER_THREAD_ID`, so it answers for one session. The workload runs through `query`, which routes
      * via the pool, and the probe runs through `simpleQuery`, which leases from that same pool. They land on the same connection only
      * because `maxConnections = 1` leaves the pool nothing else to hand out. That is a correctness property resting on a fixture constant,
      * with nothing watching the coupling: raise the pool size and the probe answers for a session that prepared nothing, both leaves read
      * 0, and the failure looks exactly like eviction closing too much.
      *
      * A silent 0 has several possible causes here, so the coupling gets asserted rather than assumed. Called before the workload: it costs
      * one prepared statement, which the size-2 cache evicts along with the rest, and the flush pair at
      * the end of each leaf still settles the count at `cacheSize` regardless of how many distinct statements preceded it.
      */
    private def assertProbeSharesWorkloadSession(client: SqlClient)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[SqlException]) =
        viaExtended(client, "SELECT connection_id()").flatMap { workload =>
            countOf(client, "SELECT connection_id()").map { probe =>
                assert(
                    workload == probe,
                    s"the probe must read the session the workload wrote to, but the workload ran on connection $workload " +
                        s"and the probe on $probe; with maxConnections = 1 these are the same connection, so a mismatch means " +
                        "the pool now hands out more than one and every count in this suite is answering for the wrong session"
                )
                ()
            }
        }

    "10 distinct queries with cacheSize=2 leave exactly 2 server-side prepared statements" in {
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
                    assertProbeSharesWorkloadSession(client).andThen(Kyo.foreach(queries) { sql =>
                        client.query(sql)
                    }).andThen(
                        // Two flush queries to drain all pending closes:
                        // - The first drains evictions from the loop, then causes a new eviction.
                        // - The second drains that new eviction (cache hit, no new eviction after this).
                        // After two flushes the server holds exactly cacheSize statements.
                        client.query("SELECT 99")
                    ).andThen(
                        client.query("SELECT 99")
                    ).andThen(serverStmtCount(client)).flatMap { count =>
                        serverStmtCountAllSessions(client).map { total =>
                            assert(
                                count == 2,
                                s"Expected exactly 2 server-side prepared statements (cacheSize=2), got $count " +
                                    s"(all sessions: $total)"
                            )
                        }
                    }
                }
            }
        }
    }

    "Connection close does not leak prepared statements" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                withMyClient(ctx, cacheSize = 2) { client =>
                    val queries = Chunk("SELECT 11", "SELECT 12", "SELECT 13", "SELECT 14", "SELECT 15")
                    assertProbeSharesWorkloadSession(client).andThen(Kyo.foreach(queries) { sql =>
                        client.query(sql)
                    }).andThen(
                        // Flush query 1: drains pending closes from the loop and evicts one more.
                        client.query("SELECT 98")
                    ).andThen(
                        // Flush query 2: drains that eviction (cache hit, no new eviction).
                        client.query("SELECT 98")
                    ).andThen(serverStmtCount(client)).flatMap { count =>
                        serverStmtCountAllSessions(client).map { total =>
                            assert(
                                count == 2,
                                s"Expected exactly 2 server-side prepared statements after drain, got $count " +
                                    s"(all sessions: $total)"
                            )
                        }
                    }
                }
            }
        }
    }

end PreparedStmtEvictionIntegrationTest
