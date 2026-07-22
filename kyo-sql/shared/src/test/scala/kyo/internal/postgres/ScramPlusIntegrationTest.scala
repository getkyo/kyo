package kyo.internal.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.OwnContainer
import kyo.net.NetTlsConfig

/** Protocol-level integration tests for SCRAM-SHA-256-PLUS (channel binding).
  *
  * Tests cover 6 leaves using PostgresConnection.connectWithCertHashOverride:
  *   1. SCRAM-SHA-256-PLUS over TLS succeeds and is verified as the selected mechanism.
  *   2. Plaintext connect falls back to SCRAM-SHA-256 (no cert hash → no PLUS).
  *   3. Wrong cert hash (MITM simulation) is rejected with SQLSTATE 28000 (invalid_authorization_specification).
  *   4. Client with forced-Absent cert hash refuses PLUS even on TLS.
  *   5. Client picks SCRAM-SHA-256 when no cert hash; server accepts.
  *   6. Wrong password under SCRAM-PLUS produces sqlState=28P01; state does NOT leak.
  *
  * Pure-unit leaves (shared/JVM impl byte-identity, c= attribute decoding) live in kyo.internal.postgres.auth.ScramSha256Test.
  *
  * Fixture architecture (shared-container, post-startup SSL enable):
  *   - One Postgres container started lazily by a per-class CAS-singleton (see [[tlsRef]]); shared across all TLS leaves and surviving the
  *     test class. Orphan reaping is handled by the sbt `Test / testOptions` setup task that removes containers labelled
  *     `kyo-sql-singleton` between test invocations.
  *   - SSL is enabled AFTER startup via `ALTER SYSTEM SET ssl = on` (and ssl_cert_file/ssl_key_file) followed by `pg_reload_conf()`. These
  *     settings have context='sighup' in PG 16, so SIGHUP suffices, no restart needed (which would kill PID 1 in Docker).
  *   - The default postgres:16-alpine pg_hba.conf already requires scram-sha-256 for host connections; over TLS, the server offers both
  *     SCRAM-SHA-256 and SCRAM-SHA-256-PLUS in the AuthenticationSASL message.
  *   - `awaitSslReady` probe confirms TLS is actually active before any leaf runs.
  *   - Leaf 2 uses `ContainerPredef.Postgres` (no TLS needed, plaintext SCRAM-SHA-256).
  */
