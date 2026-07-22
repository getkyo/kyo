package kyo.postgres

import kyo.*
import kyo.OwnContainer

/** Integration tests for retry policy via kyo.Retry + kyo.Schedule.
  *
  * Tests use a real Postgres container and exercise the SqlClient's retrySchedule behaviour by briefly making the server unresponsive (via
  * Container.pause/unpause) and confirming that the retry schedule bridges the downtime window.
  *
  * Test discipline:
  *   - All container tests wrapped in Async.timeout(60.seconds).
  *   - Retry counts verified via SqlClient.Metrics counters, never wall-clock assertions.
  *   - After every container test, one probe query verifies connection reusability.
  *
  * Container ownership: this suite mutates server state via `Container.pause` / `Container.unpause`. It cannot share the per-fork-JVM
  * Postgres singleton with other tests because pausing the singleton would freeze every concurrent caller. Each leaf therefore starts its
  * own container via [[ContainerPredef.Postgres.initWith]]; the suite is a candidate for the `OwnContainer` tag introduced in a later
  * phase.
  */
class RetryIntegrationTest extends kyo.Test:

    override def timeout: Duration = 5.minutes

    /** Helper: build a postgres:// URL from ContainerPredef.Postgres. */
    private def pgUrl(pg: ContainerPredef.Postgres, port: Int): String =
        s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}"

    /** Helper: initialise a Postgres-backed SqlClient and pass it to f. */
    private def withPgClient[A, S](
        url: String,
        config: SqlConfig
    )(f: SqlClient => A < (S & Async & Abort[SqlException]))(using Frame): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlConnectionException](SqlClient.init(url, config)).flatMap {
            case Result.Success(client) => SqlClient.let(client)(f(client))
            case Result.Failure(e)      => Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                scala.Console.err.println(s"[kyo-sql] RetryIntegrationTest.withPgClient panic: ${t.getMessage}")
                Abort.fail(SqlConnectionConnectFailedException("test", 0, new Exception(t.getMessage)))
        }

    // ── pause+unpause Postgres mid-query causes one retry then succeeds ────────

    /** Validates the four-layer retry chain (Retry → withSlot → pool → connect+execute) by briefly making the server unresponsive
      * mid-flight via `Container.pause` and confirming `Retry[SqlConnectionException]` bridges the downtime.
      *
      * Why pause/unpause and not stop/start: Docker Desktop on macOS reassigns the host port when a container is restarted, so the
      * SqlClient's cached URL would point at a dead port forever. `pause` keeps the container/port mapping intact while suspending the
      * postgres process, new connections succeed at the kernel TCP level but the server never responds to startup, exercising the same
      * `acquireTimeout`-bounded `pgConnect` path that the production fix protects against.
      */
    "Container.pause then unpause mid-query: Retry bridges the downtime and produces >= 1 retry".tagged("kyo.OwnContainer") in {
        Scope.run {
            Async.timeout(60.seconds) {
                ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
                    pg.container.mappedPort(pg.config.port).flatMap { port =>
                        val url = pgUrl(pg, port)
                        // acquireTimeout = 1 second so paused-server connect attempts time out quickly
                        // and the retry schedule cycles through several attempts within the unpause window.
                        // idleTimeout = 1.nanos forces every connection to be re-established (any pooled connection
                        // is discarded as expired on poll). This ensures the second query goes through the
                        // connect-and-startup path while the server is paused, exercising the SqlConnectionException
                        // → Retry path that the production fix protects against.
                        val config = SqlConfig(
                            maxConnections = 4,
                            acquireTimeout = 1.second,
                            queryTimeout = 5.seconds,
                            idleTimeout = 1.nano,
                            retrySchedule = Present(Schedule.fixed(500.millis).take(30)),
                            metricsEnabled = true
                        )
                        withPgClient(url, config) { client =>
                            // Warm up: confirms the server is up and connectable.
                            client.query("SELECT 1").flatMap { _ =>
                                // Pause the container, new TCP connections will succeed at the kernel proxy
                                // but the server never responds to StartupExchange, so each connect attempt
                                // hits acquireTimeout and aborts with SqlConnectionException.
                                Abort.run[ContainerException](pg.container.pause).flatMap {
                                    case Result.Failure(e) =>
                                        Abort.fail(SqlConnectionConnectFailedException(
                                            "test",
                                            0,
                                            new Exception(s"pause failed: $e")
                                        ): SqlException)
                                    case Result.Panic(t) =>
                                        scala.Console.err.println(s"[kyo-sql] RetryIntegrationTest: pause panic: ${t.getMessage}")
                                        Abort.fail(SqlConnectionConnectFailedException(
                                            "test",
                                            0,
                                            new Exception(s"pause panic: ${t.getMessage}")
                                        ): SqlException)
                                    case Result.Success(_) =>
                                        // Schedule an unpause partway through the retry schedule so the server
                                        // becomes responsive while Retry is still active.
                                        Fiber.initUnscoped(
                                            Async.delay(3.seconds) {
                                                Abort.run[ContainerException](pg.container.unpause).unit
                                            }
                                        ).flatMap { _ =>
                                            // Issue a query; Retry[SqlConnectionException] bridges the downtime.
                                            client.query("SELECT 42").flatMap { rows =>
                                                client.metrics.retriesAttempted.get.map { ra =>
                                                    assert(ra >= 1, s"expected >= 1 retry, got $ra")
                                                    assert(rows.nonEmpty, "expected SELECT 42 to return rows")
                                                }
                                            }
                                        }
                                }
                            }.andThen {
                                // Probe connection reusability after the retry storm, confirms the pool
                                // recovers fully and a fresh connect+query succeeds against the live server.
                                client.query("SELECT 7").map { rows =>
                                    assert(rows.nonEmpty, "probe query SELECT 7 returned no rows")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end RetryIntegrationTest
