package kyo.postgres

import kyo.*
import kyo.internal.SqlSharedContainers
import kyo.internal.postgres.types.* // given PostgresDecoder[BigDecimal] for columnDecoded RHS

/** Integration test verifying that the NUMERIC round-trip works correctly against a real PostgreSQL server.
  *
  * Uses the Postgres extended-protocol via `SqlClient.query` and `SqlClient.execute`. Parameters are sent using `SqlSchema.BoundValue` +
  * `SqlSchema[BigDecimal]` (text-format NUMERIC). Results are received in binary format and decoded via `SqlRow.columnDecoded[BigDecimal]`.
  *
  * The random-round-trip test uses a fixed seed for determinism.
  */
class NumericBinaryIntegrationTest extends kyo.Test:

    private def pgUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    "round-trip 1000 randomized BigDecimals via PG NUMERIC binary format" in {
        Scope.run {
            Async.timeout(60.seconds) {
                SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                    SqlClient.initWith(
                        pgUrl(ctx),
                        SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                    ) { client =>
                        // Set up a table with a NUMERIC column of sufficient precision.
                        client.executeRaw("CREATE TABLE numeric_rt (id INT PRIMARY KEY, val NUMERIC(150, 100))").andThen {
                            Scope.ensure(Abort.run(client.executeRaw("DROP TABLE IF EXISTS numeric_rt")).unit).andThen {
                                // Generate 1000 deterministic BigDecimal values using a fixed seed.
                                val rng = new scala.util.Random(0xdeadbeefL)
                                val values = (0 until 1000).map { _ =>
                                    val scale = rng.nextInt(10) // 0..9 decimal places
                                    val unscaled = BigInt(rng.nextLong().abs % 1_000_000_000_000_000L) *
                                        (if rng.nextBoolean() then 1 else -1)
                                    BigDecimal(unscaled, scale)
                                }.toList

                                // Insert each value using text-format NUMERIC encoding via SqlSchema[BigDecimal].
                                Kyo.foreach(values.zipWithIndex) { case (v, i) =>
                                    client.execute(
                                        sql"INSERT INTO numeric_rt (id, val) VALUES ($i, $v)"
                                    )
                                }.andThen {
                                    // Read each value back and assert round-trip equality.
                                    Kyo.foreach(values.zipWithIndex) { case (expected, i) =>
                                        client.query(
                                            sql"SELECT val FROM numeric_rt WHERE id = $i"
                                        ).flatMap { rows =>
                                            assert(rows.size == 1, s"Expected 1 row for id=$i, got ${rows.size}")
                                            val row = rows(0)
                                            Abort.run[SqlDecodeException](row.columnDecoded[BigDecimal](0)).map {
                                                case Result.Success(actual) =>
                                                    assert(
                                                        actual.compare(expected) == 0,
                                                        s"Round-trip mismatch at id=$i: expected $expected, got $actual"
                                                    )
                                                case Result.Failure(e) =>
                                                    fail(s"Decode failed for id=$i (value $expected): $e")
                                                case Result.Panic(t) =>
                                                    throw t
                                            }
                                        }
                                    }.andThen {
                                        // Probe connection reusability before teardown.
                                        client.query("SELECT 1").map { rows =>
                                            assert(rows.size == 1, "Connection reusability probe failed")
                                        }
                                    }.map(_ => succeed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end NumericBinaryIntegrationTest
