package kyo.mysql

import kyo.*
import kyo.OwnContainer

/** Integration tests for MySQL sslmode `allow` and `prefer` (opportunistic TLS).
  *
  * Container groups:
  *   - Leaf 1 uses `ContainerPredef.MySQL.Config.default.cmd(Chunk("--skip-ssl", "--default-authentication-plugin=mysql_native_password"))`
  * a per-leaf MySQL container with TLS disabled. SSL is disabled at the mysqld level so the server advertises no CLIENT_SSL
  *     capability: prefer falls back to plaintext, and allow stays on plaintext. The
  *     `--default-authentication-plugin=mysql_native_password` flag is required alongside `--skip-ssl` so the health-check client and
  *     kyo-sql client can authenticate over plaintext without TLS or RSA key exchange (caching_sha2_password, the MySQL 8.0 default, cannot
  *     complete fast-auth without TLS).
  *   - Leaf 2 uses a per-leaf MySQL container started with `--require-secure-transport=ON
  *     --default-authentication-plugin=mysql_native_password`. The server rejects any plaintext connection with error 3159
  *     (ER_SECURE_TRANSPORT_REQUIRED), triggering the `myIsSslRequired` reconnect path in SqlClientBackend. The test verifies the
  *     connection is established over TLS via `SHOW SESSION STATUS LIKE 'Ssl_cipher'`.
  *   - Leaves 3 and 4 share a single TLS-enabled MySQL container lazily started by a per-class CAS-singleton (see [[tlsRef]]). `mysql:8.0`
  *     auto-generates server certs on first start, so no manual cert setup is required. The container survives the test class; orphan
  *     cleanup is handled by the sbt `Test / testOptions` setup task that reaps `kyo-sql-singleton`-labelled containers between
  *     invocations.
  *
  * Test leaves:
  *   1. `sslmode=allow connects plaintext when server permits plaintext`, plain container (`--skip-ssl
  *      --default-authentication-plugin=mysql_native_password`); allow stays plaintext.
  *   2. `sslmode=allow upgrades to TLS when server requires TLS`, per-leaf container with `--require-secure-transport=ON`; plaintext
  *      attempt fails with error 3159; allow retries with TLS; Ssl_cipher is non-empty proving TLS is active.
  *   3. `sslmode=prefer connects with TLS when server supports TLS`, TLS container; prefer negotiates CLIENT_SSL; connection succeeds.
  *   4. `sslmode=prefer falls back to plaintext when server refuses TLS`, plain container (`--skip-ssl
  *      --default-authentication-plugin=mysql_native_password`); no CLIENT_SSL; plaintext fallback.
  */
