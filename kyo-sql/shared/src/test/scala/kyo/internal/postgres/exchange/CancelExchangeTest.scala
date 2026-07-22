package kyo.internal.postgres.exchange

import kyo.*
import kyo.EncodingRegistry
import kyo.OwnContainer
import kyo.internal.SqlSharedContainers
import kyo.internal.postgres.PostgresConnection
import kyo.net.NetTlsConfig

/** Integration tests for `CancelExchange` internal protocol mechanics.
  *
  * These leaves exercise internal `PostgresConnection` members (`processId`, `secretKey`, `cancel`, `simpleQuery`) and
  * `CancelExchange.cancel` directly. They live here because `private[kyo]` grants access to all `kyo.*` sub-packages, and promoting these
  * internals to a public API is not warranted, they test the wire-protocol cancel mechanism, not a user-facing feature.
  *
  * Tests:
  *   1. cancel interrupts a slow query, pg_sleep(10) aborts with SQLSTATE 57014.
  *   2. cancel after query completes is a no-op, no error from cancel itself.
  *   3. cancel with wrong secretKey is silently rejected, connection remains usable.
  */
class CancelExchangeTest extends kyo.Test:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.init().flatMap(c => HttpClient.let(c)(body))

    override def timeout: Duration = 8.minutes

    // Helper: fresh plain connection scoped to the test's schema.
    private def withConn[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(f: PostgresConnection => A < (S & Async & Abort[SqlException]))(using
        Frame
    ): A < (S & Async & Scope & Abort[SqlException]) =
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
                Scope.ensure(Abort.run(conn.terminate).unit).andThen(f(conn))
            }

    "cancel interrupts a slow query, pg_sleep(10) aborts with SQLSTATE 57014".tagged("kyo.OwnContainer") in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { queryConn =>
                    withConn(ctx) { _ =>
                        val address = SqlConfig.Address("postgres", ctx.host, ctx.port, ctx.database, ctx.username)

                        // Fire a slow query in a background fiber; wrap with Abort.run so the fiber type has no error effect.
                        Fiber.init(Abort.run[SqlException](queryConn.simpleQuery("SELECT pg_sleep(10)"))).flatMap {
                            slowFiber =>
                                // Wait briefly, then cancel.
                                Async.sleep(200.millis).andThen {
                                    queryConn.cancel(address, Absent).andThen {
                                        // The slow fiber should complete with a SqlServerException(57014).
                                        slowFiber.get.map {
                                            case Result.Failure(e: SqlServerException) =>
                                                assert(
                                                    e.sqlState == "57014",
                                                    s"Expected SQLSTATE 57014 (query_canceled), got ${e.sqlState}: ${e.message}"
                                                )
                                            case Result.Failure(e) =>
                                                fail(s"Expected SqlServerException(57014), got: $e")
                                            case Result.Success(_) =>
                                                fail("Expected query to be cancelled but it returned successfully")
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
    }

    "cancel after query completes is a no-op, no error from cancel itself".tagged("kyo.OwnContainer") in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    val address = SqlConfig.Address("postgres", ctx.host, ctx.port, ctx.database, ctx.username)
                    // Run a fast query to completion.
                    conn.simpleQuery("SELECT 1").andThen {
                        // Cancel should be silently ignored, the query already finished.
                        Abort.run[SqlException](conn.cancel(address, Absent)).map {
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

    "cancel with wrong secretKey is silently rejected, connection remains usable".tagged("kyo.OwnContainer") in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                withConn(ctx) { conn =>
                    val address = SqlConfig.Address("postgres", ctx.host, ctx.port, ctx.database, ctx.username)
                    // Send a cancel with a deliberately wrong secret key (wrong key, same PID).
                    val wrongHandle = SqlClient.CancelHandle.Postgres(address, Absent, conn.processId, conn.secretKey ^ 0xdeadbeef)
                    // Issue cancel using the wrong handle, server ignores it silently.
                    Abort.run[SqlException](CancelExchange.cancel(
                        wrongHandle.address,
                        wrongHandle.tls,
                        wrongHandle.processId,
                        wrongHandle.secretKey
                    )).andThen {
                        // The connection is still usable after the silently-rejected cancel.
                        conn.simpleQuery("SELECT 1").map { rows =>
                            assert(rows.size == 1)
                        }
                    }
                }
            }
        }
    }

end CancelExchangeTest
