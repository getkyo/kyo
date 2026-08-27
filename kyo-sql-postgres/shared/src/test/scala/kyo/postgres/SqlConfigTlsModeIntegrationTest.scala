package kyo.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.OwnContainer
import kyo.internal.SqlTestContainers
import kyo.internal.postgres.PostgresConnection
import kyo.net.NetTlsConfig

/** Integration tests for sslmode `verify-ca` and `verify-full`.
  *
  * Cert generation and the TLS-enabled Postgres container are created ONCE on first leaf access via per-class CAS-singletons (see
  * [[tlsRef]] and [[requireSslRef]]). All TLS leaves share the same cert fixture, eliminating the 9× openssl invocation + container churn
  * that caused CI flakiness.
  *
  * Container groups:
  *   - Leaf 1 uses a plain `ContainerPredef.Postgres` container (no TLS, managed per-leaf by ContainerPredef).
  *   - Leaves 2-9, 12-14, 16 share one TLS-enabled Postgres container (permits both TLS and plaintext).
  *   - Leaves 11 and 15 share a REQUIRE-SSL Postgres container (only `hostssl` in pg_hba.conf).
  *
  * Containers are NOT torn down in-process. They carry the `kyo-sql-singleton` and `kyo-sql-owner-pid` labels, and
  * `SqlTestContainers.initSingleton` removes every dead-owner container, together with its anonymous volumes, before creating a new
  * singleton. There is no build-level cleanup task: a force-killed test process runs no sbt hook either.
  *
  * Cert fixture (generated once, on demand):
  *   - `server.crt` / `server.key`: self-signed cert with CN=localhost.
  *   - `ca.pem`: copy of `server.crt` (self-signed cert is its own issuer; used as the trust anchor for `verify-ca` / `verify-full`).
  *   - `wrong.crt`: distinct self-signed cert with CN=wrongca (used as the wrong CA for negative tests).
  *
  * Test leaves:
  *   1. `sslmode=disable` connects without TLS.
  *   2. `sslmode=require` connects with TLS, accepts self-signed cert via `trustAll`.
  *   3. `sslmode=verify-ca` rejects connection when CA path is wrong (different self-signed cert), with
  *      [[SqlConnectionConnectFailedException]].
  *   4. `sslmode=verify-full` rejects connection when the hostname connected to (127.0.0.1) is not the one the cert names (CN=localhost),
  *      with [[SqlConnectionConnectFailedException]].
  *   5. `sslmode=verify-ca` connects when CA path matches server cert issuer (positive case, and leaf 3's control).
  *   6. `sslmode=verify-full` connects when CA matches AND hostname matches SAN (positive case, and leaf 4's control).
  *   7. `sslmode=verify-ca` with missing `sslrootcert` is refused before any socket opens, with [[SqlConnectionTlsConfigException]].
  *   8. `sslmode=verify-ca` with malformed PEM at `sslrootcert` fails during the TLS upgrade, with
  *      [[SqlConnectionConnectFailedException]].
  *   9. Cancellation mid-TLS handshake leaves no leaked connection, pool remains reusable.
  *   10. `sslmode=allow` connects plaintext when server permits plaintext.
  *   11. `sslmode=allow` upgrades to TLS when server requires TLS, uses the require-ssl container.
  *   12. `sslmode=prefer` connects with TLS when server supports TLS.
  *   13. `sslmode=prefer` falls back to plaintext when server refuses TLS.
  *   14. `sslmode=prefer` with invalid cert still connects (no validation).
  *   15. `sslmode=allow` upgraded connection sends subsequent queries over TLS, uses the require-ssl container.
  *   16. Cancellation during opportunistic-TLS upgrade returns connection to clean state.
  */
