package kyo.mysql

import kyo.*
import kyo.OwnContainer
import kyo.internal.SqlTestContainers

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
  *     (ER_SECURE_TRANSPORT_REQUIRED), triggering the `requiresSecureTransport` reconnect path in MysqlSqlConnection. The test verifies the
  *     connection is established over TLS via `SHOW SESSION STATUS LIKE 'Ssl_cipher'`.
  *   - Leaves 3 and 5 share a single TLS-enabled MySQL container lazily started by a per-class CAS-singleton (see [[tlsRef]]). `mysql:8.0`
  *     auto-generates server certs on first start, so no manual cert setup is required. The container survives the test class; it carries
  *     the `kyo-sql-singleton` and `kyo-sql-owner-pid` labels, and `SqlTestContainers.initSingleton` removes every dead-owner container,
  *     together with its anonymous volumes, before creating a new singleton. There is no build-level cleanup task: a force-killed test
  *     process runs no sbt hook either.
  *   - Leaf 4 starts its own `--skip-ssl` container rather than sharing the singleton, so it is grouped with leaf 1 rather than with 3.
  *
  * Every leaf here whose title claims TLS reads `Ssl_cipher`, because this server accepts plaintext as well: a query answering correctly
  * proves the connection works and says nothing about whether it was encrypted.
  *
  * Test leaves:
  *   1. `sslmode=allow connects plaintext when server permits plaintext`, plain container (`--skip-ssl
  *      --default-authentication-plugin=mysql_native_password`); allow stays plaintext.
  *   2. `sslmode=allow upgrades to TLS when server requires TLS`, per-leaf container with `--require-secure-transport=ON`; plaintext
  *      attempt fails with error 3159; allow retries with TLS; Ssl_cipher is non-empty proving TLS is active.
  *   3. `sslmode=prefer connects with TLS when server supports TLS`, TLS container; prefer negotiates CLIENT_SSL, verified by a non-empty
  *      Ssl_cipher; connection succeeds.
  *   4. `sslmode=prefer falls back to plaintext when server refuses TLS`, plain container (`--skip-ssl
  *      --default-authentication-plugin=mysql_native_password`); no CLIENT_SSL; plaintext fallback.
  *   5. `sslmode=require accepts any cert chain`, TLS container; require mandates encryption without validating the chain, so the
  *      auto-generated self-signed cert is accepted and Ssl_cipher is non-empty.
  *   6. `sslmode=verify-ca rejects untrusted CA on MySQL`, cert container; the client is handed a CA that signed nothing on the server.
  *   7. `sslmode=verify-full rejects hostname mismatch on MySQL`, cert container reached by IP against a `CN=localhost` certificate.
  *   8. `sslmode=verify-full connects with the matching CA and hostname, over TLS`, the positive control for leaves 6 and 7.
  */