class MysqlTlsModeIntegrationTest extends kyo.Test:

    override def timeout: Duration = 10.minutes

    import MysqlTlsModeIntegrationTest.*

    // ── Leaf 1: sslmode=allow connects plaintext when server permits plaintext ─
    // Uses a per-leaf MySQL container started with --skip-ssl --default-authentication-plugin=mysql_native_password
    // (no CLIENT_SSL capability). allow mode: try plaintext first → server accepts → stay plaintext. No reconnect.

    "sslmode=allow connects plaintext when server permits plaintext".tagged("kyo.OwnContainer") in {
        Scope.run {
            // --skip-ssl disables server-side TLS so the server does not advertise CLIENT_SSL.
            // --default-authentication-plugin=mysql_native_password is required alongside --skip-ssl so
            // that the 'test' user can authenticate over plaintext; caching_sha2_password (8.0 default)
            // does not complete its fast-auth path without TLS or RSA key exchange.
            val skipSslPredef = ContainerPredef.MySQL.Config.default
            val skipSslConfig = ContainerPredef.MySQL.buildContainerConfig(skipSslPredef)
                .command("--skip-ssl", "--default-authentication-plugin=mysql_native_password")
            Container.initWith(skipSslConfig) { skipSslContainer =>
                val mysql = new ContainerPredef.MySQL(skipSslContainer, skipSslPredef)
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    val url = s"mysql://${mysql.username}:${mysql.password}@${mysql.container.host}:$port/${mysql.database}?sslmode=allow"
                    Async.timeout(60.seconds) {
                        SqlClient.initMy(url).flatMap { client =>
                            SqlClient.let(client) {
                                client.query("SELECT 1").map { rows =>
                                    assert(rows.size == 1, "sslmode=allow should connect plaintext when server has no TLS")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 2: sslmode=allow upgrades to TLS when server requires TLS ────────
    // Uses a per-leaf MySQL container started with --require-secure-transport=ON
    // --default-authentication-plugin=mysql_native_password. The server rejects any
    // plaintext connection with ER_SECURE_TRANSPORT_REQUIRED (error 3159). SqlClientBackend
    // detects this via myIsSslRequired and retries with TLS (connectWithMode → TlsMode.Require).
    // The connection succeeds and is verified to be over TLS via SHOW SESSION STATUS LIKE 'Ssl_cipher'.

    "sslmode=allow upgrades to TLS when server requires TLS".tagged("kyo.OwnContainer") in {
        Scope.run {
            // --require-secure-transport=ON: server rejects plaintext with error 3159
            //   (ER_SECURE_TRANSPORT_REQUIRED), triggering the myIsSslRequired reconnect path.
            // --default-authentication-plugin=mysql_native_password: allows authentication
            //   without the caching_sha2_password RSA exchange over plaintext (the exchange
            //   is moot here since allow will reconnect over TLS, but the handshake on the
            //   initial plaintext attempt may touch the plugin before the 3159 error).
            val requireSslPredef = ContainerPredef.MySQL.Config.default
            val requireSslConfig = ContainerPredef.MySQL.buildContainerConfig(requireSslPredef)
                .command(
                    "--require-secure-transport=ON",
                    "--default-authentication-plugin=mysql_native_password"
                )
            Container.initWith(requireSslConfig) { requireSslContainer =>
                val mysql = new ContainerPredef.MySQL(requireSslContainer, requireSslPredef)
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    val url = s"mysql://${mysql.username}:${mysql.password}@${mysql.container.host}:$port/${mysql.database}?sslmode=allow"
                    Async.timeout(60.seconds) {
                        SqlClient.initMy(url).flatMap { client =>
                            SqlClient.let(client) {
                                // The allow reconnect path fired and the connection is now over TLS.
                                // Verify via SHOW SESSION STATUS LIKE 'Ssl_cipher': the cipher column
                                // must be non-empty for an active TLS connection.
                                client.query("SHOW SESSION STATUS LIKE 'Ssl_cipher'").map { rows =>
                                    assert(
                                        rows.nonEmpty,
                                        "SHOW SESSION STATUS LIKE 'Ssl_cipher' must return a row"
                                    )
                                    val cipher =
                                        rows(0).column(1).fold("")(b => new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8))
                                    assert(
                                        cipher.nonEmpty,
                                        s"Ssl_cipher must be non-empty when sslmode=allow upgrades to TLS (got empty, connection is plaintext)"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 3: sslmode=prefer connects with TLS when server supports TLS ─────
    // Uses the shared TLS container (mysql:8.0, CLIENT_SSL advertised).
    // prefer mode: HandshakeExchange sees CLIENT_SSL in server capabilities → upgrades to TLS.

    "sslmode=prefer connects with TLS when server supports TLS".tagged("kyo.OwnContainer") in {
        // The shared TLS container (mysql:8.0) auto-generates certs; CLIENT_SSL is advertised.
        // prefer mode with trustAll=true: upgrade to TLS when server supports it.
        Async.timeout(60.seconds) {
            withTlsContainer { ctx =>
                val url = s"mysql://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=prefer"
                Scope.run {
                    SqlClient.initMy(url).flatMap { client =>
                        SqlClient.let(client) {
                            // Verify connection succeeds and is reusable.
                            client.query("SELECT 'prefer_tls_ok'").map { rows =>
                                assert(rows.size == 1, "sslmode=prefer should connect with TLS when server supports it")
                                val value = rows(0).column(0).fold("")(b => new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8))
                                assert(value == "prefer_tls_ok", s"Expected 'prefer_tls_ok', got '$value'")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 4: sslmode=prefer falls back to plaintext when server refuses TLS ─
    // Uses a per-leaf MySQL container started with --skip-ssl --default-authentication-plugin=mysql_native_password
    // (no CLIENT_SSL capability). prefer mode: HandshakeExchange sees no CLIENT_SSL → preferFallback=true → plaintext fallback.

    "sslmode=prefer falls back to plaintext when server refuses TLS".tagged("kyo.OwnContainer") in {
        Scope.run {
            // --skip-ssl disables server-side TLS so the server does not advertise CLIENT_SSL.
            // --default-authentication-plugin=mysql_native_password is required alongside --skip-ssl so
            // that the 'test' user can authenticate over plaintext; caching_sha2_password (8.0 default)
            // does not complete its fast-auth path without TLS or RSA key exchange.
            val skipSslPredef2 = ContainerPredef.MySQL.Config.default
            val skipSslConfig2 = ContainerPredef.MySQL.buildContainerConfig(skipSslPredef2)
                .command("--skip-ssl", "--default-authentication-plugin=mysql_native_password")
            Container.initWith(skipSslConfig2) { skipSslContainer2 =>
                val mysql = new ContainerPredef.MySQL(skipSslContainer2, skipSslPredef2)
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    val url = s"mysql://${mysql.username}:${mysql.password}@${mysql.container.host}:$port/${mysql.database}?sslmode=prefer"
                    Async.timeout(60.seconds) {
                        SqlClient.initMy(url).flatMap { client =>
                            SqlClient.let(client) {
                                // Connection must succeed via plaintext fallback when no CLIENT_SSL.
                                client.query("SELECT 1").map { rows =>
                                    assert(rows.size == 1, "sslmode=prefer should fall back to plaintext when server has no TLS")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 5: sslmode=require accepts any cert chain ────────────────────────
    // Uses the shared TLS container (mysql:8.0 auto-generates a self-signed server cert).
    // require mode: TLS mandatory, no CA or hostname verification (trustAll=true in NetTlsConfig).
    // The self-signed cert is accepted because require does not validate the chain.
    // MysqlNegotiator dispatches Require → preferFallback=false; HandshakeExchange fails if no CLIENT_SSL.

    "sslmode=require accepts any cert chain".tagged("kyo.OwnContainer") in {
        // Shared TLS container (mysql:8.0, CLIENT_SSL advertised, self-signed cert).
        // require: TLS mandatory; no CA validation; the auto-generated self-signed cert is accepted.
        Async.timeout(60.seconds) {
            withTlsContainer { ctx =>
                val url = s"mysql://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=require"
                Scope.run {
                    SqlClient.initMy(url).flatMap { client =>
                        SqlClient.let(client) {
                            // Verify the connection is active and serving queries, not just opened.
                            client.query("SHOW SESSION STATUS LIKE 'Ssl_cipher'").flatMap { rows =>
                                val cipher = rows.headMaybe.fold("") { row =>
                                    row.column(1).fold("")(b => new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8))
                                }
                                assert(
                                    cipher.nonEmpty,
                                    s"sslmode=require must establish a TLS connection (Ssl_cipher was empty, connection is plaintext)"
                                )
                                client.query("SELECT 'require_ok'").map { rows2 =>
                                    assert(rows2.size == 1, "sslmode=require should complete a query after TLS handshake")
                                    val value =
                                        rows2(0).column(0).fold("")(b => new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8))
                                    assert(value == "require_ok", s"Expected 'require_ok', got '$value'")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 6: sslmode=verify-ca rejects untrusted CA on MySQL ──────────────
    // Uses the cert-container fixture (MySQL started with --ssl-cert/--ssl-key/--ssl-ca pointing at a
    // self-signed server cert). The client is given a *different* self-signed CA (wrongCaCertPath) so
    // the chain verification must fail.
    // MysqlNegotiator dispatches VerifyCa → preferFallback=false; NetTlsConfig carries caCertPath +
    // hostnameVerification=false; the JVM TLS layer rejects the cert before the MySQL handshake completes.
    //
    // Blocker: requires a MySQL container started with --ssl-cert / --ssl-key / --ssl-ca pointing at
    // generated cert files bind-mounted into the container. The mysql:8.0 auto-generated certs cannot be
    // used here because the client needs the exact CA PEM that signed the server cert, and the
    // auto-generated certs are written inside the container's ephemeral filesystem with no host-accessible
    // path. Implementing this leaf requires the same openssl + bind-mount infrastructure used by
    // TlsModeIntegrationTest (Postgres), cert generation with `openssl req -new -x509`, a bind-mount
    // at `/etc/mysql/ssl`, and mysqld flags `--ssl-cert=... --ssl-key=... --ssl-ca=...`. Until that
    // infrastructure is wired for MySQL containers, this leaf is marked pending.

    "sslmode=verify-ca rejects untrusted CA on MySQL".tagged("kyo.OwnContainer").ignore("pending") - {}

    // ── Leaf 7: sslmode=verify-full rejects hostname mismatch on MySQL ────────
    // Positive TLS upgrade with full chain verification; client connects via an IP address while the
    // server cert has CN=localhost, hostname mismatch must cause TlsException before handshake.
    // MysqlNegotiator dispatches VerifyFull → preferFallback=false; NetTlsConfig carries caCertPath +
    // hostnameVerification=true.
    //
    // Blocker: same as Leaf 6, requires a MySQL container started with --ssl-cert / --ssl-key /
    // --ssl-ca pointing at an openssl-generated cert with CN=localhost, and the CA PEM accessible on the
    // host filesystem. Marked pending until the MySQL cert-container infrastructure is in place.

    "sslmode=verify-full rejects hostname mismatch on MySQL".tagged("kyo.OwnContainer").ignore("pending") - {}

end MysqlTlsModeIntegrationTest

object MysqlTlsModeIntegrationTest:

    /** Connection details for the shared MySQL TLS container.
      *
      * Built once on first leaf access; subsequent leaves reuse the same container.
      */
    final case class TlsCtx(host: String, port: Int, user: String, password: String, db: String)

    private type TlsPromise = Promise[TlsCtx, Abort[ContainerException]]

    // Unsafe boundary: module-load AtomicRef init (no live Frame yet).
    private val tlsRef: AtomicRef[Maybe[TlsPromise]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Maybe[TlsPromise]](Maybe.empty).safe

    /** Acquires the shared MySQL-TLS container, lazily starting it on first call. Concurrent callers that lose the CAS race wait on the
      * same [[Promise]]. On startup failure the slot is reset so the next caller retries.
      */
    def withTlsContainer[A, S](f: TlsCtx => A < (S & Async & Abort[ContainerException]))(using
        Frame
    ): A < (S & Async & Abort[ContainerException]) =
        tlsRef.use {
            case Maybe.Present(p) => p.get.flatMap(f)
            case Maybe.Absent =>
                Promise.init[TlsCtx, Abort[ContainerException]].flatMap { p =>
                    tlsRef.compareAndSet(Maybe.empty, Maybe.Present(p)).flatMap {
                        case false =>
                            // Lost the race; await the winner (or recurse if the slot was reset due to failure).
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
                                        // Reset slot first so subsequent callers retry instead of seeing a poisoned Promise.
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

    /** Starts a plain `mysql:8.0` container, auto-generated certs make CLIENT_SSL available out of the box. The container is left running
      * for the JVM's lifetime; orphan reaping is handled by the sbt setup task via the `kyo-sql-singleton` label.
      */
    private def initTlsContainer(using Frame): TlsCtx < (Async & Abort[ContainerException]) =
        val username = "test"
        val password = "test"
        val database = "test"
        val predef   = ContainerPredef.MySQL.Config.default.copy(username = username, password = password, database = database)
        val cfg = ContainerPredef.MySQL
            .buildContainerConfig(predef)
            .label("kyo-sql-singleton", "mysql-tls-mode")
        Container.initUnscoped(cfg).flatMap { container =>
            val mysql = new ContainerPredef.MySQL(container, predef)
            mysql.container.mappedPort(mysql.config.port).map { port =>
                TlsCtx(mysql.container.host, port, username, password, database)
            }
        }
    end initTlsContainer

end MysqlTlsModeIntegrationTest