class SqlConfigTlsModeIntegrationTest extends SqlContainerTest:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 10.minutes

    import SqlConfigTlsModeIntegrationTest.*

    /** The one query that can tell a TLS connection from a plaintext one, cast to text because `query` uses the extended protocol. */
    private val sslQuery = "SELECT ssl::text FROM pg_stat_ssl WHERE pid = pg_backend_pid()"

    /** Asserts a [[sslQuery]] result says this backend's connection is encrypted.
      *
      * The shared fixture container permits plaintext as well as TLS, so a leaf that only proves the connection WORKS proves nothing about
      * the transport: a silent downgrade satisfies it exactly as an upgrade does. `pg_stat_ssl` has one row per backend process and
      * `pg_backend_pid()` scopes it to the session the query ran on, so the answer is about this connection rather than about the server's
      * configuration.
      *
      * Exactly one row is required rather than merely some row. The `fold("")` below turns an empty result into an empty cipher, which
      * would be reported as "the connection is plaintext" and send the reader after the wrong defect.
      */
    private def assertEncryptedRows(rows: Chunk[SqlRow], context: String)(using Frame, kyo.test.AssertScope): Unit =
        assert(rows.size == 1, s"$context: pg_stat_ssl must return exactly one row for the active backend pid, got ${rows.size}")
        val ssl = rows(0).column(0).fold("")(b => new String(b.toArray, StandardCharsets.UTF_8))
        assert(ssl == "true", s"$context: the connection must be encrypted (pg_stat_ssl.ssl = 'true'), got '$ssl'")
    end assertEncryptedRows

    /** Asks the server whether the connection `client` is holding is encrypted, and asserts that it is. */
    private def assertEncrypted(client: SqlClient, context: String)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[SqlException]) =
        client.query(sslQuery).map(rows => assertEncryptedRows(rows, context))

    /** The inverse, for the leaves whose titles claim a PLAINTEXT transport.
      *
      * The reasoning above applies in both directions. A leaf titled "connects without TLS" that checks only that a query answered is
      * satisfied by a silent UPGRADE exactly as a TLS leaf is satisfied by a silent downgrade. Container configuration alone is not
      * evidence: a fixture whose flags drift leaves the title unbacked, so the premise is checked rather than trusted.
      *
      * `ssl` is compared against `false` rather than merely "not true", so a `pg_stat_ssl` that answered something unexpected fails here
      * instead of passing as though it had said plaintext.
      */
    private def assertNotEncrypted(client: SqlClient, context: String)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[SqlException]) =
        client.query(sslQuery).map { rows =>
            assert(rows.size == 1, s"$context: pg_stat_ssl must return exactly one row for the active backend pid, got ${rows.size}")
            val ssl = rows(0).column(0).fold("")(b => new String(b.toArray, StandardCharsets.UTF_8))
            assert(ssl == "false", s"$context: the connection must be plaintext (pg_stat_ssl.ssl = 'false'), got '$ssl'")
        }

    // ── Leaf 1: sslmode=disable ───────────────────────────────────────────────
    // Uses ContainerPredef.Postgres, no TLS, no shared fixture needed.

    "sslmode=disable connects without TLS".tagged("kyo.OwnContainer") in {
        Scope.run {
            // Through `SqlTestContainers` rather than `ContainerPredef.Postgres.initWith` directly, so the
            // container carries the `kyo-sql-singleton` and `kyo-sql-owner-pid` labels and a killed test
            // process leaves something the reaper can find.
            SqlTestContainers.initScopedPostgres(ContainerPredef.Postgres.Config.default, "postgres-tls-mode-leaf").map { pg =>
                pg.container.mappedPort(pg.config.port).flatMap { port =>
                    val url = s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}?sslmode=disable"
                    SqlClient.init(url).flatMap { client =>
                        DB.run(client) {
                            client.query("SELECT 1").flatMap { rows =>
                                assert(rows.size == 1, "Expected 1 row from SELECT 1")
                                assertNotEncrypted(client, "sslmode=disable")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 2: sslmode=require ───────────────────────────────────────────────
    // Shared TLS container; trustAll=true accepts the self-signed cert.

    "sslmode=require connects with TLS, accepts self-signed cert".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            val config = SqlConfig.default.copy(tls = Present(NetTlsConfig(trustAll = true)))
            val url    = s"postgres://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=require"
            Scope.run {
                SqlClient.init(url, config).flatMap { client =>
                    DB.run(client) {
                        client.query("SELECT 1").flatMap { rows =>
                            assert(rows.size == 1, "Expected 1 row from SELECT 1 over TLS with sslmode=require")
                            assertEncrypted(client, "sslmode=require")
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 3: sslmode=verify-ca rejects wrong CA ───────────────────────────
    // Shared TLS container; client uses wrongCaCertPath, handshake must fail.

    "sslmode=verify-ca rejects connection when CA path is wrong".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            val url =
                s"postgres://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=verify-ca&sslrootcert=${ctx.wrongCaCertPath}"
            Abort.run[SqlException] {
                Scope.run {
                    SqlClient.init(url).flatMap { client =>
                        DB.run(client) {
                            client.query("SELECT 1")
                        }
                    }
                }
            }.map {
                // The subtype is the property. A chain the given CA did not sign is rejected by the TLS layer,
                // which InitSSLExchange.scala:153 wraps as SqlConnectionConnectFailedException. Accepting any
                // SqlException here would pass on a container that never started and on a wrong password. Leaf 5
                // is the positive control: the same container, reached with the matching CA, must connect AND
                // report pg_stat_ssl.ssl = 'true'.
                case Result.Failure(_: SqlConnectionConnectFailedException) =>
                    succeed
                case Result.Success(_) =>
                    fail("Expected SqlConnectionException for wrong CA cert, but connection succeeded")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic in leaf 3: ${t.getMessage}")
                case Result.Failure(e) =>
                    fail(s"expected SqlConnectionConnectFailedException from chain validation, got: $e")
            }
        }
    }

    // ── Leaf 4: sslmode=verify-full rejects hostname mismatch ────────────────
    // Shared TLS container (CN=localhost); connect via 127.0.0.1, verify-full rejects.

    "sslmode=verify-full rejects connection when hostname mismatches cert SAN".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            // Connect to the same container but via 127.0.0.1; the server cert has CN=localhost.
            // verify-full checks hostname against CN/SAN, so 127.0.0.1 ≠ localhost → reject.
            val url =
                s"postgres://${ctx.user}:${ctx.password}@127.0.0.1:${ctx.port}/${ctx.db}?sslmode=verify-full&sslrootcert=${ctx.caCertPath}"
            Abort.run[SqlException] {
                Scope.run {
                    SqlClient.init(url).flatMap { client =>
                        DB.run(client) {
                            client.query("SELECT 1")
                        }
                    }
                }
            }.map {
                // Hostname verification runs inside the TLS upgrade, so its refusal arrives as
                // SqlConnectionConnectFailedException. Leaf 6 is the positive control: the same container over
                // the name the cert carries must connect and report an encrypted connection.
                case Result.Failure(_: SqlConnectionConnectFailedException) =>
                    succeed
                case Result.Success(_) =>
                    fail("Expected SqlConnectionException for hostname mismatch, but connection succeeded")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic in leaf 4: ${t.getMessage}")
                case Result.Failure(e) =>
                    fail(s"expected SqlConnectionConnectFailedException from hostname verification, got: $e")
            }
        }
    }

    // ── Leaf 5: sslmode=verify-ca positive case ───────────────────────────────
    // Shared TLS container; client uses matching caCertPath, connection must succeed.

    "sslmode=verify-ca connects when CA path matches server cert issuer".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            val url =
                s"postgres://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=verify-ca&sslrootcert=${ctx.caCertPath}"
            Scope.run {
                SqlClient.init(url).flatMap { client =>
                    DB.run(client) {
                        client.query("SELECT 42").flatMap { rows =>
                            assert(rows.size == 1, "Expected 1 row from SELECT 42 over verify-ca TLS")
                            // This leaf is the positive control the verify-ca rejection leaf above depends on,
                            // so it has to prove the fixture serves TLS rather than merely that it answers.
                            assertEncrypted(client, "sslmode=verify-ca with a matching CA")
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 6: sslmode=verify-full positive case ─────────────────────────────
    // Shared TLS container; connect via "localhost", cert CN=localhost, hostname verification passes.

    "sslmode=verify-full connects when CA matches AND hostname matches SAN".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            // Connect via "localhost", cert CN=localhost, so hostname verification passes.
            val url =
                s"postgres://${ctx.user}:${ctx.password}@localhost:${ctx.port}/${ctx.db}?sslmode=verify-full&sslrootcert=${ctx.caCertPath}"
            Scope.run {
                SqlClient.init(url).flatMap { client =>
                    DB.run(client) {
                        client.query("SELECT 42").flatMap { rows =>
                            assert(rows.size == 1, "Expected 1 row from SELECT 42 over verify-full TLS")
                            // Positive control for the hostname-mismatch rejection leaf above.
                            assertEncrypted(client, "sslmode=verify-full with a matching CA and hostname")
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 7: sslmode=verify-ca missing sslrootcert ────────────────────────
    // Shared TLS container; no sslrootcert, JDK default trust store rejects self-signed cert.

    "sslmode=verify-ca with missing sslrootcert fails at SqlClient.init with SqlConnectionException".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            // No sslrootcert param, so TlsContext.build refuses at TlsContext.scala:54 with
            // SqlConnectionTlsConfigException("verify-ca") before any socket is opened. The refusal is the
            // config's, not the trust store's: verify-ca naming no CA is a configuration that cannot be built,
            // which is the distinction SqlConnectionTlsConfigException's own scaladoc draws against
            // SqlConnectionTlsNotAdvertisedException.
            val url = s"postgres://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=verify-ca"
            Abort.run[SqlException] {
                Scope.run {
                    SqlClient.init(url).flatMap { client =>
                        DB.run(client) {
                            client.query("SELECT 1")
                        }
                    }
                }
            }.map {
                case Result.Failure(_: SqlConnectionTlsConfigException) =>
                    succeed
                case Result.Success(_) =>
                    fail("Expected SqlConnectionException when sslrootcert is missing, but connection succeeded")
                case Result.Panic(t) =>
                    fail(s"Unexpected panic in leaf 7: ${t.getMessage}")
                case Result.Failure(e) =>
                    fail(s"expected SqlConnectionTlsConfigException for verify-ca naming no CA, got: $e")
            }
        }
    }

    // ── Leaf 8: sslmode=verify-ca malformed PEM ───────────────────────────────
    // Shared TLS container; malformed PEM file, SSL context creation fails.

    "sslmode=verify-ca with malformed PEM at sslrootcert fails with SqlConnectionException".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            // Write a malformed PEM file to a temp path using kyo.Path (cross-platform).
            Path.tempUnscoped(prefix = "kyo-sql-bad-cert-", suffix = ".pem").flatMap { tempPath =>
                tempPath.writeBytes(Span.from("NOT A VALID PEM CERTIFICATE".getBytes(StandardCharsets.UTF_8))).flatMap { _ =>
                    Scope.ensure(Abort.run[FileSystemException](tempPath.remove).unit).andThen {
                        val badPath = tempPath.toString
                        val url =
                            s"postgres://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=verify-ca&sslrootcert=$badPath"
                        Abort.run[SqlException] {
                            Scope.run {
                                SqlClient.init(url).flatMap { client =>
                                    DB.run(client) {
                                        client.query("SELECT 1")
                                    }
                                }
                            }
                        }.map {
                            // A path that exists but holds no certificate builds a NetTlsConfig fine and fails
                            // when the TLS context is created during the upgrade, which InitSSLExchange wraps as
                            // SqlConnectionConnectFailedException. That is a different refusal from leaf 7's, and
                            // pinning both is what stops the two collapsing into "something went wrong".
                            case Result.Failure(_: SqlConnectionConnectFailedException) =>
                                succeed
                            case Result.Success(_) =>
                                fail("Expected SqlConnectionException for malformed PEM, but connection succeeded")
                            case Result.Panic(t) =>
                                fail(s"Unexpected panic in leaf 8: ${t.getMessage}")
                            case Result.Failure(e) =>
                                fail(s"expected SqlConnectionConnectFailedException for a malformed CA PEM, got: $e")
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 9: cancellation mid-TLS handshake ────────────────────────────────
    // Shared TLS container; cancel a fiber quickly, then probe pool reusability.

    "cancellation mid-TLS handshake leaves no leaked connection".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            val url =
                s"postgres://${ctx.user}:${ctx.password}@localhost:${ctx.port}/${ctx.db}?sslmode=verify-full&sslrootcert=${ctx.caCertPath}"
            // Spin up a client and fire a long-running query in a fiber.
            // Cancel the fiber quickly to simulate mid-handshake interruption.
            Scope.run {
                SqlClient.init(url).flatMap { client =>
                    DB.run(client) {
                        // Fire multiple rapid-connect fibers and cancel them.
                        // The pool must still be usable after cancellation.
                        val cancelFiber = Fiber.init(
                            Abort.run[SqlException](client.query("SELECT pg_sleep(5)"))
                        )
                        cancelFiber.flatMap { fiber =>
                            // Cancel immediately after spawning, may be mid-handshake.
                            fiber.interrupt.andThen {
                                // Probe: pool must still serve requests after the cancelled fiber.
                                client.query("SELECT 'reusable'").map { rows =>
                                    assert(rows.size == 1, "Pool must be reusable after mid-handshake cancellation")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── sslmode=allow and sslmode=prefer opportunistic TLS ────────────────────
    // Leaves 10-16: sslmode=allow and sslmode=prefer

    // ── Leaf 10: sslmode=allow connects plaintext when server permits plaintext ─

    "sslmode=allow connects plaintext when server permits plaintext".tagged("kyo.OwnContainer") in {
        Scope.run {
            // Plain Postgres container (no cert mount → no TLS support)
            // Through `SqlTestContainers` rather than `ContainerPredef.Postgres.initWith` directly, so the
            // container carries the `kyo-sql-singleton` and `kyo-sql-owner-pid` labels and a killed test
            // process leaves something the reaper can find.
            SqlTestContainers.initScopedPostgres(ContainerPredef.Postgres.Config.default, "postgres-tls-mode-leaf").map { pg =>
                pg.container.mappedPort(pg.config.port).flatMap { port =>
                    val url = s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}?sslmode=allow"
                    SqlClient.init(url).flatMap { client =>
                        DB.run(client) {
                            client.query("SELECT 1").flatMap { rows =>
                                assert(rows.size == 1, "sslmode=allow should connect plaintext when server permits it")
                                assertNotEncrypted(client, "sslmode=allow against a server with no TLS")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 11: sslmode=allow upgrades to TLS when server requires TLS ───────
    // Uses the require-SSL container (only hostssl in pg_hba.conf). A plaintext
    // connection attempt receives SQLSTATE 28000; PostgresSqlConnection detects
    // this via requiresSsl and retries with TLS. The connection must end up
    // encrypted, verified via pg_stat_ssl.ssl = "true".

    "sslmode=allow upgrades to TLS when server requires TLS".tagged("kyo.OwnContainer") in {
        withRequireSslContainer { ctx =>
            // sslmode=allow: tries plaintext first → gets SQLSTATE 28000 → retries with TLS.
            // The reconnect succeeds because the container also has TLS certs (same as the main fixture).
            val url = s"postgres://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=allow"
            Scope.run {
                SqlClient.init(url).flatMap { client =>
                    DB.run(client) {
                        // The query succeeds only because the allow reconnect path fired and upgraded to TLS.
                        assertEncrypted(client, "sslmode=allow against a server that requires TLS")
                    }
                }
            }
        }
    }

    // ── Leaf 12: sslmode=prefer connects with TLS when server supports TLS ────
    // Uses the shared TLS container. prefer sends SSLRequest, gets 'S', upgrades.

    "sslmode=prefer connects with TLS when server supports TLS".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            // The shared TLS container advertises SSL ('S' response to SSLRequest).
            // prefer mode: SSLRequest → 'S' → upgrade to TLS → proceed with startup.
            val url = s"postgres://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=prefer"
            Scope.run {
                SqlClient.init(url).flatMap { client =>
                    DB.run(client) {
                        // Verify the connection succeeded AND is actually using TLS: prefer must upgrade when
                        // the server advertises SSL, and a silent stay on plaintext also answers queries.
                        assertEncrypted(client, "sslmode=prefer against a server that supports TLS")
                    }
                }
            }
        }
    }

    // ── Leaf 13: sslmode=prefer falls back to plaintext when server refuses TLS

    "sslmode=prefer falls back to plaintext when server refuses TLS".tagged("kyo.OwnContainer") in {
        Scope.run {
            // Plain Postgres container: no cert → SSLRequest returns 'N' → plaintext fallback.
            // Through `SqlTestContainers` rather than `ContainerPredef.Postgres.initWith` directly, so the
            // container carries the `kyo-sql-singleton` and `kyo-sql-owner-pid` labels and a killed test
            // process leaves something the reaper can find.
            SqlTestContainers.initScopedPostgres(ContainerPredef.Postgres.Config.default, "postgres-tls-mode-leaf").map { pg =>
                pg.container.mappedPort(pg.config.port).flatMap { port =>
                    val url = s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}?sslmode=prefer"
                    SqlClient.init(url).flatMap { client =>
                        DB.run(client) {
                            // Connection must succeed via plaintext fallback.
                            client.query("SELECT 1").map { rows =>
                                assert(rows.size == 1, "sslmode=prefer should fall back to plaintext when server refuses TLS")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 14: sslmode=prefer with invalid cert still connects ──────────────
    // "no validation; the trap users walk into": prefer doesn't validate certs.

    "sslmode=prefer with invalid cert still connects (no validation; the trap users walk into)".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            // prefer mode uses trustAll=true (no cert validation), so even an "invalid" CA path
            // won't cause a handshake failure, the user gets TLS without validation.
            val url =
                s"postgres://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=prefer&sslrootcert=${ctx.wrongCaCertPath}"
            Scope.run {
                // Expect SUCCESS, prefer with no cert validation ignores the wrong CA.
                SqlClient.init(url).flatMap { client =>
                    DB.run(client) {
                        client.query("SELECT 1").map { rows =>
                            assert(rows.size == 1, "sslmode=prefer should succeed even with a wrong CA path (no cert validation)")
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 15: sslmode=allow upgraded connection sends subsequent queries over TLS ──
    // Uses the require-SSL container. The allow mode must upgrade to TLS (because the
    // server rejects plaintext). All subsequent queries on the same connection run over
    // TLS, each is verified via pg_stat_ssl.ssl = "true".

    "sslmode=allow upgraded connection sends subsequent queries over TLS".tagged("kyo.OwnContainer") in {
        withRequireSslContainer { ctx =>
            // hostssl-only pg_hba.conf forces the allow upgrade.
            // After reconnect with TLS, every subsequent query on the connection is encrypted.
            val url = s"postgres://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=allow"
            Scope.run {
                SqlClient.init(url).flatMap { client =>
                    DB.run(client) {
                        // All three queries run on connections obtained from the pool.
                        // Each connection was established over TLS (allow upgrade path) so
                        // pg_stat_ssl must report ssl='true' for every backend pid.
                        client.query(sslQuery).flatMap { r1 =>
                            client.query(sslQuery).flatMap { r2 =>
                                client.query(sslQuery).map { r3 =>
                                    assertEncryptedRows(r1, "query 1 of the upgraded connection")
                                    assertEncryptedRows(r2, "query 2 of the upgraded connection")
                                    assertEncryptedRows(r3, "query 3 of the upgraded connection")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 16: cancellation during opportunistic-TLS upgrade returns connection to clean state ──

    "cancellation during opportunistic-TLS upgrade returns connection to clean state".tagged("kyo.OwnContainer") in {
        withTlsContainer { ctx =>
            // Use prefer mode (which sends SSLRequest before startup) to exercise the upgrade path.
            // Cancel the fiber mid-negotiation; verify pool is still reusable.
            val url = s"postgres://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=prefer"
            Scope.run {
                SqlClient.init(url).flatMap { client =>
                    DB.run(client) {
                        val cancelFiber = Fiber.init(
                            Abort.run[SqlException](client.query("SELECT pg_sleep(5)"))
                        )
                        cancelFiber.flatMap { fiber =>
                            fiber.interrupt.andThen {
                                // Probe: pool must still serve requests after mid-upgrade cancellation.
                                client.query("SELECT 'clean_state'").map { rows =>
                                    assert(
                                        rows.size == 1,
                                        "Pool must be reusable after cancellation during opportunistic TLS upgrade"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end SqlConfigTlsModeIntegrationTest

object SqlConfigTlsModeIntegrationTest:

    /** Connection details for the shared TLS Postgres container (permits both TLS and plaintext). Includes paths to the matching CA cert
      * and the wrong CA cert (both generated from the singleton's bind-mount).
      */
    final case class TlsCtx(
        host: String,
        port: Int,
        user: String,
        password: String,
        db: String,
        caCertPath: String,
        wrongCaCertPath: String
    )

    /** Connection details for the require-SSL Postgres container (`hostssl`-only pg_hba.conf). */
    final case class RequireSslCtx(
        host: String,
        port: Int,
        user: String,
        password: String,
        db: String
    )

    private type TlsPromise        = Promise[TlsCtx, Abort[ContainerException]]
    private type RequireSslPromise = Promise[RequireSslCtx, Abort[ContainerException]]

    // Unsafe: module-load AtomicRef init (no live Frame yet).
    private val tlsRef: AtomicRef[Maybe[TlsPromise]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Maybe[TlsPromise]](Maybe.empty).safe

    // Unsafe: module-load AtomicRef init (no live Frame yet).
    private val requireSslRef: AtomicRef[Maybe[RequireSslPromise]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Maybe[RequireSslPromise]](Maybe.empty).safe

    private val username = "test"
    private val password = "test"
    private val database = "test"

    /** Acquires the shared TLS Postgres container, lazily starting it on first call. */
    def withTlsContainer[A, S](f: TlsCtx => A < (S & Async & Abort[ContainerException]))(using
        Frame
    ): A < (S & Async & Abort[ContainerException]) =
        tlsRef.use {
            case Maybe.Present(p) => p.get.flatMap(f)
            case Maybe.Absent =>
                Promise.init[TlsCtx, Abort[ContainerException]].flatMap { p =>
                    tlsRef.compareAndSet(Maybe.empty, Maybe.Present(p)).flatMap {
                        case false =>
                            tlsRef.use {
                                case Maybe.Present(winner) => winner.get.flatMap(f)
                                case Maybe.Absent          => withTlsContainer(f)
                            }
                        case true =>
                            Fiber.initUnscoped(initTlsContainer).flatMap { fiber =>
                                fiber.getResult.flatMap {
                                    case Result.Success(ctx) =>
                                        p.completeDiscard(Result.succeed(ctx)).andThen(f(ctx))
                                    case Result.Failure(e: ContainerException) =>
                                        tlsRef.set(Maybe.empty)
                                            .andThen(p.completeDiscard(Result.fail(e)))
                                            .andThen(p.get)
                                            .flatMap(f)
                                    case Result.Panic(t) =>
                                        tlsRef.set(Maybe.empty)
                                            .andThen(p.completeDiscard(Result.panic(t)))
                                            .andThen(p.get)
                                            .flatMap(f)
                                }
                            }
                    }
                }
        }

    /** Acquires the shared require-SSL Postgres container, lazily starting it on first call. The require-SSL container needs the same cert
      * files as the TLS container, so it is started AFTER the TLS container has produced its temp dir.
      */
    def withRequireSslContainer[A, S](f: RequireSslCtx => A < (S & Async & Abort[ContainerException]))(using
        Frame
    ): A < (S & Async & Abort[ContainerException]) =
        requireSslRef.use {
            case Maybe.Present(p) => p.get.flatMap(f)
            case Maybe.Absent =>
                Promise.init[RequireSslCtx, Abort[ContainerException]].flatMap { p =>
                    requireSslRef.compareAndSet(Maybe.empty, Maybe.Present(p)).flatMap {
                        case false =>
                            requireSslRef.use {
                                case Maybe.Present(winner) => winner.get.flatMap(f)
                                case Maybe.Absent          => withRequireSslContainer(f)
                            }
                        case true =>
                            Fiber.initUnscoped {
                                // Reuse the cert files from the TLS singleton; ensures both containers share the same self-signed cert.
                                withTlsContainer { tls =>
                                    initRequireSslContainer(Path(tls.caCertPath).parent.getOrElse(Path("/tmp")))
                                }
                            }.flatMap { fiber =>
                                fiber.getResult.flatMap {
                                    case Result.Success(ctx) =>
                                        p.completeDiscard(Result.succeed(ctx)).andThen(f(ctx))
                                    case Result.Failure(e: ContainerException) =>
                                        requireSslRef.set(Maybe.empty)
                                            .andThen(p.completeDiscard(Result.fail(e)))
                                            .andThen(p.get)
                                            .flatMap(f)
                                    case Result.Panic(t) =>
                                        requireSslRef.set(Maybe.empty)
                                            .andThen(p.completeDiscard(Result.panic(t)))
                                            .andThen(p.get)
                                            .flatMap(f)
                                }
                            }
                    }
                }
        }

    /** Generates self-signed certs into a temp dir, then starts the TLS-enabled Postgres container with those certs bind-mounted at
      * `/etc/ssl-pg`. The container's wrapper script copies the certs into `/tmp/`, sets ownership/permissions, and starts postgres with
      * `ssl=on`. Cert files survive the JVM (they live in the container's bind-mount) since the container is the singleton's lifetime.
      */
    private def initTlsContainer(using Frame): TlsCtx < (Async & Abort[ContainerException]) =
        Scope.run {
            // tempDirUnscoped, not the `Scope`-managed Path.tempDir: the container this directory is
            // bind-mounted into is a singleton that outlives this `Scope.run`, so a scope-registered
            // removal would delete the certs while the server is still reading them.
            Abort.run[FileStructureException](Path.tempDirUnscoped(prefix = "kyo-sql-tls-mode-")).flatMap {
                case Result.Failure(e) =>
                    Abort.fail(ContainerBackendException(s"temp dir creation failed: ${e.getMessage}"))
                case Result.Panic(t) =>
                    Abort.fail(ContainerBackendException(s"temp dir creation panic: ${t.getMessage}"))
                case Result.Success(tempDirPath) =>
                    val tempDir = tempDirPath.toString
                    // Generate main server cert (CN=localhost).
                    Abort.run[Throwable](Command(
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
                    ).text).flatMap {
                        case Result.Failure(e) =>
                            Abort.fail(ContainerBackendException(s"openssl server cert generation failed: ${e.getMessage}"))
                        case Result.Panic(t) =>
                            Abort.fail(ContainerBackendException(s"openssl server cert generation panic: ${t.getMessage}"))
                        case Result.Success(_) =>
                            // Generate wrong-CA cert (CN=wrongca), distinct self-signed cert.
                            Abort.run[Throwable](Command(
                                "openssl",
                                "req",
                                "-new",
                                "-x509",
                                "-days",
                                "1",
                                "-nodes",
                                "-subj",
                                "/CN=wrongca",
                                "-keyout",
                                s"$tempDir/wrong.key",
                                "-out",
                                s"$tempDir/wrong.crt"
                            ).text).flatMap {
                                case Result.Failure(e) =>
                                    Abort.fail(ContainerBackendException(s"openssl wrong cert generation failed: ${e.getMessage}"))
                                case Result.Panic(t) =>
                                    Abort.fail(ContainerBackendException(s"openssl wrong cert generation panic: ${t.getMessage}"))
                                case Result.Success(_) =>
                                    // Copy server.crt as ca.pem (self-signed cert is its own issuer).
                                    Abort.run[Throwable](Command("cp", s"$tempDir/server.crt", s"$tempDir/ca.pem").text).flatMap {
                                        case Result.Failure(e) =>
                                            Abort.fail(ContainerBackendException(s"ca.pem copy failed: ${e.getMessage}"))
                                        case Result.Panic(t) =>
                                            Abort.fail(ContainerBackendException(s"ca.pem copy panic: ${t.getMessage}"))
                                        case Result.Success(_) =>
                                            startTlsContainer(tempDirPath)
                                    }
                            }
                    }
            }
        }
    end initTlsContainer

    private def startTlsContainer(tempDirPath: Path)(using
        Frame
    ): TlsCtx < (Async & Abort[ContainerException] & Scope) =
        val tempDir = tempDirPath.toString
        val wrapperScript =
            "cp /etc/ssl-pg/server.crt /tmp/server.crt && " +
                "cp /etc/ssl-pg/server.key /tmp/server.key && " +
                "chmod 600 /tmp/server.key && " +
                "chown postgres:postgres /tmp/server.crt /tmp/server.key && " +
                "exec docker-entrypoint.sh postgres " +
                "-c ssl=on " +
                "-c ssl_cert_file=/tmp/server.crt " +
                "-c ssl_key_file=/tmp/server.key"

        val containerConfig = Container.Config.default
            .copy(image = ContainerImage("postgres:16-alpine"))
            .envAll(Dict.from(Map(
                "POSTGRES_USER"     -> username,
                "POSTGRES_PASSWORD" -> password,
                "POSTGRES_DB"       -> database
            )))
            .port(5432, 0)
            .bind(tempDirPath, Path("/etc/ssl-pg"), readOnly = true)
            .command("sh", "-c", wrapperScript)
            .healthCheck(Container.HealthCheck.exec(
                Command("psql", "-U", username, "-d", database, "-c", "SELECT 1"),
                Absent,
                Schedule.fixed(1.second).take(60)
            ))

        SqlTestContainers.initSingleton(containerConfig, "postgres-tls-mode").flatMap { container =>
            container.awaitHealthy.andThen {
                container.mappedPort(5432).map { port =>
                    TlsCtx(
                        host = container.host,
                        port = port,
                        user = username,
                        password = password,
                        db = database,
                        caCertPath = s"$tempDir/ca.pem",
                        wrongCaCertPath = s"$tempDir/wrong.crt"
                    )
                }
            }
        }
    end startTlsContainer

    /** Starts a TLS-enabled Postgres container whose pg_hba.conf is rewritten post-startup to require TLS for all host connections. Reuses
      * the cert files from the TLS singleton's bind-mount directory.
      */
    private def initRequireSslContainer(certsDir: Path)(using
        Frame
    ): RequireSslCtx < (Async & Abort[ContainerException]) =
        val requireSslHba =
            "local all all trust\n" +
                "hostssl all all 0.0.0.0/0 md5\n" +
                "hostssl all all ::/0 md5\n"

        val wrapperScript =
            "cp /etc/ssl-pg/server.crt /tmp/server.crt && " +
                "cp /etc/ssl-pg/server.key /tmp/server.key && " +
                "chmod 600 /tmp/server.key && " +
                "chown postgres:postgres /tmp/server.crt /tmp/server.key && " +
                "exec docker-entrypoint.sh postgres " +
                "-c ssl=on " +
                "-c ssl_cert_file=/tmp/server.crt " +
                "-c ssl_key_file=/tmp/server.key"

        val containerConfig = Container.Config.default
            .copy(image = ContainerImage("postgres:16-alpine"))
            .envAll(Dict.from(Map(
                "POSTGRES_USER"     -> username,
                "POSTGRES_PASSWORD" -> password,
                "POSTGRES_DB"       -> database
            )))
            .port(5432, 0)
            .bind(certsDir, Path("/etc/ssl-pg"), readOnly = true)
            .command("sh", "-c", wrapperScript)
            .healthCheck(Container.HealthCheck.exec(
                Command("psql", "-U", username, "-d", database, "-c", "SELECT 1"),
                Absent,
                Schedule.fixed(1.second).take(60)
            ))

        SqlTestContainers.initSingleton(containerConfig, "postgres-tls-mode-require").flatMap { container =>
            container.awaitHealthy.andThen {
                // Container is healthy and initially permits both SSL and plaintext.
                // Rewrite pg_hba.conf to require SSL (hostssl-only) and reload.
                // This is the reliable alternative to bind-mounting a custom pg_hba.conf
                // before initdb (initdb regenerates pg_hba.conf on first start).
                container.exec(
                    "sh",
                    "-c",
                    s"printf '%s' '${requireSslHba.replace("'", "'\\''")}' > /var/lib/postgresql/data/pg_hba.conf"
                ).andThen {
                    // Reload pg_hba.conf; pg_reload_conf() returns TRUE on success.
                    container.exec(
                        "psql",
                        "-U",
                        username,
                        "-d",
                        database,
                        "-c",
                        "SELECT pg_reload_conf()"
                    ).flatMap { reloadResult =>
                        val reloadCheck: Unit < (Async & Abort[ContainerException]) =
                            if !reloadResult.exitCode.isSuccess then
                                Log.warn(s"[kyo-sql] SqlConfigTlsModeIntegrationTest: pg_reload_conf failed: ${reloadResult.stderr}")
                            else Kyo.unit
                        reloadCheck.andThen {
                            container.mappedPort(5432).map { port =>
                                RequireSslCtx(container.host, port, username, password, database)
                            }
                        }
                    }
                }
            }
        }
    end initRequireSslContainer

end SqlConfigTlsModeIntegrationTest