class MysqlSqlConfigTlsModeIntegrationTest extends SqlContainerTest:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 10.minutes

    import MysqlSqlConfigTlsModeIntegrationTest.*

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
                .appendServerArgs("--skip-ssl", "--default-authentication-plugin=mysql_native_password")
            val skipSslConfig = ContainerPredef.MySQL.buildContainerConfig(skipSslPredef)
            // Through `SqlTestContainers` rather than `Container.init` directly, so the container carries the
            // `kyo-sql-singleton` and `kyo-sql-owner-pid` labels and a force-killed run's leftover is still reapable.
            SqlTestContainers.initScoped(skipSslConfig, "mysql-skip-ssl").flatMap { skipSslContainer =>
                val mysql = new ContainerPredef.MySQL(skipSslContainer, skipSslPredef)
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    val url = s"mysql://${mysql.username}:${mysql.password}@${mysql.container.host}:$port/${mysql.database}?sslmode=allow"
                    MysqlClient.init(url).flatMap { client =>
                        DB.run(client) {
                            client.query("SELECT 1").flatMap { rows =>
                                assert(rows.size == 1, "sslmode=allow should connect plaintext when server has no TLS")
                                assertPlaintext("sslmode=allow against a server with no TLS")
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
    // plaintext connection with ER_SECURE_TRANSPORT_REQUIRED (error 3159). MysqlSqlConnection
    // detects this via requiresSecureTransport and retries with TLS (connectWithMode → SqlConfig.TlsMode.Require).
    // The connection succeeds and is verified to be over TLS via SHOW SESSION STATUS LIKE 'Ssl_cipher'.

    "sslmode=allow upgrades to TLS when server requires TLS".tagged("kyo.OwnContainer") in {
        Scope.run {
            // --require-secure-transport=ON: server rejects plaintext with error 3159
            //   (ER_SECURE_TRANSPORT_REQUIRED), triggering the requiresSecureTransport reconnect path.
            // --default-authentication-plugin=mysql_native_password: allows authentication
            //   without the caching_sha2_password RSA exchange over plaintext (the exchange
            //   is moot here since allow will reconnect over TLS, but the handshake on the
            //   initial plaintext attempt may touch the plugin before the 3159 error).
            val requireSslPredef = ContainerPredef.MySQL.Config.default
                .appendServerArgs(
                    "--require-secure-transport=ON",
                    "--default-authentication-plugin=mysql_native_password"
                )
            val requireSslConfig = ContainerPredef.MySQL.buildContainerConfig(requireSslPredef)
            // Labelled for the same reason as leaf 1: an unlabelled container is unreapable after a force-kill.
            SqlTestContainers.initScoped(requireSslConfig, "mysql-require-secure-transport").flatMap { requireSslContainer =>
                val mysql = new ContainerPredef.MySQL(requireSslContainer, requireSslPredef)
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    val url = s"mysql://${mysql.username}:${mysql.password}@${mysql.container.host}:$port/${mysql.database}?sslmode=allow"
                    MysqlClient.init(url).flatMap { client =>
                        DB.run(client) {
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

    // ── Leaf 3: sslmode=prefer connects with TLS when server supports TLS ─────
    // Uses the shared TLS container (mysql:8.0, CLIENT_SSL advertised).
    // prefer mode: HandshakeExchange sees CLIENT_SSL in server capabilities → upgrades to TLS.

    "sslmode=prefer connects with TLS when server supports TLS".tagged("kyo.OwnContainer") in {
        // The shared TLS container (mysql:8.0) auto-generates certs; CLIENT_SSL is advertised.
        // prefer mode with trustAll=true: upgrade to TLS when server supports it.
        withTlsContainer { ctx =>
            val url = s"mysql://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=prefer"
            Scope.run {
                MysqlClient.init(url).flatMap { client =>
                    DB.run(client) {
                        // A plaintext connection to this same server answers 'SELECT' identically, so the
                        // cipher is the only thing that distinguishes prefer having negotiated CLIENT_SSL
                        // from prefer having silently stayed on plaintext.
                        client.query("SHOW SESSION STATUS LIKE 'Ssl_cipher'").flatMap { statusRows =>
                            val cipher = statusRows.headMaybe.fold("") { row =>
                                row.column(1).fold("")(b => new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8))
                            }
                            assert(
                                cipher.nonEmpty,
                                "sslmode=prefer must negotiate TLS when the server advertises CLIENT_SSL (Ssl_cipher was empty, connection is plaintext)"
                            )
                            // Then verify the connection succeeds and is reusable.
                            client.query("SELECT 'prefer_tls_ok'").map { rows =>
                                assert(rows.size == 1, "sslmode=prefer should connect with TLS when server supports it")
                                val value =
                                    rows(0).column(0).fold("")(b => new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8))
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
                .appendServerArgs("--skip-ssl", "--default-authentication-plugin=mysql_native_password")
            val skipSslConfig2 = ContainerPredef.MySQL.buildContainerConfig(skipSslPredef2)
            // Labelled for the same reason as leaf 1: an unlabelled container is unreapable after a force-kill.
            SqlTestContainers.initScoped(skipSslConfig2, "mysql-skip-ssl").flatMap { skipSslContainer2 =>
                val mysql = new ContainerPredef.MySQL(skipSslContainer2, skipSslPredef2)
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    val url = s"mysql://${mysql.username}:${mysql.password}@${mysql.container.host}:$port/${mysql.database}?sslmode=prefer"
                    MysqlClient.init(url).flatMap { client =>
                        DB.run(client) {
                            // Connection must succeed via plaintext fallback when no CLIENT_SSL.
                            client.query("SELECT 1").flatMap { rows =>
                                assert(rows.size == 1, "sslmode=prefer should fall back to plaintext when server has no TLS")
                                assertPlaintext("sslmode=prefer falling back against a server with no TLS")
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
    // MysqlConnection derives preferFallback from the mode, false for Require since only Prefer falls back, and
    // HandshakeExchange.run fails when the server does not advertise CLIENT_SSL.

    "sslmode=require accepts any cert chain".tagged("kyo.OwnContainer") in {
        // Shared TLS container (mysql:8.0, CLIENT_SSL advertised, self-signed cert).
        // require: TLS mandatory; no CA validation; the auto-generated self-signed cert is accepted.
        withTlsContainer { ctx =>
            val url = s"mysql://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=require"
            Scope.run {
                MysqlClient.init(url).flatMap { client =>
                    DB.run(client) {
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

    // ── Leaf 6: sslmode=verify-ca rejects untrusted CA on MySQL ──────────────
    // Uses the cert-container fixture (MySQL started with --ssl-cert/--ssl-key/--ssl-ca pointing at a
    // self-signed server cert). The client is given a *different* self-signed CA (wrongCaCertPath) so
    // the chain verification must fail.
    // preferFallback is false for VerifyCa, since only Prefer falls back, and TlsContext.build produces a
    // NetTlsConfig carrying caCertPath with hostnameVerification=false, so the JVM TLS layer rejects the cert
    // before the MySQL handshake completes.
    //
    // The mysql:8.0 auto-generated certs cannot serve this leaf: the client needs the exact CA PEM that signed
    // the server cert, and those certs live in the container's ephemeral filesystem with no host-accessible path.
    // Hence the cert-container fixture, which generates a cert with `openssl req -new -x509`, bind-mounts it at
    // `/etc/mysql/ssl`, and starts mysqld with `--ssl-cert=... --ssl-key=... --ssl-ca=...`.

    "sslmode=verify-ca rejects untrusted CA on MySQL".tagged("kyo.OwnContainer") in {
        withCertContainer { ctx =>
            val url =
                s"mysql://${ctx.user}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.db}?sslmode=verify-ca&sslrootcert=${ctx.wrongCaCertPath}"
            Abort.run[SqlException] {
                Scope.run {
                    SqlClient.init(url).flatMap { client =>
                        DB.run(client)(client.query("SELECT 1"))
                    }
                }
            }.map {
                // The SUBTYPE is the property, not merely that something failed. A chain the supplied CA never
                // signed must be refused by the TLS layer. Accepting any SqlException here would also pass on a
                // container that never started and on a wrong password, which would make this leaf green for
                // reasons that have nothing to do with certificate validation. Leaf 8 below is the positive
                // control on the same container: the matching CA must connect AND report a non-empty Ssl_cipher.
                case Result.Failure(_: SqlConnectionConnectFailedException) => succeed
                case Result.Success(_) =>
                    fail("expected the handshake to fail: the server chain was not signed by the supplied CA")
                case Result.Panic(t) => fail(s"unexpected panic verifying an untrusted CA: ${t.getMessage}")
                case Result.Failure(other) =>
                    fail(s"expected SqlConnectionConnectFailedException from chain validation, got: $other")
            }
        }
    }

    // ── Leaf 7: sslmode=verify-full rejects hostname mismatch on MySQL ────────
    // Positive TLS upgrade with full chain verification; client connects via an IP address while the
    // server cert has CN=localhost, hostname mismatch must cause TlsException before handshake.
    // preferFallback is false for VerifyFull, since only Prefer falls back, and TlsContext.build produces a
    // NetTlsConfig carrying caCertPath with hostnameVerification=true.
    //
    // Reached through the same cert-container fixture as leaf 6, which is what supplies a `CN=localhost`
    // certificate and a host-accessible CA PEM.

    "sslmode=verify-full rejects hostname mismatch on MySQL".tagged("kyo.OwnContainer") in {
        withCertContainer { ctx =>
            // Reach the SAME container by its IP literal while the certificate says CN=localhost, so the chain
            // is valid and only the hostname disagrees. That is what separates verify-full from verify-ca: the
            // CA here is the correct one, and leaf 6 above already covers the wrong-CA rejection.
            val url =
                s"mysql://${ctx.user}:${ctx.password}@127.0.0.1:${ctx.port}/${ctx.db}?sslmode=verify-full&sslrootcert=${ctx.caCertPath}"
            Abort.run[SqlException] {
                Scope.run {
                    SqlClient.init(url).flatMap { client =>
                        DB.run(client)(client.query("SELECT 1"))
                    }
                }
            }.map {
                case Result.Failure(_: SqlConnectionConnectFailedException) => succeed
                case Result.Success(_) =>
                    fail("expected the handshake to fail: 127.0.0.1 does not match the certificate's CN=localhost")
                case Result.Panic(t) => fail(s"unexpected panic verifying a hostname mismatch: ${t.getMessage}")
                case Result.Failure(other) =>
                    fail(s"expected SqlConnectionConnectFailedException from hostname verification, got: $other")
            }
        }
    }

    // ── Leaf 8: the positive control for leaves 6 and 7 ───────────────────────
    // Without this, both negatives above are satisfied by a container that never started. This reaches the same
    // container by the name the certificate actually carries, with the CA that actually signed it, and then
    // OBSERVES the transport rather than inferring it from the query succeeding.

    "sslmode=verify-full connects with the matching CA and hostname, over TLS".tagged("kyo.OwnContainer") in {
        withCertContainer { ctx =>
            val url =
                s"mysql://${ctx.user}:${ctx.password}@localhost:${ctx.port}/${ctx.db}?sslmode=verify-full&sslrootcert=${ctx.caCertPath}"
            Scope.run {
                SqlClient.init(url).flatMap { client =>
                    DB.run(client) {
                        client.query("SHOW SESSION STATUS LIKE 'Ssl_cipher'").map { rows =>
                            assert(rows.size == 1, "SHOW SESSION STATUS LIKE 'Ssl_cipher' must return a row")
                            val cipher = rows.headMaybe.fold("") { row =>
                                row.column(1).fold("")(b => new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8))
                            }
                            assert(
                                cipher.nonEmpty,
                                "verify-full with the matching CA and hostname must end up on TLS (Ssl_cipher was empty, connection is plaintext)"
                            )
                        }
                    }
                }
            }
        }
    }

end MysqlSqlConfigTlsModeIntegrationTest

object MysqlSqlConfigTlsModeIntegrationTest:

    /** Reads `Ssl_cipher` off the ambient client and asserts the session is NOT encrypted.
      *
      * A leaf that claims TLS reads this variable, because a query answering correctly says nothing about the transport. A leaf claiming
      * PLAINTEXT makes an equally specific claim, and container configuration alone is not evidence for it: a fixture whose flags drift
      * leaves the title unbacked. So the premise is checked rather than trusted.
      *
      * The failure message carries the observed value on purpose. MySQL reports a session with no TLS as `Ssl_cipher` present and EMPTY
      * rather than absent, which is a shape nobody here has observed rather than reasoned about, so if it is wrong the first run says what
      * the server actually sent instead of only that an assertion failed.
      */
    private def assertPlaintext(context: String)(using Frame, kyo.test.AssertScope): Unit < (Abort[SqlException] & DB) =
        DB.client.map(_.query("SHOW SESSION STATUS LIKE 'Ssl_cipher'")).map { rows =>
            assert(rows.size == 1, s"$context: SHOW SESSION STATUS LIKE 'Ssl_cipher' must return exactly one row, got ${rows.size}")
            val cipher = rows.headMaybe.fold("") { row =>
                row.column(1).fold("")(b => new String(b.toArray, java.nio.charset.StandardCharsets.UTF_8))
            }
            assert(cipher.isEmpty, s"$context: the connection must be plaintext, but Ssl_cipher reports '$cipher'")
        }

    /** Connection details for the shared MySQL TLS container.
      *
      * Built once on first leaf access; subsequent leaves reuse the same container.
      */
    final case class TlsCtx(host: String, port: Int, user: String, password: String, db: String)

    private type TlsPromise = Promise[TlsCtx, Abort[ContainerException]]

    // Unsafe: module-load AtomicRef init (no live Frame yet).
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

    /** Connection details for the KNOWN-CERTIFICATE MySQL container, which is a different fixture from [[TlsCtx]] above.
      *
      * The shared container uses MySQL's own auto-generated certificate. That is enough for `sslmode=require`, which accepts any chain, and
      * useless for `verify-ca` and `verify-full`, which need a CA file the client can be handed and a subject the hostname can be checked
      * against. So this fixture generates its own: a server certificate with `CN=localhost`, a `ca.pem` that is a copy of it (a self-signed
      * certificate is its own issuer), and a SECOND unrelated self-signed certificate that serves as the wrong CA for the negative leaves.
      */
    final case class CertCtx(
        host: String,
        port: Int,
        user: String,
        password: String,
        db: String,
        caCertPath: String,
        wrongCaCertPath: String
    )

    private type CertPromise = Promise[CertCtx, Abort[ContainerException]]

    // Unsafe: module-load AtomicRef init (no live Frame yet).
    private val certRef: AtomicRef[Maybe[CertPromise]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Maybe[CertPromise]](Maybe.empty).safe

    /** Acquires the known-certificate container, lazily starting it on first call. Same lose-the-CAS-race-and-wait shape as
      * [[withTlsContainer]]; the block is repeated rather than factored because the sibling PostgreSQL fixture repeats it too, so a shared
      * helper would be a cross-module refactor of working concurrency code rather than part of this fixture.
      */
    def withCertContainer[A, S](f: CertCtx => A < (S & Async & Abort[ContainerException]))(using
        Frame
    ): A < (S & Async & Abort[ContainerException]) =
        certRef.use {
            case Maybe.Present(p) => p.get.flatMap(f)
            case Maybe.Absent =>
                Promise.init[CertCtx, Abort[ContainerException]].flatMap { p =>
                    certRef.compareAndSet(Maybe.empty, Maybe.Present(p)).flatMap {
                        case false =>
                            certRef.use {
                                case Maybe.Present(winner) => winner.get.flatMap(f)
                                case Maybe.Absent          => withCertContainer(f)
                            }
                        case true =>
                            Fiber.initUnscoped(initCertContainer).flatMap { fiber =>
                                fiber.getResult.flatMap {
                                    case Result.Success(ctx) =>
                                        p.completeDiscard(Result.succeed(ctx)).andThen(f(ctx))
                                    case Result.Failure(e: ContainerException) =>
                                        certRef.set(Maybe.empty)
                                            .andThen(p.completeDiscard(Result.fail(e)))
                                            .andThen(p.get)
                                            .flatMap(f)
                                    case Result.Panic(t) =>
                                        certRef.set(Maybe.empty)
                                            .andThen(p.completeDiscard(Result.panic(t)))
                                            .andThen(p.get)
                                            .flatMap(f)
                                }
                            }
                    }
                }
        }

    /** Generates the certificates on the host and starts a MySQL container serving them. */
    private def initCertContainer(using Frame): CertCtx < (Async & Abort[ContainerException]) =
        Scope.run {
            Abort.run[FileFsException](Path.tempDir(prefix = "kyo-sql-mysql-certs-")).flatMap {
                case Result.Failure(e) => Abort.fail(ContainerBackendException(s"temp dir creation failed: ${e.getMessage}"))
                case Result.Panic(t)   => Abort.fail(ContainerBackendException(s"temp dir creation panic: ${t.getMessage}"))
                case Result.Success(tempDirPath) =>
                    val tempDir = tempDirPath.toString
                    openssl(tempDir, "localhost", "server").andThen {
                        openssl(tempDir, "wrongca", "wrong").andThen {
                            // A self-signed certificate is its own issuer, so the CA the client trusts IS the server certificate.
                            Abort.run[Throwable](Command("cp", s"$tempDir/server.crt", s"$tempDir/ca.pem").text).flatMap {
                                case Result.Failure(e) => Abort.fail(ContainerBackendException(s"ca.pem copy failed: ${e.getMessage}"))
                                case Result.Panic(t)   => Abort.fail(ContainerBackendException(s"ca.pem copy panic: ${t.getMessage}"))
                                case Result.Success(_) => startCertContainer(tempDirPath)
                            }
                        }
                    }
            }
        }
    end initCertContainer

    /** One `openssl req -x509` invocation, writing `<name>.key` and `<name>.crt` into `dir` with the given CN. */
    private def openssl(dir: String, cn: String, name: String)(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.run[Throwable](Command(
            "openssl",
            "req",
            "-new",
            "-x509",
            "-days",
            "1",
            "-nodes",
            "-subj",
            s"/CN=$cn",
            "-keyout",
            s"$dir/$name.key",
            "-out",
            s"$dir/$name.crt"
        ).text).flatMap {
            case Result.Failure(e) => Abort.fail(ContainerBackendException(s"openssl $name cert generation failed: ${e.getMessage}"))
            case Result.Panic(t)   => Abort.fail(ContainerBackendException(s"openssl $name cert generation panic: ${t.getMessage}"))
            case Result.Success(_) => ()
        }

    /** Starts the container with the generated certificates bind-mounted, and hands mysqld its TLS flags.
      *
      * THIS SITE REPLACES THE COMMAND LINE ON PURPOSE, which no other fixture here does, so the difference is worth stating. mysqld will
      * not read a private key that is group or world readable, and a bind mount arrives with the host's ownership, so the key has to be
      * copied and re-owned inside the container before the server starts. That needs a shell, which means replacing the command.
      *
      * The consequence is that `buildContainerConfig`'s command line is discarded, and with it BOTH the `mysqld` executable and
      * [[ContainerPredef.MySQL.defaultServerArgs]]. So the wrapper restates them explicitly. Dropping them costs real memory silently:
      * `--performance-schema=OFF` alone is worth roughly 350MB per container at boot, and the official image hides the loss by prepending
      * `mysqld` to any argv whose first element starts with a dash.
      */
    private def startCertContainer(tempDirPath: Path)(using Frame): CertCtx < (Async & Abort[ContainerException] & Scope) =
        val tempDir  = tempDirPath.toString
        val username = "test"
        val password = "test"
        val database = "test"
        val predef   = ContainerPredef.MySQL.Config.default.copy(username = username, password = password, database = database)
        val wrapperScript =
            "cp /etc/ssl-my/server.crt /etc/ssl-my/server.key /etc/ssl-my/ca.pem /tmp/ && " +
                "chmod 600 /tmp/server.key && " +
                "chown mysql:mysql /tmp/server.crt /tmp/server.key /tmp/ca.pem && " +
                "exec docker-entrypoint.sh mysqld " +
                ContainerPredef.MySQL.defaultServerArgs.mkString(" ") +
                " --ssl-ca=/tmp/ca.pem --ssl-cert=/tmp/server.crt --ssl-key=/tmp/server.key"
        val cfg = ContainerPredef.MySQL.buildContainerConfig(predef)
            .bind(tempDirPath, Path("/etc/ssl-my"), readOnly = true)
            .command("sh", "-c", wrapperScript)
        SqlTestContainers.initSingleton(cfg, "mysql-tls-certs").flatMap { container =>
            container.awaitHealthy.andThen {
                container.mappedPort(ContainerPredef.MySQL.defaultPort).map { port =>
                    CertCtx(
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
    end startCertContainer

    /** Starts a plain `mysql:8.0` container, auto-generated certs make CLIENT_SSL available out of the box. The container is left running
      * for the JVM's lifetime; `SqlTestContainers.initSingleton` reaps it, with its anonymous volumes, on the next container-using run once
      * this process is gone.
      */
    private def initTlsContainer(using Frame): TlsCtx < (Async & Abort[ContainerException]) =
        val username = "test"
        val password = "test"
        val database = "test"
        val predef   = ContainerPredef.MySQL.Config.default.copy(username = username, password = password, database = database)
        val cfg      = ContainerPredef.MySQL.buildContainerConfig(predef)
        SqlTestContainers.initSingleton(cfg, "mysql-tls-mode").flatMap { container =>
            val mysql = new ContainerPredef.MySQL(container, predef)
            mysql.container.mappedPort(mysql.config.port).map { port =>
                TlsCtx(mysql.container.host, port, username, password, database)
            }
        }
    end initTlsContainer

end MysqlSqlConfigTlsModeIntegrationTest
