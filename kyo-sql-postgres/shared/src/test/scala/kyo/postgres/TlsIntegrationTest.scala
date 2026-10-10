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
  *   5. TLS connect to a non-TLS Postgres raises SqlConnectionTlsNotAdvertisedException.
  *   6. The pooled SqlClient.init entry point ends up on an encrypted connection that serves queries.
  *
  * Every leaf that claims TLS asks the server what it sees for its own backend, via [[assertEncrypted]]. Starting the container with
  * `ssl=on` proves the server offers encryption, not that this connection took it, so without that check each of these leaves passes
  * unchanged over plaintext.
  *
  * The TLS leaves share [[SqlConfigTlsModeIntegrationTest]]'s TLS server: a self-signed cert generated on the host via `openssl`, mounted
  * into a Postgres container that starts with SSL enabled via an entrypoint wrapper script. Its host connections authenticate with
  * scram-sha-256, the postgres:16 default. The non-TLS leaf runs on [[PostgresSharedServer]].
  */
class TlsIntegrationTest extends SqlContainerTest:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 5.minutes

    /** Runs `f` against the TLS-enabled Postgres server of [[SqlConfigTlsModeIntegrationTest.withTlsContainer]], with
      * `NetTlsConfig(trustAll = true)` because its self-signed cert is not in the JDK trust store.
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
    )(using Frame): A < (S & Async & Abort[SqlException | ContainerException]) =
        SqlConfigTlsModeIntegrationTest.withTlsContainer { ctx =>
            f(ctx.host, ctx.port, ctx.user, ctx.password, ctx.db, NetTlsConfig(trustAll = true))
        }

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

    /** Asserts this client's own connection is encrypted, by asking the server what it sees for this backend.
      *
      * `pg_stat_ssl` has one row per backend process and `pg_backend_pid()` scopes it to the session the query runs on, so the answer is
      * about the connection under test rather than about the server's configuration. Configuring the container with `ssl=on` proves only
      * that the server offers encryption. The boolean is cast to text because `query` uses the extended protocol, where a binary bool
      * arrives as a single `\x01` byte rather than as `"true"`.
      */
    private def assertEncrypted(client: SqlClient)(using Frame, kyo.test.AssertScope): Unit < (Async & Abort[SqlException]) =
        client.query("SELECT ssl::text FROM pg_stat_ssl WHERE pid = pg_backend_pid()").map { rows =>
            assert(rows.size == 1, s"pg_stat_ssl must return exactly one row for the active backend pid, got ${rows.size}")
            val ssl = rows(0).column(0).fold("")(b => new String(b.toArray, StandardCharsets.UTF_8))
            assert(ssl == "true", s"the connection must be encrypted (pg_stat_ssl.ssl = 'true'), got '$ssl'")
        }

    "TLS connection, SELECT 1 returns correct row".tagged(OwnContainer.name) in {
        Scope.run {
            withPostgresTls { (host, port, user, password, db, tlsConfig) =>
                initTlsClient(host, port, user, password, db, tlsConfig).flatMap { client =>
                    // Use text literal '1' so the server returns text OID bytes (UTF-8 compatible in binary format).
                    client.query("SELECT '1'").flatMap { rows =>
                        assert(rows.size == 1)
                        val value = rows(0).column(0)
                        assert(value.isDefined)
                        val str = new String(value.get.toArray, StandardCharsets.UTF_8)
                        assert(str == "1")
                        assertEncrypted(client)
                    }
                }
            }
        }
    }

    "TLS connection, multi-row query SELECT generate_series(1,10) returns 10 rows".tagged(OwnContainer.name) in {
        Scope.run {
            withPostgresTls { (host, port, user, password, db, tlsConfig) =>
                initTlsClient(host, port, user, password, db, tlsConfig).flatMap { client =>
                    client.query("SELECT generate_series(1,10)").flatMap { rows =>
                        assert(rows.size == 10)
                        assertEncrypted(client)
                    }
                }
            }
        }
    }

    "TLS connection, error recovery: bad SQL followed by valid query works".tagged(OwnContainer.name) in {
        Scope.run {
            withPostgresTls { (host, port, user, password, db, tlsConfig) =>
                initTlsClient(host, port, user, password, db, tlsConfig).flatMap { client =>
                    Abort.run[SqlException](client.query("this is not valid SQL !!!")).flatMap { errResult =>
                        assert(errResult.isFailure)
                        // Use text literal '42' so the server returns text OID bytes (UTF-8 compatible in binary format).
                        client.query("SELECT '42'").flatMap { rows =>
                            assert(rows.size == 1)
                            val str = new String(rows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                            assert(str == "42")
                            // Encryption must survive the error round and the drain, not merely hold at connect.
                            assertEncrypted(client)
                        }
                    }
                }
            }
        }
    }

    "TLS connection, connect with Present(tls) to non-TLS Postgres raises SqlConnectionException".tagged(OwnContainer.name) in {
        Scope.run {
            // A standard (non-TLS) Postgres, connected to with TLS required.
            PostgresSharedServer.server.map { pg =>
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

    "TLS connection, SCRAM-SHA-256 auth works over TLS".tagged(OwnContainer.name) in {
        Scope.run {
            withPostgresTls { (host, port, user, password, db, tlsConfig) =>
                initTlsClient(host, port, user, password, db, tlsConfig).flatMap { client =>
                    // The server's host connections use scram-sha-256, so any successful query proves SCRAM-SHA-256
                    // authenticated; what needs asserting on top is that it did so over an encrypted connection.
                    // isAlive is a ping mapped to isSuccess, which proves a round trip happened and nothing about
                    // value, transport or auth method.
                    assertEncrypted(client)
                }
            }
        }
    }

    "TLS connection through SqlClient.init authenticates and queries over an encrypted connection".tagged(OwnContainer.name) in {
        Scope.run {
            withPostgresTls { (host, port, user, password, db, tlsConfig) =>
                SqlClient.init(
                    s"postgres://$user:$password@$host:$port/$db",
                    SqlConfig.default.copy(
                        tls = Present(tlsConfig),
                        maxConnections = 1,
                        minConnections = 1
                    )
                ).flatMap { client =>
                    // Which SASL mechanism the handshake selected is not observable through SqlClient; it is
                    // asserted by name in ScramPlusIntegrationTest, which drives PostgresConnection directly and
                    // captures the mechanism. What this layer can prove, and what this leaf asserts, is that the
                    // pooled public entry point ends up on an encrypted connection that serves queries.
                    client.query("SELECT '1'").flatMap { rows =>
                        assert(rows.size == 1, s"expected one row, got ${rows.size}")
                        val str = new String(rows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                        assert(str == "1", s"expected '1', got '$str'")
                        assertEncrypted(client)
                    }
                }
            }
        }
    }

end TlsIntegrationTest
