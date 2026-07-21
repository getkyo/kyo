package kyo.mysql

import kyo.*
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend

/** Integration tests for the MySQL sequential pipeline API.
  *
  * MySQL does not support the PostgreSQL Sync-barrier batch-write protocol, so `SqlClient.pipeline` on a MySQL client executes statements
  * sequentially on the same connection using the extended (binary) protocol. Each statement is isolated: a per-statement server error is
  * recorded as [[SqlStatementResult.Failure]] without aborting subsequent statements.
  *
  * All tests run against a live MySQL container via [[SqlSharedContainers.withFreshSchema]].
  *
  * ==Test strategy==
  *
  * Each test acquires a fresh schema so table names never collide. A second [[SqlClient]] (single-connection pool) is used for out-of-band
  * verification (so the result is never read back through the same connection that wrote, ruling out write-buffer visibility bugs).
  */
class MysqlPipelineIntegrationTest extends kyo.Test:

    override def timeout: Duration = 8.minutes

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def withMyClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(f: SqlClient => A < (S & Async & Abort[SqlException]))(using
        Frame
    ): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlException.Connection](SqlClient.initMy(myUrl(ctx))).flatMap {
            case Result.Success(client) =>
                Scope.ensure(client.close).andThen(SqlClient.let(client)(f(client)))
            case Result.Failure(e) =>
                Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                Abort.error(Result.Panic(t))
        }

    private def withVerifyClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(
        f: SqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlException.Connection](
            SqlClient.initMy(
                myUrl(ctx),
                SqlClientConfig.default.copy(maxConnections = 1, minConnections = 1)
            )
        ).flatMap {
            case Result.Success(client) =>
                Scope.ensure(client.close).andThen(f(client))
            case Result.Failure(e) =>
                Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                Abort.error(Result.Panic(t))
        }

    /** Reads a single long value from the first column of the first row. */
    private def countVia(client: SqlClient, sql: String)(using Frame): Long < (Async & Abort[SqlException]) =
        client.query(sql).map { rows =>
            if rows.isEmpty then 0L
            else
                rows(0).column(0).fold(0L) { bytes =>
                    new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8).trim.toLong
                }
        }

    private def isSuccess(r: SqlStatementResult): Boolean = r match
        case _: SqlStatementResult.Success => true
        case _: SqlStatementResult.Failure => false

    private def isFailure(r: SqlStatementResult): Boolean = !isSuccess(r)

    private def affectedCount(r: SqlStatementResult): Long = r match
        case SqlStatementResult.Success(_, n) => n
        case _: SqlStatementResult.Failure    => -1L

    // ── Leaf 1: pipeline of 5 queries returns 5 results in order on MySQL ─────

    "pipeline of 5 INSERTs returns 5 results in order on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    withVerifyClient(ctx) { verify =>
                        Async.timeout(60.seconds) {
                            client
                                .executeRaw("CREATE TABLE pip_order (id INT PRIMARY KEY, val VARCHAR(64))")
                                .andThen {
                                    client.pipeline { p =>
                                        (1 to 5).foreach { i =>
                                            p.execute(s"INSERT INTO pip_order (id, val) VALUES ($i, 'row$i')")
                                        }
                                    }.map { results =>
                                        assert(results.size == 5, s"Expected 5 results, got ${results.size}")
                                        results.zipWithIndex.foreach { case (r, i) =>
                                            assert(isSuccess(r), s"Statement ${i + 1} failed: $r")
                                        }
                                    }
                                }
                                .andThen {
                                    countVia(verify, "SELECT COUNT(*) FROM pip_order").map { count =>
                                        assert(count == 5L, s"Expected 5 rows, got $count")
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 2: pipeline inside MySQL transaction commits atomically ──────────

    "pipeline inside MySQL transaction commits atomically" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    withVerifyClient(ctx) { verify =>
                        Async.timeout(60.seconds) {
                            client
                                .executeRaw("CREATE TABLE pip_tx (id INT PRIMARY KEY, val VARCHAR(64))")
                                .andThen {
                                    // Run pipeline inside a transaction.
                                    client.transaction {
                                        client.pipeline { p =>
                                            (1 to 3).foreach { i =>
                                                p.execute(s"INSERT INTO pip_tx (id, val) VALUES ($i, 'tx$i')")
                                            }
                                        }.map { results =>
                                            results.foreach(r =>
                                                assert(isSuccess(r), s"Pipeline statement failed inside tx: $r")
                                            )
                                        }
                                    }
                                }
                                .andThen {
                                    // After commit, all 3 rows are visible on the separate connection.
                                    countVia(verify, "SELECT COUNT(*) FROM pip_tx").map { count =>
                                        assert(count == 3L, s"Expected 3 rows after commit, got $count")
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    // ── Additional leaf: per-statement error isolation ────────────────────────

    "per-statement error in pipeline does not abort subsequent statements on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    withVerifyClient(ctx) { verify =>
                        Async.timeout(60.seconds) {
                            client
                                .executeRaw("CREATE TABLE pip_err (id INT PRIMARY KEY, val VARCHAR(64))")
                                .andThen {
                                    // Pre-insert id=2 to trigger a duplicate-key error on statement 2.
                                    client.executeRaw("INSERT INTO pip_err VALUES (2, 'pre')")
                                }
                                .andThen {
                                    client.pipeline { p =>
                                        p.execute("INSERT INTO pip_err (id, val) VALUES (1, 'a')") // success
                                        p.execute("INSERT INTO pip_err (id, val) VALUES (2, 'b')") // dup key error
                                        p.execute("INSERT INTO pip_err (id, val) VALUES (3, 'c')") // success
                                    }.map { results =>
                                        assert(results.size == 3, s"Expected 3 results, got ${results.size}")
                                        assert(isSuccess(results(0)), s"Statement 1 should succeed: ${results(0)}")
                                        assert(isFailure(results(1)), s"Statement 2 should fail (dup key): ${results(1)}")
                                        assert(isSuccess(results(2)), s"Statement 3 should succeed after error: ${results(2)}")
                                    }
                                }
                                .andThen {
                                    // Rows 1 and 3 inserted; row 2 has the pre-existing value.
                                    countVia(verify, "SELECT COUNT(*) FROM pip_err").map { total =>
                                        assert(total == 3L, s"Expected 3 total rows (1 pre + 2 inserted), got $total")
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    // ── Additional leaf: empty pipeline returns empty Chunk ───────────────────

    "empty pipeline returns empty Chunk on MySQL" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    Async.timeout(60.seconds) {
                        client.pipeline { _ =>
                            ()
                        }.map { results =>
                            assert(results.isEmpty, s"Expected empty Chunk, got ${results.size} elements")
                        }.andThen {
                            // Connection is reusable after empty pipeline.
                            client.executeRaw("SELECT 1").map { n =>
                                assert(n >= 0L)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Additional leaf: affected-row count is reported per-statement ─────────

    "pipeline reports correct affected-row count per statement" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    Async.timeout(60.seconds) {
                        client
                            .executeRaw("CREATE TABLE pip_aff (id INT PRIMARY KEY, val VARCHAR(64))")
                            .andThen {
                                client
                                    .executeRaw("INSERT INTO pip_aff VALUES (1, 'x'), (2, 'y'), (3, 'z')")
                            }
                            .andThen {
                                client.pipeline { p =>
                                    p.execute("UPDATE pip_aff SET val = 'updated' WHERE id = 1")   // 1 row
                                    p.execute("UPDATE pip_aff SET val = 'all' WHERE id IN (2, 3)") // 2 rows
                                }.map { results =>
                                    assert(results.size == 2, s"Expected 2 results, got ${results.size}")
                                    assert(isSuccess(results(0)), s"Statement 1 should succeed: ${results(0)}")
                                    assert(isSuccess(results(1)), s"Statement 2 should succeed: ${results(1)}")
                                    assert(
                                        affectedCount(results(0)) == 1L,
                                        s"Statement 1 should affect 1 row, got ${affectedCount(results(0))}"
                                    )
                                    assert(
                                        affectedCount(results(1)) == 2L,
                                        s"Statement 2 should affect 2 rows, got ${affectedCount(results(1))}"
                                    )
                                }
                            }
                    }
                }
            }
        }
    }

end MysqlPipelineIntegrationTest
