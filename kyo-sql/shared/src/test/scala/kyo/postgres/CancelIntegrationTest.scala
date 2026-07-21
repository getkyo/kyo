package kyo.postgres

import kyo.*
import kyo.OwnContainer
import kyo.internal.SqlSharedContainers
import kyo.net.NetTlsConfig

/** Integration tests for query cancellation via the public SqlClient API.
  *
  * These tests exercise the public cancellation surface:
  *   - `SqlClient.cancellableQuery` returns a `SqlCancelHandle.Pg` carrying the backend's process ID, secret key, address, and TLS config.
  *   - `client.cancel(handle: SqlCancelHandle.Pg)` opens a fresh out-of-band TCP connection to deliver the cancel request.
  *
  * Tests:
  *   1. cancellableQuery exposes a SqlCancelHandle with correct processId.
  *   2. cancellableQuery via TLS — handle carries TLS config from the client.
  *
  * Internal CancelExchange / PostgresConnection tests live in `kyo.internal.postgres.exchange.CancelExchangeTest`.
  */
class CancelIntegrationTest extends kyo.Test:

    override def timeout: Duration = 8.minutes

    /** Starts a TLS-enabled Postgres container by generating a self-signed cert on the host, bind-mounting it into the container, and
      * overriding the entrypoint with a wrapper script that copies the certs to a writable path before launching `docker-entrypoint.sh`
      * with `ssl=on`. Avoids the brittle "exec apk add openssl + ALTER SYSTEM + container.restart" sequence which fails under load.
      */
    private def withPostgresTls[A, S](
        f: (
            host: String,
            port: Int,
            user: String,
            password: String,
            db: String,
            trustAllConfig: NetTlsConfig
        ) => A < (S & Async & Abort[SqlException])
    )(using
        Frame
    ): A < (S & Async & Scope & Abort[ContainerException] & Abort[CommandException] & Abort[SqlException] & Abort[FileException]) =
        val username = "test"
        val password = "test"
        val database = "test"
        Path.tempDir(prefix = "kyo-sql-tls-cancel-").flatMap { tempDirPath =>
            val tempDir = tempDirPath.toString
            Command(
                "openssl",
                "req",
                "-new",
                "-x509",
                "-days",
                "1",
                "-nodes",
                "-subj",
                "/CN=localhost",
                "-keyout",
                s"$tempDir/server.key",
                "-out",
                s"$tempDir/server.crt"
            ).text.andThen {
                val wrapperScript =
                    "cp /etc/ssl-pg/server.crt /tmp/server.crt && " +
                        "cp /etc/ssl-pg/server.key /tmp/server.key && " +
                        "chmod 600 /tmp/server.key && " +
                        "chown postgres:postgres /tmp/server.crt /tmp/server.key && " +
                        "exec docker-entrypoint.sh postgres " +
                        "-c ssl=on " +
                        "-c ssl_cert_file=/tmp/server.crt " +
                        "-c ssl_key_file=/tmp/server.key"

                val baseEnv = Dict.from(Map(
                    "POSTGRES_USER"     -> username,
                    "POSTGRES_PASSWORD" -> password,
                    "POSTGRES_DB"       -> database
                ))

                val containerConfig = Container.Config.default
                    .copy(image = ContainerImage("postgres:16-alpine"))
                    .envAll(baseEnv)
                    .port(5432, 0)
                    .bind(tempDirPath, Path("/etc/ssl-pg"), readOnly = true)
                    .command("sh", "-c", wrapperScript)
                    .healthCheck(Container.HealthCheck.exec(
                        Command("psql", "-U", username, "-d", database, "-c", "SELECT 1"),
                        Absent,
                        Schedule.fixed(1.second).take(60)
                    ))

                Container.init(containerConfig).flatMap { container =>
                    container.awaitHealthy.andThen {
                        container.mappedPort(5432).flatMap { port =>
                            f(container.host, port, username, password, database, NetTlsConfig(trustAll = true))
                        }
                    }
                }
            }
        }
    end withPostgresTls

    "cancellableQuery exposes a SqlCancelHandle with correct processId".tagged("kyo.OwnContainer") in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(SqlSharedContainers.Backend.Postgres) { ctx =>
                val url = s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"
                SqlClient.init(url).flatMap { client =>
                    SqlClient.let(client) {
                        client.cancellableQuery("SELECT 42").map {
                            case (handle: SqlCancelHandle.Pg, rows) =>
                                assert(handle.processId > 0, s"Expected positive processId, got ${handle.processId}")
                                assert(rows.size == 1, s"Expected 1 row, got ${rows.size}")
                            case null => fail("Expected SqlCancelHandle.Pg for a Postgres client")
                        }
                    }
                }
            }
        }
    }

    "cancellableQuery via TLS — handle carries TLS config from the client".tagged("kyo.OwnContainer") in {
        Scope.run {
            withPostgresTls { (host, port, user, password, db, trustAllConfig) =>
                val url = s"postgres://$user:$password@$host:$port/$db?sslmode=require"
                SqlClient.init(url, SqlClientConfig.default.copy(tls = Present(trustAllConfig))).flatMap { client =>
                    SqlClient.let(client) {
                        // Run a fast query over TLS and check that the returned SqlCancelHandle carries the TLS config.
                        client.cancellableQuery("SELECT 42").map {
                            case (handle: SqlCancelHandle.Pg, rows) =>
                                assert(rows.size == 1, s"Expected 1 row, got ${rows.size}")
                                assert(handle.processId > 0, s"Expected positive processId, got ${handle.processId}")
                                // The handle's TLS config should be Present (since we configured TLS).
                                assert(handle.tls.isDefined, "Expected Present tls in SqlCancelHandle for TLS client")
                            case null => fail("Expected SqlCancelHandle.Pg for a Postgres client")
                        }
                    }
                }
            }
        }
    }

end CancelIntegrationTest
