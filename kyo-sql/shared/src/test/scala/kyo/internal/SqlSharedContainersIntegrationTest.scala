package kyo.internal

import kyo.*
import kyo.EncodingRegistry
import kyo.internal.SqlSharedContainers.Backend
import kyo.internal.SqlSharedContainers.SchemaCtx
import kyo.internal.postgres.PostgresConnection

/** End-to-end integration test for [[SqlSharedContainers]]. Validates that:
  *
  *   - The shared PG / MySQL singleton containers boot once and serve fresh schemas per call.
  *   - DROP DATABASE runs on exit even when the body throws.
  *   - Concurrent calls produce isolated schemas (no cross-contamination).
  */
class SqlSharedContainersIntegrationTest extends kyo.Test:

    final private case class FailureWithSchema(schema: String) extends RuntimeException(s"intentional $schema")

    /** Decode a non-NULL text-format INT8 column as a Long. */
    private def decodeLong(bytes: Span[Byte]): Long =
        new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8).trim.toLong

    "withFreshSchema(Postgres) creates and drops a schema" in {
        Async.timeout(180.seconds) {
            Scope.run {
                SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                    import kyo.internal.postgres.PostgresConnection
                    PostgresConnection
                        .connect(
                            ctx.host,
                            ctx.port,
                            ctx.username,
                            ctx.database,
                            Present(ctx.password),
                            Absent,
                            64,
                            Duration.Infinity,
                            EncodingRegistry.builtin
                        )
                        .flatMap { conn =>
                            Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                                for
                                    _    <- conn.simpleExecute("CREATE TABLE smoke (id INT)")
                                    _    <- conn.simpleExecute("INSERT INTO smoke VALUES (1)")
                                    rows <- conn.simpleQuery("SELECT count(*) FROM smoke")
                                yield
                                    val count = rows(0).column(0) match
                                        case Present(b) => decodeLong(b)
                                        case Absent     => fail("count(*) was NULL")
                                    assert(count == 1L)
                            }
                        }
                }
            }
        }
    }

    "withFreshSchema(MySQL) creates and drops a schema" in {
        Async.timeout(180.seconds) {
            Scope.run {
                SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                    import kyo.internal.mysql.MysqlConnection
                    MysqlConnection
                        .connect(
                            ctx.host,
                            ctx.port,
                            ctx.username,
                            Present(ctx.password),
                            Present(ctx.database),
                            Absent,
                            64,
                            Duration.Infinity
                        )
                        .flatMap { conn =>
                            Scope.ensure(Abort.run(conn.quit()).unit).andThen {
                                for
                                    _    <- conn.simpleExecute("CREATE TABLE smoke (id INT)")
                                    _    <- conn.simpleExecute("INSERT INTO smoke VALUES (1)")
                                    rows <- conn.simpleQuery("SELECT count(*) FROM smoke")
                                yield
                                    val count = rows(0).column(0) match
                                        case Present(b) => decodeLong(b)
                                        case Absent     => fail("count(*) was NULL")
                                    assert(count == 1L)
                            }
                        }
                }
            }
        }
    }

    "withFreshSchema(Postgres) drops the schema even when the body fails" in {
        Async.timeout(180.seconds) {
            // Run a body that fails after capturing its schema name.
            Scope.run {
                Abort.run[Throwable](
                    SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                        Abort.fail[Throwable](FailureWithSchema(ctx.database))
                    }
                )
            }.flatMap { failed =>
                val failedSchema = failed match
                    case Result.Failure(FailureWithSchema(s)) => s
                    case other                                => fail(s"expected FailureWithSchema, got $other")
                // Open an admin connection via a fresh withFreshSchema call and
                // assert the failed schema no longer appears in pg_database.
                Scope.run {
                    SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                        import kyo.internal.postgres.PostgresConnection
                        PostgresConnection
                            .connect(
                                ctx.host,
                                ctx.port,
                                ctx.username,
                                ctx.database,
                                Present(ctx.password),
                                Absent,
                                64,
                                Duration.Infinity,
                                EncodingRegistry.builtin
                            )
                            .flatMap { conn =>
                                Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                                    conn.simpleQuery(
                                        s"SELECT datname FROM pg_database WHERE datname = '$failedSchema'"
                                    ).map { rows =>
                                        assert(rows.isEmpty, s"failed schema $failedSchema still exists")
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    "withFreshSchema(MySQL) drops the schema even when the body fails" in {
        Async.timeout(180.seconds) {
            Scope.run {
                Abort.run[Throwable](
                    SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                        Abort.fail[Throwable](FailureWithSchema(ctx.database))
                    }
                )
            }.flatMap { failed =>
                val failedSchema = failed match
                    case Result.Failure(FailureWithSchema(s)) => s
                    case other                                => fail(s"expected FailureWithSchema, got $other")
                Scope.run {
                    SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                        import kyo.internal.mysql.MysqlConnection
                        MysqlConnection
                            .connect(
                                ctx.host,
                                ctx.port,
                                ctx.username,
                                Present(ctx.password),
                                Present(ctx.database),
                                Absent,
                                64,
                                Duration.Infinity
                            )
                            .flatMap { conn =>
                                Scope.ensure(Abort.run(conn.quit()).unit).andThen {
                                    conn.simpleQuery(
                                        s"SELECT schema_name FROM information_schema.schemata " +
                                            s"WHERE schema_name = '$failedSchema'"
                                    ).map { rows =>
                                        assert(rows.isEmpty, s"failed schema $failedSchema still exists")
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    "concurrent withFreshSchema calls do not collide" in {
        Async.timeout(180.seconds) {
            Async.fill(8, concurrency = 8) {
                Scope.run {
                    SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                        import kyo.internal.mysql.MysqlConnection
                        MysqlConnection
                            .connect(
                                ctx.host,
                                ctx.port,
                                ctx.username,
                                Present(ctx.password),
                                Present(ctx.database),
                                Absent,
                                64,
                                Duration.Infinity
                            )
                            .flatMap { conn =>
                                Scope.ensure(Abort.run(conn.quit()).unit).andThen {
                                    for
                                        _    <- conn.simpleExecute("CREATE TABLE smoke (id INT)")
                                        _    <- conn.simpleExecute("INSERT INTO smoke VALUES (1)")
                                        rows <- conn.simpleQuery("SELECT count(*) FROM smoke")
                                    yield rows(0).column(0) match
                                        case Present(b) => decodeLong(b)
                                        case Absent     => fail("count(*) was NULL")
                                }
                            }
                    }
                }
            }.map { counts =>
                assert(counts.size == 8, s"expected 8 fibers, got ${counts.size}")
                counts.foreach(c => assert(c == 1L, s"expected exactly 1 row per schema, got $c"))
                succeed
            }
        }
    }

end SqlSharedContainersIntegrationTest
