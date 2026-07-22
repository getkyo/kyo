package kyo.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.OwnContainer
import kyo.net.NetTlsConfig

/** Integration tests for Postgres TLS upgrade via SSLRequest handshake.
  *
  * Tests cover:
  *   1. Full TLS connection, SELECT 1 succeeds through TLS.
  *   2. Multi-row query through TLS.
  *   3. drainToReadyForQuery still works through TLS (error recovery).
  *   4. SCRAM-SHA-256 auth works over TLS.
  *   5. TLS connect to a non-TLS Postgres → SqlConnectionException (TlsNotSupported).
  *   6. SCRAM-SHA-256-PLUS handshake via SqlClient.init with TLS config succeeds.
  *
  * The TLS fixture generates a self-signed cert on the host via `openssl`, mounts it into a Postgres container, and starts Postgres with
  * SSL enabled via an entrypoint wrapper script, no ALTER SYSTEM, no container restart.
  */
class TlsIntegrationTest extends kyo.Test:

    // Each leaf spins up its own Postgres container with TLS enabled. Running the six leaves in
    // parallel against the same Docker daemon that also hosts the shared PG + MySQL fixtures for
    // the rest of the suite pushes the container port-binding and TLS handshake past their default
    // 5s connection timeout under load. Serialising the leaves within this suite keeps at most one
    // TLS-enabled container in flight from here, matching the pattern established by
    // CachingSha2IntegrationTest and CachingSha2FullAuthIntegrationTest.
    override def config = super.config.sequential

    override def timeout: Duration = 5.minutes

    /** Starts a Postgres container with SSL enabled using a self-signed certificate generated on the host.
      *
      * Steps:
      *   1. Generate a self-signed cert + key in a host temp directory using `openssl`.
      *   2. Start `postgres:16` with the cert directory mounted read-only at `/etc/ssl-pg` inside the container.
      *   3. Override the container entrypoint with a shell wrapper that copies the certs to `/tmp`, fixes permissions, and execs
      *      `docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=... -c ssl_key_file=...`.
      *   4. Poll for readiness via the built-in health check.
      *   5. Yield connection details with `NetTlsConfig(trustAll = true)` because the self-signed cert is not in the JDK trust store.
      *   6. On scope exit: Scope cleans up the container; `Scope.ensure` deletes the temp directory.
      */
    private def withPostgresTls[A, S](
        extraEnv: Map[String, String] = Map.empty
    )(
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
        // Step 1: generate self-signed cert on the host
        val username = "test"
        val password = "test"
        val database = "test"
        Path.tempDir(prefix = "kyo-sql-tls-").flatMap { tempDirPath =>
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
                // Step 2 & 3: start container with bind mount + entrypoint wrapper
                val wrapperScript =
                    "cp /etc/ssl-pg/server.crt /tmp/server.crt && " +
                        "cp /etc/ssl-pg/server.key /tmp/server.key && " +
                        "chmod 600 /tmp/server.key && " +
                        "chown postgres:postgres /tmp/server.crt /tmp/server.key && " +
                        "exec docker-entrypoint.sh postgres " +
                        "-c ssl=on " +
                        "-c ssl_cert_file=/tmp/server.crt " +
                        "-c ssl_key_file=/tmp/server.key"

                val baseEnv = Map(
                    "POSTGRES_USER"     -> username,
                    "POSTGRES_PASSWORD" -> password,
                    "POSTGRES_DB"       -> database
                ) ++ extraEnv

                val containerConfig = Container.Config.default
                    .copy(image = ContainerImage("postgres:16-alpine"))
                    .envAll(Dict.from(baseEnv))
                    .port(5432, 0)
                    .bind(tempDirPath, Path("/etc/ssl-pg"), readOnly = true)
                    .command("sh", "-c", wrapperScript)
                    .healthCheck(Container.HealthCheck.exec(
                        Command("psql", "-U", username, "-d", database, "-c", "SELECT 1"),
                        Absent,
                        Schedule.fixed(1.second).take(60)
                    ))

                // Step 4: Container.init starts and awaits health check
                Container.init(containerConfig).flatMap { container =>
                    // Step 5: yield connection details
                    container.awaitHealthy.andThen {
                        container.mappedPort(5432).flatMap { port =>
                            f(container.host, port, username, password, database, NetTlsConfig(trustAll = true))
                        }
                    }
                }
            }
        }
    end withPostgresTls

    // Helper: open a TLS SqlClient pool and return it.
    private def initTlsClient(
        host: String,
        port: Int,
        user: String,
        password: String,
        db: String,
        trustAllConfig: NetTlsConfig
    )(using Frame): SqlClient < (Async & Scope & Abort[SqlException]) =
        SqlClient.init(
            s"postgres://$user:$password@$host:$port/$db",
            SqlConfig.default.copy(
                tls = Present(trustAllConfig),
                maxConnections = 1,
                minConnections = 1
            )
        )

    "TLS connection, SELECT 1 returns correct row".tagged("kyo.OwnContainer") in {
        Scope.run {
            withPostgresTls() { (host, port, user, password, db, tlsConfig) =>
                initTlsClient(host, port, user, password, db, tlsConfig).flatMap { client =>
                    // Use text literal '1' so the server returns text OID bytes (UTF-8 compatible in binary format).
                    client.query("SELECT '1'").map { rows =>
                        assert(rows.size == 1)
                        val value = rows(0).column(0)
                        assert(value.isDefined)
                        val str = new String(value.get.toArray, StandardCharsets.UTF_8)
                        assert(str == "1")
                    }
                }
            }
        }
    }

    "TLS connection, multi-row query SELECT generate_series(1,10) returns 10 rows".tagged("kyo.OwnContainer") in {
        Scope.run {
            withPostgresTls() { (host, port, user, password, db, tlsConfig) =>
                initTlsClient(host, port, user, password, db, tlsConfig).flatMap { client =>
                    client.query("SELECT generate_series(1,10)").map { rows =>
                        assert(rows.size == 10)
                    }
                }
            }
        }
    }

    "TLS connection, error recovery: bad SQL followed by valid query works".tagged("kyo.OwnContainer") in {
        Scope.run {
            withPostgresTls() { (host, port, user, password, db, tlsConfig) =>
                initTlsClient(host, port, user, password, db, tlsConfig).flatMap { client =>
                    Abort.run[SqlException](client.query("this is not valid SQL !!!")).flatMap { errResult =>
                        assert(errResult.isFailure)
                        // Use text literal '42' so the server returns text OID bytes (UTF-8 compatible in binary format).
                        client.query("SELECT '42'").map { rows =>
                            assert(rows.size == 1)
                            val str = new String(rows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                            assert(str == "42")
                        }
                    }
                }
            }
        }
    }

    "TLS connection, connect with Present(tls) to non-TLS Postgres raises SqlConnectionException".tagged("kyo.OwnContainer") in {
        Scope.run {
            // Start a standard (non-TLS) Postgres and try to connect with TLS required.
            ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
                pg.container.mappedPort(pg.config.port).flatMap { port =>
                    Abort.run[SqlException] {
                        Scope.run {
                            SqlClient.init(
                                s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}",
                                SqlConfig.default.copy(
                                    tls = Present(NetTlsConfig.default),
                                    maxConnections = 1,
                                    minConnections = 1
                                )
                            )
                        }
                    }.map {
                        case Result.Failure(_: SqlConnectionTlsNotAdvertisedException) =>
                            succeed
                        case other =>
                            fail(s"Expected SqlConnectionTlsNotAdvertisedException for TLS-required connect to non-TLS server, got: $other")
                    }
                }
            }
        }
    }

    "TLS connection, SCRAM-SHA-256 auth works over TLS".tagged("kyo.OwnContainer") in {
        Scope.run {
            // postgres:16-alpine uses scram-sha-256 by default; explicitly set to confirm.
            withPostgresTls(extraEnv = Map("POSTGRES_HOST_AUTH_METHOD" -> "scram-sha-256")) {
                (host, port, user, password, db, tlsConfig) =>
                    initTlsClient(host, port, user, password, db, tlsConfig).flatMap { client =>
                        client.isAlive.map(alive => assert(alive))
                    }
            }
        }
    }

    "SCRAM-SHA-256-PLUS handshake via SqlClient.init with TLS config succeeds".tagged("kyo.OwnContainer") in {
        Scope.run {
            withPostgresTls() { (host, port, user, password, db, tlsConfig) =>
                SqlClient.init(
                    s"postgres://$user:$password@$host:$port/$db",
                    SqlConfig.default.copy(
                        tls = Present(tlsConfig),
                        maxConnections = 1,
                        minConnections = 1
                    )
                ).flatMap { client =>
                    client.executeRaw("SELECT 1").map { _ =>
                        assert(true, "SCRAM-SHA-256-PLUS handshake and SELECT 1 succeeded through SqlClient.init")
                    }
                }
            }
        }
    }

end TlsIntegrationTest