class ScramPlusIntegrationTest extends kyo.Test:

    override def timeout: Duration = 10.minutes

    import ScramPlusIntegrationTest.*

    // ── Helpers: access shared fixture values inside leaves ───────────────────

    /** Runs the test body against the shared TLS+SCRAM-SHA-256 Postgres container. */
    private def withPostgresTls[A, S](
        f: (
            host: String,
            port: Int,
            user: String,
            password: String,
            db: String,
            tlsConfig: NetTlsConfig
        ) => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Abort[SqlException | ContainerException]) =
        withTlsContainer { ctx =>
            f(ctx.host, ctx.port, ctx.user, ctx.password, ctx.db, NetTlsConfig(trustAll = true))
        }

    // ── Leaf 1: SCRAM-SHA-256-PLUS over TLS succeeds ─────────────────────────

    "connecting to PG with SCRAM-SHA-256-PLUS over TLS succeeds".tagged("kyo.OwnContainer") in {
        Scope.run {
            Async.timeout(60.seconds) {
                withPostgresTls { (host, port, user, password, db, tlsConfig) =>
                    AtomicRef.init("").flatMap { mechanismRef =>
                        PostgresConnection.connectWithCertHashOverride(
                            host,
                            port,
                            user,
                            db,
                            Present(password),
                            tls = Present(tlsConfig),
                            certHashOverride = Absent, // use real cert hash
                            mechanismCapture = Present(mechanismRef),
                            preparedStmtCacheSize = 64
                        ).flatMap { conn =>
                            Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                                // Verify TLS is in use by querying pg_stat_ssl.
                                // Note: raw bool (OID 16) in simple query protocol serialises as "t"/"f";
                                // ssl::text (OID 25) would give "true"/"false". Use the uncast column.
                                conn.simpleQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()").flatMap {
                                    sslRows =>
                                        assert(sslRows.nonEmpty, "pg_stat_ssl should return a row for current backend")
                                        val sslValue = new String(sslRows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                                        assert(sslValue == "t", s"Expected ssl=t (TLS in use), got: $sslValue")
                                        // Verify cert hash is Present (TLS cert captured, PLUS was selectable).
                                        conn.serverCertificateHash.flatMap { certHash =>
                                            assert(certHash.isDefined, "serverCertificateHash must be Present after TLS connect")
                                            // Verify the mechanism selected was SCRAM-SHA-256-PLUS.
                                            mechanismRef.get.flatMap { selectedMechanism =>
                                                assert(
                                                    selectedMechanism == "SCRAM-SHA-256-PLUS",
                                                    s"Expected SCRAM-SHA-256-PLUS selected, got: '$selectedMechanism'"
                                                )
                                                // Probe reusability: one more query to confirm the connection is still usable.
                                                conn.simpleQuery("SELECT 1").map { probeRows =>
                                                    assert(probeRows.size == 1, "Connection reuse probe failed after SCRAM-PLUS auth")
                                                }
                                            }
                                        }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 2: Plaintext connect falls back to SCRAM-SHA-256 ─────────────────

    "connecting plaintext to a PG that offers PLUS falls back to SCRAM-SHA-256".tagged("kyo.OwnContainer") in {
        Scope.run {
            Async.timeout(60.seconds) {
                // Use a plain non-TLS Postgres container. Without TLS, no cert hash, so non-PLUS.
                ContainerPredef.Postgres.initWith(ContainerPredef.Postgres.Config.default) { pg =>
                    pg.container.mappedPort(pg.config.port).flatMap { port =>
                        AtomicRef.init("").flatMap { mechanismRef =>
                            PostgresConnection.connectWithCertHashOverride(
                                pg.container.host,
                                port,
                                pg.username,
                                pg.database,
                                Present(pg.password),
                                tls = Absent,              // plaintext
                                certHashOverride = Absent, // use real cert hash (Absent for plaintext)
                                mechanismCapture = Present(mechanismRef),
                                preparedStmtCacheSize = 64
                            ).flatMap { conn =>
                                Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                                    conn.serverCertificateHash.flatMap { certHash =>
                                        assert(certHash.isEmpty, "serverCertificateHash must be Absent for plaintext connect")
                                        mechanismRef.get.flatMap { selectedMechanism =>
                                            assert(
                                                selectedMechanism == "SCRAM-SHA-256",
                                                s"Expected SCRAM-SHA-256 for plaintext connect, got: '$selectedMechanism'"
                                            )
                                            assert(
                                                !selectedMechanism.contains("PLUS"),
                                                s"Must NOT select SCRAM-SHA-256-PLUS on plaintext, got: '$selectedMechanism'"
                                            )
                                            // Probe reusability.
                                            conn.simpleQuery("SELECT 2").map { probeRows =>
                                                assert(probeRows.size == 1, "Connection reuse probe failed after non-PLUS auth")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 3: Wrong cert hash (MITM) is rejected with SQLSTATE 28000 ─────────

    "channel binding mismatch (e.g., MITM with different cert) is rejected by the server".tagged("kyo.OwnContainer") in {
        Scope.run {
            Async.timeout(60.seconds) {
                withPostgresTls { (host, port, user, password, db, tlsConfig) =>
                    // Inject a deliberately wrong cert hash: 32 bytes of 0xAA.
                    // The client will attempt SCRAM-PLUS with this fake hash, which does NOT match
                    // the actual channel binding the server computes from the TLS session.
                    // PG will reject the client-final-message because the c= attribute decodes to
                    // garbage (wrong hash), causing a SCRAM verification failure
                    // (SQLSTATE 28000 = invalid_authorization_specification).
                    val wrongHash: Span[Byte]                       = Span.fill(32)(0xaa.toByte)
                    val wrongHashOverride: Maybe[Maybe[Span[Byte]]] = Present(Present(wrongHash))

                    Abort.run[SqlException] {
                        PostgresConnection.connectWithCertHashOverride(
                            host,
                            port,
                            user,
                            db,
                            Present(password),
                            tls = Present(tlsConfig),
                            certHashOverride = wrongHashOverride,
                            mechanismCapture = Absent,
                            preparedStmtCacheSize = 64
                        ).flatMap { conn =>
                            Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                                conn.simpleQuery("SELECT 1")
                            }
                        }
                    }.map {
                        case Result.Failure(e: SqlConnectionAuthenticationFailedException) =>
                            // PG raises ERRCODE_INVALID_AUTHORIZATION_SPECIFICATION (28000) for SCRAM
                            // SASL response failures (including channel-binding mismatch), NOT
                            // ERRCODE_INVALID_PASSWORD (28P01). See PG src/backend/libpq/auth-scram.c.
                            assert(
                                e.sqlState == "28000",
                                s"Expected SQLSTATE 28000 (invalid_authorization_specification, PG raises this for SCRAM SASL failures) for MITM cert hash mismatch, got: ${e.sqlState}, ${e.serverMessage}"
                            )
                        case Result.Success(_) =>
                            fail(
                                "Expected authentication failure with wrong cert hash, but connection succeeded, SCRAM-PLUS channel binding check is broken!"
                            )
                        case Result.Failure(other) =>
                            fail(s"Expected SqlConnectionAuthenticationFailedException(28000) for wrong cert hash, got: $other")
                        case Result.Panic(t) =>
                            fail(s"Unexpected panic in leaf 3: ${t.getMessage}")
                    }
                }
            }
        }
    }

    // ── Leaf 4: Client with forced-Absent cert hash refuses PLUS ────────────────

    "client without serverCertificateHash refuses to advertise PLUS".tagged("kyo.OwnContainer") in {
        Scope.run {
            Async.timeout(60.seconds) {
                withPostgresTls { (host, port, user, password, db, tlsConfig) =>
                    // Force cert hash override to Absent. Even though the TLS connection has a real cert,
                    // the client code will see Absent and pick SCRAM-SHA-256 (not PLUS).
                    val forceAbsent: Maybe[Maybe[Span[Byte]]] = Present(Absent)

                    AtomicRef.init("").flatMap { mechanismRef =>
                        PostgresConnection.connectWithCertHashOverride(
                            host,
                            port,
                            user,
                            db,
                            Present(password),
                            tls = Present(tlsConfig),
                            certHashOverride = forceAbsent,
                            mechanismCapture = Present(mechanismRef),
                            preparedStmtCacheSize = 64
                        ).flatMap { conn =>
                            Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                                // TLS is active (cert is present on the real connection).
                                conn.serverCertificateHash.flatMap { realCertHash =>
                                    assert(realCertHash.isDefined, "Real TLS cert hash must be Present (sanity: we have TLS)")
                                    mechanismRef.get.flatMap { selectedMechanism =>
                                        // Despite having a real TLS cert, cert hash was overridden to Absent,
                                        // so the client must have selected SCRAM-SHA-256, not SCRAM-SHA-256-PLUS.
                                        assert(
                                            selectedMechanism == "SCRAM-SHA-256",
                                            s"Expected SCRAM-SHA-256 when cert hash forced Absent, got: '$selectedMechanism'"
                                        )
                                        assert(
                                            !selectedMechanism.contains("PLUS"),
                                            s"Must NOT select SCRAM-SHA-256-PLUS when cert hash is Absent, got: '$selectedMechanism'"
                                        )
                                        // Probe reusability.
                                        conn.simpleQuery("SELECT 4").map { probeRows =>
                                            assert(probeRows.size == 1, "Connection reuse probe failed for forced-non-PLUS auth")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 7: Client picks SCRAM-SHA-256 when cert hash absent; server accepts ──

    "server selects SCRAM-SHA-256 over PLUS even when PLUS is offered; client honors server choice".tagged("kyo.OwnContainer") in {
        Scope.run {
            Async.timeout(60.seconds) {
                // TLS PG offers both SCRAM-SHA-256 and SCRAM-SHA-256-PLUS.
                // We connect with TLS but override cert hash to Absent, client picks non-PLUS.
                // Server must accept non-PLUS even though PLUS is offered.
                withPostgresTls { (host, port, user, password, db, tlsConfig) =>
                    val forceAbsent: Maybe[Maybe[Span[Byte]]] = Present(Absent)

                    AtomicRef.init("").flatMap { mechanismRef =>
                        PostgresConnection.connectWithCertHashOverride(
                            host,
                            port,
                            user,
                            db,
                            Present(password),
                            tls = Present(tlsConfig),
                            certHashOverride = forceAbsent,
                            mechanismCapture = Present(mechanismRef),
                            preparedStmtCacheSize = 64
                        ).flatMap { conn =>
                            Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                                conn.simpleQuery("SELECT 42").flatMap { rows =>
                                    assert(rows.size == 1, "SELECT 42 must return one row")
                                    val v = new String(rows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                                    assert(v == "42", s"Expected '42', got: $v")
                                    mechanismRef.get.flatMap { selectedMechanism =>
                                        // Client chose non-PLUS even though TLS was available (cert hash forced Absent).
                                        // Server accepted SCRAM-SHA-256 (it always accepts non-PLUS when client presents it).
                                        assert(
                                            selectedMechanism == "SCRAM-SHA-256",
                                            s"Expected SCRAM-SHA-256 when cert hash forced Absent, got: '$selectedMechanism'"
                                        )
                                        assert(
                                            !selectedMechanism.contains("PLUS"),
                                            s"Must NOT select PLUS when cert hash is Absent, got: '$selectedMechanism'"
                                        )
                                        // Probe reusability.
                                        conn.simpleQuery("SELECT 7").map { probeRows =>
                                            assert(probeRows.size == 1, "Connection reuse probe failed for leaf 7")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 8: Wrong password under SCRAM-PLUS → sqlState=28P01; no state leak ─

    "wrong password under SCRAM-PLUS produces SqlConnectionAuthenticationFailedException with sqlState=28P01 and does NOT leak channel-binding state".tagged(
        "kyo.OwnContainer"
    ) in {
        Scope.run {
            Async.timeout(60.seconds) {
                withPostgresTls { (host, port, user, password, db, tlsConfig) =>
                    // Attempt 1: TLS + correct cert hash + WRONG password → must fail with 28P01.
                    Abort.run[SqlException] {
                        PostgresConnection.connectWithCertHashOverride(
                            host,
                            port,
                            user,
                            db,
                            Present("DEFINITELY_WRONG_PASSWORD"),
                            tls = Present(tlsConfig),
                            certHashOverride = Absent, // use real cert hash → PLUS selected
                            mechanismCapture = Absent,
                            preparedStmtCacheSize = 64
                        ).flatMap { conn =>
                            Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                                conn.simpleQuery("SELECT 1")
                            }
                        }
                    }.flatMap {
                        case Result.Failure(e: SqlConnectionAuthenticationFailedException) =>
                            assert(
                                e.sqlState == "28P01",
                                s"Expected SQLSTATE 28P01 (invalid_password) for wrong SCRAM-PLUS password, got: ${e.sqlState}"
                            )
                            // Attempt 2: TLS + correct cert hash + CORRECT password → must succeed.
                            // This proves the SCRAM-PLUS state from attempt 1 did NOT leak into this connection.
                            PostgresConnection.connectWithCertHashOverride(
                                host,
                                port,
                                user,
                                db,
                                Present(password),
                                tls = Present(tlsConfig),
                                certHashOverride = Absent, // use real cert hash → PLUS selected
                                mechanismCapture = Absent,
                                preparedStmtCacheSize = 64
                            ).flatMap { conn =>
                                Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                                    conn.simpleQuery("SELECT 99").map { rows =>
                                        assert(rows.size == 1, "SELECT 99 must return one row after failed SCRAM-PLUS attempt")
                                        val v = new String(rows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                                        assert(
                                            v == "99",
                                            s"Follow-up SCRAM-PLUS connection failed, state leaked from failed auth attempt: $v"
                                        )
                                    }
                                }
                            }
                        case Result.Success(_) =>
                            fail("Expected authentication failure with wrong password under SCRAM-PLUS, but connection succeeded!")
                        case Result.Failure(other) =>
                            fail(s"Expected SqlConnectionAuthenticationFailedException(28P01) for wrong SCRAM-PLUS password, got: $other")
                        case Result.Panic(t) =>
                            fail(s"Unexpected panic in leaf 8: ${t.getMessage}")
                    }
                }
            }
        }
    }

end ScramPlusIntegrationTest

object ScramPlusIntegrationTest:

    /** Connection details for the shared TLS+SCRAM Postgres container. */
    final case class TlsCtx(host: String, port: Int, user: String, password: String, db: String)

    private type TlsPromise = Promise[TlsCtx, Abort[ContainerException]]

    // Unsafe boundary: module-load AtomicRef init (no live Frame yet).
    private val tlsRef: AtomicRef[Maybe[TlsPromise]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Maybe[TlsPromise]](Maybe.empty).safe

    private val username = "scramplus_user"
    private val password = "scramplus_pass"
    private val database = "scramplus_db"

    /** Acquires the shared SCRAM-PLUS TLS Postgres container, lazily starting it on first call. */
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
                            Fiber.initUnscoped(initContainer).flatMap { fiber =>
                                fiber.getResult.flatMap {
                                    case Result.Success(ctx) =>
                                        p.completeDiscard(Result.succeed(ctx)).andThen(f(ctx))
                                    case Result.Failure(e: ContainerException) =>
                                        // Reset slot first so the next caller retries instead of seeing a poisoned Promise.
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

    /** Starts the TLS-enabled Postgres container with SCRAM-SHA-256 auth.
      *
      * Steps:
      *   1. Generate a self-signed TLS cert (CN=localhost) via openssl.
      *   2. Start a postgres:16-alpine container with the cert directory bind-mounted at /etc/ssl-pg.
      *   3. Wait for awaitHealthy (the health check fires against the fully-started PG via psql).
      *   4. Copy certs into /tmp and chmod/chown them for the postgres user.
      *   5. ALTER SYSTEM SET ssl/ssl_cert_file/ssl_key_file (separate psql -c per ALTER, they cannot share a transaction block).
      *   6. SELECT pg_reload_conf(), sighup-context settings activate without restart.
      *   7. Probe `awaitSslReady` until PG responds 'S' to SSLRequest.
      *
      * The container is left running for the JVM lifetime; orphan reaping is handled by the sbt setup task via the `kyo-sql-singleton`
      * label. The temp dir for cert files is cleaned by `Scope.ensure` registered inside the singleton's lifetime; it survives the JVM in
      * the happy path and is removed only if init throws.
      */
    private def initContainer(using Frame): TlsCtx < (Async & Abort[ContainerException]) =
        Scope.run {
            Abort.run[FileFsException](Path.tempDir(prefix = "kyo-sql-scram-plus-")).flatMap {
                case Result.Failure(e) =>
                    Abort.fail(ContainerBackendException(s"temp dir creation failed: ${e.getMessage}"))
                case Result.Panic(t) =>
                    Abort.fail(ContainerBackendException(s"temp dir creation panic: ${t.getMessage}"))
                case Result.Success(tempDirPath) =>
                    val tempDir = tempDirPath.toString
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
                            Abort.fail(ContainerBackendException(s"openssl cert generation failed: ${e.getMessage}"))
                        case Result.Panic(t) =>
                            Abort.fail(ContainerBackendException(s"openssl cert generation panic: ${t.getMessage}"))
                        case Result.Success(_) =>
                            startContainer(tempDirPath)
                    }
            }
        }
    end initContainer

    private def startContainer(tempDirPath: Path)(using Frame): TlsCtx < (Async & Abort[ContainerException] & Scope) =
        // Start a plain postgres container with the cert directory bind-mounted.
        // SSL is enabled AFTER startup via ALTER SYSTEM SET + pg_reload_conf().
        // This avoids the custom-entrypoint approach (postgres:16-alpine's
        // docker-entrypoint.sh entrypoint is left untouched).
        val containerConfig = Container.Config.default
            .copy(image = ContainerImage("postgres:16-alpine"))
            .envAll(Dict.from(Map(
                "POSTGRES_USER"     -> username,
                "POSTGRES_PASSWORD" -> password,
                "POSTGRES_DB"       -> database
            )))
            .port(5432, 0)
            .bind(tempDirPath, Path("/etc/ssl-pg"), readOnly = true)
            .label("kyo-sql-singleton", "postgres-scram-plus")
            .healthCheck(Container.HealthCheck.exec(
                Command("psql", "-U", username, "-d", database, "-c", "SELECT 1"),
                Absent,
                Schedule.fixed(1.second).take(60)
            ))

        Container.initUnscoped(containerConfig).flatMap { container =>
            container.awaitHealthy.andThen {
                // Container is healthy: PG is fully up.
                // Step 1: Copy certs from the bind mount into /tmp/ and fix permissions.
                container.exec(
                    "sh",
                    "-c",
                    "cp /etc/ssl-pg/server.crt /tmp/server.crt && " +
                        "cp /etc/ssl-pg/server.key /tmp/server.key && " +
                        "chmod 600 /tmp/server.key && " +
                        "chown postgres:postgres /tmp/server.crt /tmp/server.key"
                ).andThen {
                    // Step 2: Enable SSL via ALTER SYSTEM SET. Parameters ssl,
                    // ssl_cert_file, and ssl_key_file all have context='sighup',
                    // so pg_reload_conf() picks them up without a restart.
                    // Each ALTER SYSTEM SET must be a separate psql -c call because
                    // ALTER SYSTEM cannot run inside a transaction block (which
                    // psql -c "stmt1; stmt2" creates implicitly).
                    container.exec("psql", "-U", username, "-d", database, "-c", "ALTER SYSTEM SET ssl = on").andThen {
                        container.exec(
                            "psql",
                            "-U",
                            username,
                            "-d",
                            database,
                            "-c",
                            "ALTER SYSTEM SET ssl_cert_file = '/tmp/server.crt'"
                        ).andThen {
                            container.exec(
                                "psql",
                                "-U",
                                username,
                                "-d",
                                database,
                                "-c",
                                "ALTER SYSTEM SET ssl_key_file = '/tmp/server.key'"
                            ).andThen {
                                // Step 3: Reload config so SSL settings take effect.
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
                                            Log.warn(
                                                s"[kyo-sql] ScramPlusTest: pg_reload_conf failed: ${reloadResult.stderr}"
                                            )
                                        else Kyo.unit
                                    reloadCheck.andThen {
                                        container.mappedPort(5432).flatMap { port =>
                                            // Belt-and-braces: poll until PG responds 'S' to SSLRequest.
                                            awaitSslReady(container.host, port).map { _ =>
                                                TlsCtx(container.host, port, username, password, database)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    end startContainer

    /** Polls until the PostgreSQL server at host:port responds with 'S' (SSL supported) to an SSLRequest.
      *
      * Docker Desktop on macOS can set up port-mapping metadata AND have TCP accessible before PostgreSQL has fully initialized its SSL
      * context. Without this specific SSL probe, kyo-sql connects right after TCP becomes available and gets 'N' (SSL not yet ready).
      *
      * Sends the 8-byte Postgres SSLRequest packet (Int32(8) || Int32(80877103)) and checks for a 1-byte 'S' response. Retries up to 30
      * times at 500ms intervals (15 seconds total).
      */
    private def awaitSslReady(host: String, port: Int)(using Frame): Unit < Async =
        // SSLRequest bytes: length=8 (big-endian Int32) || magic=80877103 (big-endian Int32)
        val sslRequest: Span[Byte] =
            Span.from(Array[Byte](0x00, 0x00, 0x00, 0x08, 0x04, 0xd2.toByte, 0x16, 0x2f))

        // Uses kyo.net.NetPlatform.transport so the probe links on every platform; running it
        // requires a real PG server (i.e., Docker) which is JVM-only in practice, but the
        // linker cannot tell that from sources alone.
        def tryProbe(): Boolean < Async =
            Abort.run[Throwable] {
                Async.timeout(2.seconds) {
                    Scope.run {
                        Sync.Unsafe.defer(kyo.net.NetPlatform.transport.connect(host, port).safe)
                            .flatMap(_.use(identity))
                            .flatMap { conn =>
                                conn.outbound.safe.put(sslRequest).andThen {
                                    conn.inbound.safe.take.map { firstChunk =>
                                        firstChunk.head.exists(_ == 'S'.toByte)
                                    }
                                }
                            }
                    }
                }
            }.map {
                case Result.Success(ok) => ok
                case _                  => false
            }
        end tryProbe

        def attempt(remaining: Int): Unit < Async =
            tryProbe().flatMap { ok =>
                if ok then Kyo.unit
                else if remaining <= 0 then
                    Log.warn(s"[kyo-sql] awaitSslReady: SSL not ready at $host:$port after 30 attempts")
                else
                    Async.sleep(500.millis).andThen(attempt(remaining - 1))
            }
        end attempt
        attempt(30)
    end awaitSslReady

end ScramPlusIntegrationTest
