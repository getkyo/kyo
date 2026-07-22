package kyo.internal.postgres.exchange

import kyo.*
import kyo.internal.SqlSharedContainers

/** Integration tests for [[PipelineExchange]] internal instrumentation.
  *
  * These tests exercise `PipelineExchange.writeCount`, a `private[kyo]` atomic counter incremented once per successful TCP batch write in
  * `PipelineExchange.run`. The counter is inaccessible from `kyo.postgres.*` (different package), so these leaves live in
  * `kyo.internal.postgres.exchange` where `private[kyo]` grants access.
  */
class PipelineExchangeTest extends kyo.Test:

    override def timeout: Duration = 5.minutes

    /** Runs a block with a single SqlClient connected to the Postgres container. */
    private def withPg[A, S](
        f: SqlClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException] & Abort[SqlConnectionException] & Abort[ContainerException]) =
        SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
            val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
            SqlClient.init(url).flatMap { client =>
                SqlClient.let(client)(f(client))
            }
        }

    // ── writeCount increment on pipeline batch ────────────────────────────────

    "pipeline of 10 INSERTs uses one round-trip" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    // Reset the transport-level write counter before the pipeline call.
                    // PipelineExchange.run increments it once per TCP batch write.
                    PipelineExchange.writeCount.set(0)
                    client.executeRaw("CREATE TABLE pip_rtt (id INT PRIMARY KEY, val TEXT)").andThen {
                        client.pipeline { p =>
                            // All 10 registrations are pure (no Kyo effects); the batch is flushed in ONE TCP write.
                            (1 to 10).foreach { i =>
                                p.execute(s"INSERT INTO pip_rtt (id, val) VALUES ($i, 'v$i')")
                            }
                        }.map { results =>
                            // The transport incremented writeCount exactly once for the 10-INSERT batch.
                            assert(
                                PipelineExchange.writeCount.get() == 1,
                                s"Expected 1 TCP batch write for 10 INSERTs, got ${PipelineExchange.writeCount.get()}"
                            )
                            assert(results.size == 10, s"Expected 10 results, got ${results.size}")
                            results.zipWithIndex.foreach { case (r, i) =>
                                assert(r.isSuccess, s"Statement ${i + 1} failed: $r")
                            }
                        }.andThen {
                            // Reusability probe: verification query sees the rows via pool connection.
                            client.query("SELECT COUNT(*) FROM pip_rtt").flatMap { rows =>
                                rows(0).decode[Long](0).map { count =>
                                    assert(count == 10L, s"Expected 10 rows in pip_rtt, got $count")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── writeCount stays zero for a non-pipelined query ───────────────────────

    "PipelineExchange.writeCount stays zero for a single non-pipelined query" in {
        Scope.run {
            withPg { client =>
                Async.timeout(60.seconds) {
                    // Reset the counter, then run a plain (non-pipelined) query.
                    // PipelineExchange.run must NOT be invoked; the counter must remain 0.
                    PipelineExchange.writeCount.set(0)
                    client.query("SELECT 1").map { rows =>
                        assert(rows.nonEmpty)
                        assert(
                            PipelineExchange.writeCount.get() == 0,
                            s"Expected 0 TCP pipeline writes for a non-pipelined query, got ${PipelineExchange.writeCount.get()}"
                        )
                    }
                }
            }
        }
    }

end PipelineExchangeTest
