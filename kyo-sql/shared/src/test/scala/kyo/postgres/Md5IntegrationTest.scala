package kyo.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.OwnContainer

/** Integration tests for MD5 password authentication.
  *
  * Uses a postgres:16 container with `POSTGRES_HOST_AUTH_METHOD=md5`.
  */
class Md5IntegrationTest extends kyo.Test:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.init().flatMap(c => HttpClient.let(c)(body))

    private def initMd5Client(
        pg: ContainerPredef.Postgres
    )(using Frame): SqlClient < (Async & Scope & Abort[SqlException] & Abort[ContainerException]) =
        pg.container.mappedPort(pg.config.port).flatMap { port =>
            SqlClient.init(
                s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}",
                SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
            )
        }

    /** Start a Postgres container with `POSTGRES_HOST_AUTH_METHOD=md5` and pass the [[ContainerPredef.Postgres]] handle to `f`. */
    private def initWithMd5[A, S](
        predefConfig: ContainerPredef.Postgres.Config = ContainerPredef.Postgres.Config.default
    )(f: ContainerPredef.Postgres => A < S)(using Frame): A < (S & Async & Abort[ContainerException] & Scope) =
        val containerConfig = ContainerPredef.Postgres.buildContainerConfig(predefConfig)
            .env("POSTGRES_HOST_AUTH_METHOD", "md5")
        Container.initWith(containerConfig) { container =>
            f(new ContainerPredef.Postgres(container, predefConfig))
        }
    end initWithMd5

    "StartupExchange succeeds with MD5 server, connect completes without error".tagged("kyo.OwnContainer") in {
        Scope.run {
            initWithMd5() { pg =>
                initMd5Client(pg).flatMap { client =>
                    client.isAlive.map(alive => assert(alive))
                }
            }
        }
    }

    "StartupExchange MD5 wrong password raises SqlConnectionAuthenticationFailedException".tagged("kyo.OwnContainer") in {
        Scope.run {
            initWithMd5(ContainerPredef.Postgres.Config.default.password("correctmd5pw")) { pg =>
                pg.container.mappedPort(pg.config.port).flatMap { port =>
                    Abort.run[SqlException] {
                        Scope.run {
                            SqlClient.init(
                                s"postgres://${pg.username}:wrongmd5pw@${pg.container.host}:$port/${pg.database}",
                                SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                            )
                        }
                    }.map {
                        case Result.Failure(e: SqlConnectionAuthenticationFailedException) =>
                            assert(
                                e.sqlState == "28P01" || e.sqlState == "28000",
                                s"Expected sqlState 28P01 or 28000 for wrong MD5 password, got: ${e.sqlState}"
                            )
                        case other => fail(s"Expected SqlConnectionAuthenticationFailedException for wrong MD5 password, got: $other")
                    }
                }
            }
        }
    }

    "StartupExchange MD5 SELECT 1 returns correct result after MD5 authentication".tagged("kyo.OwnContainer") in {
        Scope.run {
            initWithMd5() { pg =>
                initMd5Client(pg).flatMap { client =>
                    // Use text literal '1' so the server returns text OID bytes (UTF-8 compatible in binary format).
                    client.query("SELECT '1'").map { rows =>
                        assert(rows.size == 1)
                        val v = new String(rows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                        assert(v == "1")
                    }
                }
            }
        }
    }

end Md5IntegrationTest
