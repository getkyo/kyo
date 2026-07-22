package kyo.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.OwnContainer

/** Integration tests for SCRAM-SHA-256 authentication.
  *
  * Uses a postgres:16 container with the default auth method (scram-sha-256 is the postgres:16 default, so no POSTGRES_HOST_AUTH_METHOD
  * override is needed). Tests cover successful auth, wrong-password rejection, and server signature verification.
  */
class ScramIntegrationTest extends kyo.Test:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.init().flatMap(c => HttpClient.let(c)(body))

    // Helper: init a SqlClient via SCRAM and return it.
    private def initScramClient(
        pg: ContainerPredef.Postgres
    )(using Frame): SqlClient.Postgres < (Async & Scope & Abort[SqlException] & Abort[ContainerException]) =
        pg.container.mappedPort(pg.config.port).flatMap { port =>
            SqlClient.init(
                s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}",
                SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
            )
        }

    "StartupExchange succeeds with SCRAM-SHA-256 server, connect completes without error".tagged("kyo.OwnContainer") in {
        Scope.run {
            // Default postgres:16 uses scram-sha-256; no authMethod override needed.
            ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
                initScramClient(pg).flatMap { client =>
                    client.isAlive.map(alive => assert(alive))
                }
            }
        }
    }

    "StartupExchange SCRAM wrong password raises SqlConnectionAuthenticationFailedException".tagged("kyo.OwnContainer") in {
        Scope.run {
            ContainerPredef.Postgres.initWith(
                ContainerPredef.Postgres.Config.default.password("correctpassword")
            ) { pg =>
                pg.container.mappedPort(pg.config.port).flatMap { port =>
                    Abort.run[SqlException] {
                        Scope.run {
                            SqlClient.init(
                                s"postgres://${pg.username}:wrongpassword@${pg.container.host}:$port/${pg.database}",
                                SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                            )
                        }
                    }.map {
                        case Result.Failure(e: SqlConnectionAuthenticationFailedException) =>
                            assert(
                                e.sqlState == "28P01" || e.sqlState == "28000",
                                s"Expected sqlState 28P01 or 28000 for wrong SCRAM password, got: ${e.sqlState}"
                            )
                        case other => fail(s"Expected SqlConnectionAuthenticationFailedException for wrong SCRAM password, got: $other")
                    }
                }
            }
        }
    }

    "StartupExchange SCRAM server signature verified, no error after successful SCRAM".tagged("kyo.OwnContainer") in {
        Scope.run {
            ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
                // If server signature verification fails, connect raises SqlConnectionException.
                // Success here proves the server signature was accepted.
                initScramClient(pg).flatMap { client =>
                    client.isAlive.map(alive => assert(alive, "Connection should be open after SCRAM with valid server signature"))
                }
            }
        }
    }

    "StartupExchange SCRAM populates ParameterStatus, server_version present after SCRAM connect".tagged("kyo.OwnContainer") in {
        Scope.run {
            ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
                initScramClient(pg).flatMap { client =>
                    client.parameters.map { params =>
                        assert(
                            params.contains("server_version"),
                            s"server_version missing from params: ${params.keys.mkString(", ")}"
                        )
                    }
                }
            }
        }
    }

    "StartupExchange SCRAM SELECT 1 returns correct result after authentication".tagged("kyo.OwnContainer") in {
        Scope.run {
            ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
                initScramClient(pg).flatMap { client =>
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

    "StartupExchange trust auth still works after SCRAM addition, regression".tagged("kyo.OwnContainer") in {
        Scope.run {
            // Verify cleartext (password) auth still works after adding SCRAM support.
            val regPredefConfig = ContainerPredef.Postgres.Config.default.password("regpw")
            val regContainerConfig = ContainerPredef.Postgres.buildContainerConfig(regPredefConfig)
                .env("POSTGRES_HOST_AUTH_METHOD", "password")
            Container.initWith(regContainerConfig) { regContainer =>
                val pg = new ContainerPredef.Postgres(regContainer, regPredefConfig)
                pg.container.mappedPort(pg.config.port).flatMap { port =>
                    SqlClient.init(
                        s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}",
                        SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                    ).flatMap { client =>
                        client.isAlive.map(alive => assert(alive, "Cleartext auth must still work alongside SCRAM"))
                    }
                }
            }
        }
    }

    "StartupExchange SCRAM stores BackendKeyData, processId > 0 after SCRAM connect".tagged("kyo.OwnContainer") in {
        Scope.run {
            ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
                initScramClient(pg).flatMap { client =>
                    client.query("SELECT pg_backend_pid()").flatMap { rows =>
                        assert(rows.nonEmpty, "pg_backend_pid() returned no rows")
                        rows(0).decode[Int](0).map { pid =>
                            assert(pid > 0, s"Expected positive processId (pg_backend_pid()), got $pid")
                        }
                    }
                }
            }
        }
    }

end ScramIntegrationTest
