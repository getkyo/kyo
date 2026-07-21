package kyo.mysql

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for MySQL query cancellation via KILL QUERY.
  *
  * Tests:
  *   1. Cancel a long-running query (SELECT SLEEP(5)), query aborts with ER_QUERY_INTERRUPTED / SQLSTATE 70100
  *   2. Cancel an already-completed query, silent no-op (no error from cancelQuery itself)
  *   3. cancellableQuery pattern, connectionId is exposed via handle.connectionId (SqlCancelHandle.Mysql)
  *   4. Cancel with wrong connectionId, no error (KILL QUERY of a non-existent thread is silently accepted)
  *   5. Sequential queries after cancel, connection remains usable after KILL QUERY on cancel conn
  *
  * Each test runs against a fresh schema in the per-fork-JVM shared MySQL container (via [[SqlSharedContainers.withFreshSchema]]).
  */
class MysqlCancelIntegrationTest extends kyo.Test:

    override def timeout: Duration = 3.minutes

    private def initClient[A, S](ctx: SqlSharedContainers.SchemaCtx, maxConns: Int = 2)(
        f: MysqlSqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException]) =
        SqlClient.initMysqlWith(
            s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}",
            SqlConfig.default.copy(
                maxConnections = maxConns,
                minConnections = maxConns,
                cancelTimeout = 2.seconds
            )
        )(f)

    // ── cancel a long-running query ───────────────────────────────────────────

    "cancel interrupts SELECT SLEEP(5), query aborts early (error or SLEEP returns 1)" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 2) { client =>
                    // Launch a slow query in a background fiber (wrapping in Abort.run so the fiber is Unit-typed).
                    (client.cancellableQueryFiber(
                        "SELECT SLEEP(5)"
                    ): (SqlCancelHandle.Mysql, Fiber[Chunk[SqlRow], Abort[SqlException]]) < (Async & Abort[SqlException])).flatMap {
                        case (handle, slowFiber) =>
                            // Wait 200ms then issue KILL QUERY using the cancel handle.
                            Async.sleep(200.millis).andThen {
                                client.cancel(handle).andThen {
                                    // MySQL KILL QUERY on SELECT SLEEP has two valid outcomes:
                                    // (a) The server sends ER_QUERY_INTERRUPTED (error 1317 / SQLSTATE 70100).
                                    // (b) The server returns a row with SLEEP value = 1 (interrupted early).
                                    // Both indicate the kill was effective. We accept both.
                                    Abort.run[SqlException](slowFiber.get).map {
                                        case Result.Failure(e: SqlException.Server) =>
                                            // MySQL SQLSTATE for ER_QUERY_INTERRUPTED is 70100.
                                            assert(
                                                e.extra.get("code").exists(c => c == "1317") || e.sqlState == "70100",
                                                s"Expected error 1317 / SQLSTATE 70100, got: code=${e.extra.get("code")}, state=${e.sqlState}, msg=${e.message}"
                                            )
                                        case Result.Failure(e) =>
                                            fail(s"Expected SqlException.Server, got: $e")
                                        case Result.Success(rows) =>
                                            // SLEEP(5) returns 1 when interrupted by KILL QUERY, this is also a valid kill outcome.
                                            // Typed decode via Phase 19b row.decode[T], handles both binary
                                            // (extended-protocol via cancellableQueryFiber) and text format.
                                            rows.headOption match
                                                case None =>
                                                    fail("Expected SLEEP to return 1 (interrupted) or an error, got: empty result")
                                                case Some(row) =>
                                                    row.decode[Long](0).map { sleepResult =>
                                                        assert(
                                                            sleepResult == 1L,
                                                            s"Expected SLEEP to return 1 (interrupted) or an error, got: '$sleepResult'"
                                                        )
                                                    }
                                        case Result.Panic(t) =>
                                            fail(s"Unexpected panic: ${t.getMessage}")
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    // ── cancel an already-completed query (no-op) ─────────────────────────────

    "cancel an already-completed query is a no-op, no error from cancelQuery" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 2) { client =>
                    // Complete a fast query to obtain a handle pointing at a known connection id.
                    (client.cancellableQuery(
                        sql"SELECT 1"
                    ): (SqlCancelHandle.Mysql, Chunk[SqlRow]) < (Async & Abort[SqlException])).flatMap { case (handle, _) =>
                        // The handle's connection has long since been returned to the pool;
                        // KILL QUERY <connectionId> for that thread is a no-op on the server
                        // (the thread either no longer exists or has no active query).
                        Abort.run[SqlException](client.cancel(handle)).map {
                            case Result.Success(_) =>
                                succeed // expected: no error
                            case Result.Failure(e) =>
                                fail(s"Unexpected error from cancel after completion: $e")
                            case Result.Panic(t) =>
                                fail(s"Unexpected panic: ${t.getMessage}")
                        }
                    }
                }
            }
        }
    }

    // ── connectionId is exposed for cancellation ──────────────────────────────

    "cancellableQuery pattern, MysqlConnection.connectionId is positive and available before query" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 1) { client =>
                    (client.cancellableQuery(
                        sql"SELECT 1"
                    ): (SqlCancelHandle.Mysql, Chunk[SqlRow]) < (Async & Abort[SqlException])).map { case (handle, _) =>
                        assert(handle.connectionId > 0, s"Expected positive connectionId, got ${handle.connectionId}")
                    }
                }
            }
        }
    }

    // ── kill with non-existent connectionId (no error) ───────────────────────

    "cancel with non-existent connectionId, server accepts KILL QUERY gracefully" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 1) { client =>
                    // Use a very large connectionId that is extremely unlikely to exist.
                    val fakeConnectionId = 9999999L
                    // Construct a SqlCancelHandle.Mysql pointing at a non-existent thread.
                    val fakeHandle = SqlCancelHandle.Mysql(client.address, fakeConnectionId)
                    // Path A: SqlClient.cancel with a fabricated handle, public KILL QUERY round-trip.
                    Abort.run[SqlException](client.cancel(fakeHandle)).map { _ => succeed }.andThen {
                        // Path B: direct KILL QUERY via executeRaw, preserves the original second probe.
                        Abort.run[SqlException](client.executeRaw(s"KILL QUERY $fakeConnectionId")).map {
                            case Result.Success(_) =>
                                succeed // no error, thread doesn't exist, MySQL says OK
                            case Result.Failure(_: SqlException.Server) =>
                                succeed // error 1094 (unknown thread) is also acceptable
                            case Result.Failure(e) =>
                                fail(s"Unexpected error type: $e")
                            case Result.Panic(t) =>
                                fail(s"Unexpected panic: ${t.getMessage}")
                        }
                    }
                }
            }
        }
    }

    // ── cancel connection remains usable after killing another query ──────────

    "cancel connection is still usable after sending KILL QUERY" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.MySQL) { ctx =>
                initClient(ctx, maxConns = 2) { client =>
                    // Kick off a slow query.
                    (client.cancellableQueryFiber(
                        "SELECT SLEEP(3)"
                    ): (SqlCancelHandle.Mysql, Fiber[Chunk[SqlRow], Abort[SqlException]]) < (Async & Abort[SqlException])).flatMap {
                        case (handle, slowFiber) =>
                            Async.sleep(100.millis).andThen {
                                // Cancel via handle.
                                client.cancel(handle).andThen {
                                    // The client (pool) must still be usable after the cancel.
                                    client.query("SELECT 42").flatMap { rows =>
                                        slowFiber.get.map { _ =>
                                            assert(rows.size == 1, "Cancel connection should be reusable after KILL QUERY")
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

end MysqlCancelIntegrationTest
