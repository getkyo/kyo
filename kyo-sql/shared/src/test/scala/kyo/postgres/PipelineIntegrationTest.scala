package kyo.postgres

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for PostgreSQL extended-protocol pipeline mode.
  *
  * All 10 tests run against a real PostgreSQL container via [[SqlClient.pipeline]].
  *
  * Each test is wrapped in `Async.timeout(60.seconds)`. After each container test a follow-up SELECT verifies connection reusability.
  *
  * ==Table strategy==
  *
  * Tests use PERMANENT tables (not TEMP) so that all verification queries via `client.query` can see the same data. Each test runs against
  * a fresh schema (via [[SqlSharedContainers.withFreshSchema]]); the schema is dropped on test exit, so table names don't collide across
  * tests.
  */
class PipelineIntegrationTest extends kyo.Test:

    override def timeout: Duration = 8.minutes

    /** Runs a block with a single SqlClient connected to the container.
      *
      * The `client` is used for DDL, the pipeline API under test (`client.pipeline`, `client.executeRaw`), and all verification queries
      * (`client.query`). Tables must be permanent (not TEMP) so that pool connections have full visibility.
      */
    private def withPg[A, S](
        f: SqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException] & Abort[SqlException.Connection] & Abort[ContainerException]) =
        SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
            val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
            SqlClient.init(url).flatMap { client =>
                SqlClient.let(client)(f(client))
            }
        }

    /** Decodes the first column of a row as a UTF-8 string (text format). */
    private def decodeStr(column: Maybe[Span[Byte]]): String =
        column.map(s => new String(s.toArray, java.nio.charset.StandardCharsets.UTF_8)).getOrElse("")

    /** Runs a COUNT(*) query via `client.query` and returns the count using the typed decoder. */
    private def simpleCount(client: SqlClient, table: String)(using
        Frame
    ): Long < (Async & Abort[SqlException] & Abort[SqlException.Decode]) =
        client.query(s"SELECT COUNT(*) FROM $table").flatMap { rows =>
            if rows.isEmpty then 0L
            else rows(0).decode[Long](0)
        }

    /** Runs a SELECT via `client.query` and returns the first column of all rows as strings, decoding each integer column via the typed
      * decoder.
      */
    private def simpleStrCol(client: SqlClient, sql: String)(using
        Frame
    ): Chunk[String] < (Async & Abort[SqlException] & Abort[SqlException.Decode]) =
        client.query(sql).flatMap { rows =>
            Kyo.foreach(rows)(r => r.decode[Int](0).map(_.toString))
        }

    /** Returns true if the [[SqlStatementResult]] represents a successful execution. */
    private def isSuccess(r: SqlStatementResult): Boolean = r match
        case _: SqlStatementResult.Success => true
        case _: SqlStatementResult.Failure => false

    /** Returns true if the [[SqlStatementResult]] represents a failure. */
    private def isFailure(r: SqlStatementResult): Boolean = !isSuccess(r)

    /** Extracts the affected row count from a successful [[SqlStatementResult]], defaulting to -1 on failure. */
    private def affectedCount(r: SqlStatementResult): Long = r match
        case SqlStatementResult.Success(_, n) => n
        case _: SqlStatementResult.Failure    => -1L

    // ── Base test leaves ───────────────────────────────────────────────────────

    "pipeline preserves submission order" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    client.executeRaw("CREATE TABLE pip_order (id INT PRIMARY KEY, val TEXT)").andThen {
                        // Insert rows 1..20 in order via pipeline.
                        client.pipeline { p =>
                            (1 to 20).foreach { i =>
                                p.execute(s"INSERT INTO pip_order (id, val) VALUES ($i, 'row$i')")
                            }
                        }.andThen {
                            // Read them back and verify ascending id order.
                            simpleStrCol(client, "SELECT id FROM pip_order ORDER BY id").map { ids =>
                                assert(ids.size == 20, s"Expected 20 rows, got ${ids.size}")
                                val expected = Chunk.from((1 to 20).map(_.toString))
                                assert(ids == expected, s"Order mismatch: $ids")
                            }
                        }.andThen {
                            // Reusability probe.
                            client.query("SELECT 1 AS ok").map { rows =>
                                assert(rows.nonEmpty)
                            }
                        }
                    }
                }
            }
        }
    }

    "error on the 5th statement of a pipeline still allows statements 6..10 to complete" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    // id=5 will duplicate-insert to force a unique-constraint error on statement 5.
                    client.executeRaw("CREATE TABLE pip_err (id INT PRIMARY KEY, val TEXT)").andThen {
                        client.executeRaw("INSERT INTO pip_err (id, val) VALUES (5, 'pre')").andThen {
                            client.pipeline { p =>
                                (1 to 10).foreach { i =>
                                    // Statement 5 will violate the PK constraint (id=5 already exists).
                                    p.execute(s"INSERT INTO pip_err (id, val) VALUES ($i, 'row$i')")
                                }
                            }.map { results =>
                                assert(results.size == 10, s"Expected 10 results, got ${results.size}")
                                // Statements 1..4 succeed (ids 1-4 don't exist yet).
                                (0 until 4).foreach { i =>
                                    assert(isSuccess(results(i)), s"Statement ${i + 1} should succeed, got ${results(i)}")
                                }
                                // Statement 5 fails (duplicate key).
                                assert(isFailure(results(4)), s"Statement 5 should fail (duplicate key), got ${results(4)}")
                                // Statements 6..10 succeed (ids 6-10 don't exist).
                                (5 until 10).foreach { i =>
                                    assert(isSuccess(results(i)), s"Statement ${i + 1} should succeed after error, got ${results(i)}")
                                }
                            }.andThen {
                                // Verify rows 6..10 are present (pipeline continued after statement 5's error).
                                client.query("SELECT COUNT(*) FROM pip_err WHERE id >= 6").flatMap { rows =>
                                    rows(0).decode[Long](0).map { count =>
                                        assert(count == 5L, s"Expected 5 rows with id >= 6, got $count")
                                    }
                                }
                            }.andThen {
                                // Reusability probe.
                                client.query("SELECT 1").map { rows =>
                                    assert(rows.nonEmpty)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "pipeline executed inside a transaction commits atomically" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    client.executeRaw("CREATE TABLE pip_tx (id INT PRIMARY KEY, val TEXT)").andThen {
                        // Run 5 INSERTs inside a transaction via pipeline.
                        client.transaction {
                            client.pipeline { p =>
                                (1 to 5).foreach { i =>
                                    p.execute(s"INSERT INTO pip_tx (id, val) VALUES ($i, 'tx$i')")
                                }
                            }.map { results =>
                                results.foreach(r => assert(isSuccess(r), s"Pipeline statement failed inside tx: $r"))
                            }
                        }.andThen {
                            // After commit, all 5 rows are visible.
                            simpleCount(client, "pip_tx").map { count =>
                                assert(count == 5L, s"Expected 5 rows after tx commit, got $count")
                            }
                        }.andThen {
                            // Reusability probe.
                            client.query("SELECT 42").map { rows =>
                                assert(rows.nonEmpty)
                            }
                        }
                    }
                }
            }
        }
    }

    "pipeline with a single statement matches non-pipeline result" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    client.executeRaw("CREATE TABLE pip_single (id INT PRIMARY KEY, val TEXT)").andThen {
                        // Run a single INSERT via pipeline.
                        client.pipeline { p =>
                            p.execute("INSERT INTO pip_single (id, val) VALUES (1, 'solo')")
                        }.flatMap { pipeResults =>
                            // Run the equivalent non-pipeline execute.
                            client.execute("INSERT INTO pip_single (id, val) VALUES (2, 'solo2')").map { directCount =>
                                assert(pipeResults.size == 1, s"Expected 1 pipeline result, got ${pipeResults.size}")
                                assert(isSuccess(pipeResults(0)), s"Pipeline result should succeed: ${pipeResults(0)}")
                                val pipeCount = affectedCount(pipeResults(0))
                                assert(pipeCount == directCount, s"Pipeline affected count $pipeCount != direct $directCount")
                            }
                        }.andThen {
                            // Reusability probe.
                            simpleCount(client, "pip_single").map { count =>
                                assert(count == 2L, s"Expected 2 rows, got $count")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Audit-mandated additional leaves ──────────────────────────────────────

    "pipeline of 200 statements completes in one logical batch" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    client.executeRaw("CREATE TABLE pip_200 (id INT PRIMARY KEY, val TEXT)").andThen {
                        client.pipeline { p =>
                            (1 to 200).foreach { i =>
                                p.execute(s"INSERT INTO pip_200 (id, val) VALUES ($i, 'r$i')")
                            }
                        }.map { results =>
                            assert(results.size == 200, s"Expected 200 results, got ${results.size}")
                            val failures = results.zipWithIndex.filter { case (r, _) => isFailure(r) }
                            assert(failures.isEmpty, s"Unexpected failures: ${failures.take(5)}")
                        }.andThen {
                            simpleCount(client, "pip_200").map { count =>
                                assert(count == 200L, s"Expected 200 rows, got $count")
                            }
                        }.andThen {
                            // Reusability probe.
                            client.query("SELECT 1").map { rows =>
                                assert(rows.nonEmpty)
                            }
                        }
                    }
                }
            }
        }
    }

    "pipeline cancellation drains protocol cleanly; connection is reusable for a follow-up SELECT" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    // Launch a slow pipeline (10 × pg_sleep(2) = 20 seconds minimum).
                    // Race it against a 300ms hard timeout to force mid-flight cancellation.
                    // After the Abort[Timeout] fires, the pool's releaseOnExit discards the dirty
                    // connection and returns the slot to the pool so the follow-up can acquire a
                    // fresh connection from the SAME pool (same SqlClient instance).
                    Abort.run[Timeout] {
                        Async.timeout(300.millis) {
                            client.pipeline { p =>
                                (1 to 10).foreach { i =>
                                    // Each statement executes pg_sleep on the server side.
                                    // The pipeline write is fast; the reads block on the server.
                                    p.query(s"SELECT pg_sleep(2), $i AS idx")
                                }
                            }
                        }
                    }.map { cancelResult =>
                        // Expect a Timeout failure (cancelled before 20s). isSuccess means the pipeline
                        // somehow finished before 300ms, that is also fine (proves reusability still works).
                        assert(
                            cancelResult.isFailure,
                            s"Expected timeout (cancellation MUST fire, pipeline is 20s, timeout is 300ms), got: $cancelResult"
                        )
                    }.andThen {
                        // Follow-up via the SAME pool (same SqlClient, same pool slot).
                        // The pool discards dirty connections on error; this acquires a fresh connection.
                        // If the pool slot was not returned (e.g., slot leak), this call would hang or fail.
                        client.query("SELECT 1 AS ok").map { rows =>
                            assert(rows.size == 1, s"Expected 1 row from follow-up SELECT 1, got ${rows.size}")
                        }
                    }
                }.andThen {
                    // Independent reusability probe on a fresh pool connection.
                    client.query("SELECT 1").map { rows =>
                        assert(rows.nonEmpty)
                    }
                }
            }
        }
    }

    "pipeline empty body returns empty Chunk" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    client.pipeline { _ =>
                        // Body registers no statements.
                        ()
                    }.map { results =>
                        assert(results.isEmpty, s"Expected empty Chunk for empty pipeline, got ${results.size} elements")
                    }.andThen {
                        // Reusability probe: a real query after the empty pipeline works.
                        client.query("SELECT 7").flatMap { rows =>
                            rows(0).decode[Int](0).map { v =>
                                assert(rows.nonEmpty)
                                assert(v == 7, s"Expected 7, got $v")
                            }
                        }
                    }
                }
            }
        }
    }

    "pipeline with mixed success+error+success preserves per-statement results" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    // Table: id is PK. Pre-insert id=3 to force error on stmt 3.
                    client.executeRaw("CREATE TABLE pip_mixed (id INT PRIMARY KEY, val TEXT)").andThen {
                        client.executeRaw("INSERT INTO pip_mixed (id, val) VALUES (3, 'pre')").andThen {
                            client.pipeline { p =>
                                p.execute("INSERT INTO pip_mixed (id, val) VALUES (1, 'a')") // success
                                p.execute("INSERT INTO pip_mixed (id, val) VALUES (2, 'b')") // success
                                p.execute("INSERT INTO pip_mixed (id, val) VALUES (3, 'c')") // error, dup key
                                p.execute("INSERT INTO pip_mixed (id, val) VALUES (4, 'd')") // success
                                p.execute("INSERT INTO pip_mixed (id, val) VALUES (5, 'e')") // success
                            }.map { results =>
                                assert(results.size == 5, s"Expected 5 results, got ${results.size}")
                                // Ordered exact match:
                                assert(isSuccess(results(0)), s"stmt 1 should succeed: ${results(0)}")
                                assert(isSuccess(results(1)), s"stmt 2 should succeed: ${results(1)}")
                                assert(isFailure(results(2)), s"stmt 3 should fail (dup key): ${results(2)}")
                                assert(isSuccess(results(3)), s"stmt 4 should succeed: ${results(3)}")
                                assert(isSuccess(results(4)), s"stmt 5 should succeed: ${results(4)}")
                            }.andThen {
                                // Rows 1, 2, 4, 5 are present; row 3 was pre-inserted (not replaced).
                                simpleStrCol(client, "SELECT id FROM pip_mixed ORDER BY id").map { ids =>
                                    val expected = Chunk("1", "2", "3", "4", "5")
                                    assert(ids == expected, s"Expected ids [1,2,3,4,5], got $ids")
                                }
                            }.andThen {
                                // Reusability probe.
                                client.query("SELECT 1").map { rows =>
                                    assert(rows.nonEmpty)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "pipeline with prepared-statement (extended-protocol) Bind reuse returns correct row count per Bind" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    client.executeRaw("CREATE TABLE pip_prep (id INT PRIMARY KEY, val TEXT)").andThen {
                        // Use three distinct SQL literals for the first pipeline.
                        // The prepared-statement cache stores them by SQL hash.
                        client.pipeline { p =>
                            p.execute("INSERT INTO pip_prep (id, val) VALUES (1, 'x')")
                            p.execute("INSERT INTO pip_prep (id, val) VALUES (2, 'y')")
                            p.execute("INSERT INTO pip_prep (id, val) VALUES (3, 'z')")
                        }.flatMap { firstResults =>
                            assert(firstResults.size == 3, s"Expected 3 results from first pipeline, got ${firstResults.size}")
                            firstResults.foreach(r => assert(isSuccess(r), s"First pipeline result failed: $r"))

                            // Second pipeline on a separate table, reuses the same prepared statements.
                            client.executeRaw("CREATE TABLE pip_prep2 (id INT PRIMARY KEY, val TEXT)").andThen {
                                client.pipeline { p =>
                                    p.execute("INSERT INTO pip_prep2 (id, val) VALUES (10, 'a')")
                                    p.execute("INSERT INTO pip_prep2 (id, val) VALUES (20, 'b')")
                                    p.execute("INSERT INTO pip_prep2 (id, val) VALUES (30, 'c')")
                                }.map { secondResults =>
                                    assert(secondResults.size == 3, s"Expected 3 results from second pipeline, got ${secondResults.size}")
                                    // Each Bind should return affected count = 1.
                                    secondResults.zipWithIndex.foreach { case (r, i) =>
                                        assert(isSuccess(r), s"Second pipeline stmt ${i + 1} failed: $r")
                                        val count = affectedCount(r)
                                        assert(count == 1L, s"Expected affected count 1 for stmt ${i + 1}, got $count")
                                    }
                                }
                            }
                        }.andThen {
                            // Final reusability probe.
                            simpleCount(client, "pip_prep").map { count =>
                                assert(count == 3L, s"Expected 3 rows in pip_prep, got $count")
                            }
                        }
                    }
                }
            }
        }
    }

    // Verify that both SqlPipelineBuilder.execute overloads compile:
    //   execute(sql)         , no-params form delegates to execute(sql, Chunk.empty)
    //   execute(sql, params) , explicit-params canonical form
    // No integration call is needed; the test exercises the API surface itself.
    "SqlPipelineBuilder execute(sql) and execute(sql, params) both compile" in {
        val builder = new SqlPipelineBuilder
        builder.execute("SELECT 1")
        builder.execute("SELECT $1", Seq.empty[SqlSchema.BoundValue[?]])
        builder.query("SELECT 2")
        builder.query("SELECT $1", Seq.empty[SqlSchema.BoundValue[?]])
        val stmts = builder.drainStmts()
        assert(stmts.size == 4)
        succeed
    }

end PipelineIntegrationTest
