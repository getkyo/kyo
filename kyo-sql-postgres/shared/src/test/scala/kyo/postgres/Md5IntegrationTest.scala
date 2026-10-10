package kyo.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.OwnContainer
import kyo.internal.TestContainers

/** Integration tests for MD5 password authentication.
  *
  * Uses a postgres:16 container with `POSTGRES_HOST_AUTH_METHOD=md5` and MD5-stored secrets, so the server asks the client for MD5.
  */
class Md5IntegrationTest extends SqlContainerTest:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    // Container startup under CI's cross-module churn can outlast the default 2m per-leaf limit, so widen it
    // (the suite runs sequentially via SqlContainerTest).
    override def timeout: Duration = 5.minutes

    private def initMd5Client(
        pg: ContainerPredef.Postgres
    )(using Frame): SqlClient < (Async & Scope & Abort[SqlException] & Abort[ContainerException]) =
        pg.container.mappedPort(pg.config.port).flatMap { port =>
            SqlClient.init(
                s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}",
                SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
            )
        }

    /** Asserts the session's login role stores an MD5 secret. An md5 host rule asks the client for MD5 only then: for a SCRAM verifier it
      * answers with SCRAM-SHA-256, and `system_user` reports `md5:` either way, so the secret is the evidence.
      */
    private def assertMd5Secret(client: SqlClient)(using Frame, kyo.test.AssertScope): Unit < (Async & Abort[SqlException]) =
        client.query("SELECT left(rolpassword, 3) FROM pg_authid WHERE rolname = current_user").flatMap { rows =>
            rows(0).decode[String](0).map { prefix =>
                assert(prefix == "md5", s"the login role's secret must be an MD5 hash, got a secret starting with '$prefix'")
            }
        }

    /** Start a Postgres container with `POSTGRES_HOST_AUTH_METHOD=md5` and pass the [[ContainerPredef.Postgres]] handle to `f`.
      *
      * postgres:16 stores SCRAM verifiers by default, including the bootstrap role's, so the md5 host rule alone would still authenticate
      * with SCRAM. initdb's `-c password_encryption=md5` makes the bootstrap secret, and every one set later, an MD5 hash.
      */
    private def initWithMd5[A, S](
        predefConfig: ContainerPredef.Postgres.Config = ContainerPredef.Postgres.Config.default
    )(f: ContainerPredef.Postgres => A < S)(using Frame): A < (S & Async & Abort[ContainerException] & Scope) =
        val containerConfig = ContainerPredef.Postgres.buildContainerConfig(predefConfig)
            .env("POSTGRES_HOST_AUTH_METHOD", "md5")
            .env("POSTGRES_INITDB_ARGS", "-c password_encryption=md5")
        // Through `TestContainers` rather than `Container.init` directly, so the container carries the
        // `kyo-test-container` and `kyo-test-owner-pid` labels: the scope removes it on every normal exit, and on a
        // force-kill the labels are the only thing that lets the next run reap it and its anonymous volume.
        TestContainers.initScoped(containerConfig, "postgres-md5").flatMap { container =>
            f(new ContainerPredef.Postgres(container, predefConfig))
        }
    end initWithMd5

    "StartupExchange succeeds with MD5 server, connect completes without error".tagged(OwnContainer.name) in {
        Scope.run {
            initWithMd5() { pg =>
                initMd5Client(pg).flatMap { client =>
                    client.isAlive.map(alive => assert(alive)).andThen(assertMd5Secret(client))
                }
            }
        }
    }

    "StartupExchange MD5 wrong password raises SqlConnectionAuthenticationFailedException".tagged(OwnContainer.name) in {
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

    "StartupExchange MD5 SELECT 1 returns correct result after MD5 authentication".tagged(OwnContainer.name) in {
        Scope.run {
            initWithMd5() { pg =>
                initMd5Client(pg).flatMap { client =>
                    // Use text literal '1' so the server returns text OID bytes (UTF-8 compatible in binary format).
                    client.query("SELECT '1'").flatMap { rows =>
                        assert(rows.size == 1)
                        val v = new String(rows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                        assert(v == "1")
                        assertMd5Secret(client)
                    }
                }
            }
        }
    }

    "the MD5 server stores the login role's secret as MD5, so its md5 host rule asks the client for MD5".tagged(OwnContainer.name) in {
        Scope.run {
            initWithMd5() { pg =>
                initMd5Client(pg).flatMap(assertMd5Secret)
            }
        }
    }

end Md5IntegrationTest
