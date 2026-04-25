package demo

import kyo.*

/** Testcontainers-style integration test scaffold.
  *
  * Spins up Postgres + Redis on a shared user-defined network, waits for both to be healthy, then runs a test body that can talk to both
  * via DNS aliases. All resources are cleaned up through Scope when the body returns.
  *
  * Demonstrates: user-defined networks, DNS aliases, exec-based health checks with retry schedules, `awaitHealthy`, multi-container
  * orchestration, `Config.NetworkMode.Custom`, `initWith`.
  */
object IntegrationTestScaffold extends KyoApp:

    val netName       = "kyo-pod-demo-it"
    val postgresUser  = "kyo"
    val postgresPass  = "kyo"
    val postgresDb    = "kyo"
    val postgresAlias = "postgres"
    val redisAlias    = "redis"

    /** Spin up Postgres + Redis on a shared network, run `body` with both healthy, then clean everything up. */
    def withStack[A, S](
        body: (Container, Container) => A < S
    )(using Frame): A < (S & Async & Abort[ContainerException] & Scope) =
        Container.Network.init(Container.Network.Config.default.copy(name = netName).label("demo", "integration-test-scaffold")).map { _ =>
            // postgres:16-alpine's default entrypoint runs initdb on first start and creates
            // POSTGRES_DB before opening the real listener. The naive `pg_isready` healthcheck
            // races against init scripts — it returns ready during the temporary listener phase,
            // before POSTGRES_DB exists. A `psql -c "SELECT 1"` probe against the configured
            // database can only succeed once init scripts have completed.
            val pgConfig = Container.Config.default.copy(
                image = ContainerImage("postgres:16-alpine"),
                name = Present(postgresAlias),
                env = Dict(
                    "POSTGRES_USER"     -> postgresUser,
                    "POSTGRES_PASSWORD" -> postgresPass,
                    "POSTGRES_DB"       -> postgresDb
                ),
                healthCheck = Container.HealthCheck.exec(
                    Command("psql", "-U", postgresUser, "-d", postgresDb, "-c", "SELECT 1"),
                    expected = Absent,
                    retrySchedule = Schedule.fixed(500.millis).take(60)
                )
            ).networkMode(Container.Config.NetworkMode.Custom(netName))

            // redis:7-alpine's default CMD runs redis-server.
            val redisConfig = Container.Config.default.copy(
                image = ContainerImage("redis:7-alpine"),
                name = Present(redisAlias),
                healthCheck = Container.HealthCheck.exec(
                    Command("redis-cli", "ping"),
                    expected = Absent,
                    retrySchedule = Schedule.fixed(200.millis).take(60)
                )
            ).networkMode(Container.Config.NetworkMode.Custom(netName))

            // Containers resolve each other by container name — NetworkMode.Custom attaches them
            // with the container name as the default DNS alias.
            Container.initWith(pgConfig) { pg =>
                Container.initWith(redisConfig) { redis =>
                    pg.awaitHealthy.andThen {
                        redis.awaitHealthy.andThen {
                            body(pg, redis)
                        }
                    }
                }
            }
        }

    /** Example test body: verifies both containers are up by exec-ing real client tools, then proves cross-container DNS resolution by
      * having the Postgres container reach Redis via the network alias.
      */
    def exampleTest(pg: Container, redis: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
        for
            pgRes    <- pg.exec("psql", "-U", postgresUser, "-d", postgresDb, "-c", "SELECT 1")
            redisRes <- redis.exec("redis-cli", "ping")
            // Cross-container reachability: Postgres image lacks `nc`, but `getent hosts redis`
            // returns 0 only if DNS resolves. If even getent isn't available, fall back to
            // bash's /dev/tcp probe.
            crossRes <- pg.exec("sh", "-c", s"getent hosts $redisAlias || (exec 3<>/dev/tcp/$redisAlias/6379 && echo connected)")
            _        <- Console.printLine(s"[postgres] ready=${pgRes.exitCode.isSuccess} exit=${pgRes.exitCode.toInt}")
            _ <- Console.printLine(
                s"[redis]    ready=${redisRes.exitCode.isSuccess && redisRes.stdout.trim.equalsIgnoreCase("PONG")} stdout='${redisRes.stdout.trim}'"
            )
            _ <- Console.printLine(s"[dns]      pg→redis exit=${crossRes.exitCode.toInt} stdout='${crossRes.stdout.trim}'")
        yield ()
    end exampleTest

    run {
        Console.printLine("[scaffold] starting Postgres + Redis...").andThen {
            withStack { (pg, redis) =>
                Console.printLine(s"[scaffold] postgres=${pg.id.value.take(12)} redis=${redis.id.value.take(12)}").andThen {
                    exampleTest(pg, redis)
                }
            }.andThen {
                Console.printLine("[scaffold] done; containers + network cleaned up").unit
            }
        }
    }
end IntegrationTestScaffold
