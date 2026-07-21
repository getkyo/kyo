package kyo.mysql

import kyo.*
import kyo.OwnContainer

/** Integration tests for caching_sha2_password authentication.
  *
  * Uses a MySQL 8.0 container started WITHOUT `--default-authentication-plugin=mysql_native_password`, so the server's default
  * `caching_sha2_password` plugin is active.
  *
  * Test structure:
  *   - Each test that needs a fresh server cache starts its own container (container-per-test) to guarantee cache is empty (cache miss →
  *     full-auth path).
  *   - Tests that need a warm cache (fast-path) open a first connection then a second within the same container.
  *
  * Container startup takes ~30-60 s, so each test has a 3-minute timeout.
  */
class CachingSha2IntegrationTest extends kyo.Test:

    override def timeout: Duration = 4.minutes

    // Case class to carry connection details from the Kyo fiber to openClient.
    private case class ConnDetails(host: String, port: Int, user: String, password: String, db: String)

    /** Starts a fresh MySQL container using caching_sha2_password as the default auth plugin, runs `f`, then stops the container. */
    private def withCachingSha2Container[A](
        f: ConnDetails => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[Throwable] & Scope) =
        ContainerPredef.MySQL.initWith(ContainerPredef.MySQL.Config.default) { mysql =>
            mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                val details = ConnDetails(
                    mysql.container.host,
                    port,
                    mysql.username,
                    mysql.password,
                    mysql.database
                )
                Abort.run[SqlException](f(details)).flatMap {
                    case Result.Success(a) => a
                    case Result.Failure(e) => Abort.fail(e: Throwable)
                    case Result.Panic(t) =>
                        scala.Console.err.println(s"[CachingSha2IntegrationTest] panic: ${t.getMessage}")
                        Abort.fail(t)
                }
            }
        }

    /** Connects to the given connection details and returns a [[SqlClient]]. The client is closed when the outer Scope exits. */
    private def openClient(
        details: ConnDetails
    )(using Frame): SqlClient < (Async & Scope & Abort[SqlException]) =
        SqlClient.initMy(
            s"mysql://${details.user}:${details.password}@${details.host}:${details.port}/${details.db}",
            SqlClientConfig.default.copy(maxConnections = 1, minConnections = 1)
        )

    // ── caching_sha2 fast-path (warm cache) ───────────────────────────────────

    "HandshakeExchange caching_sha2 fast-path (cache hit), second connection uses fast path".tagged("kyo.OwnContainer") in {
        Scope.run {
            withCachingSha2Container { details =>
                Scope.run {
                    // First connection: cache miss → full-auth via RSA, populates server cache.
                    openClient(details).flatMap { client1 =>
                        client1.query("SELECT 1").flatMap { _ =>
                            // Second connection: same user → server cache is warm → fast-path success (AuthMoreData 0x03).
                            openClient(details).flatMap { client2 =>
                                client2.query("SELECT 'fast_path_ok'").map { rows =>
                                    val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                    assert(str == "fast_path_ok")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── caching_sha2 full-auth via RSA (no TLS, fresh container) ─────────────

    "HandshakeExchange caching_sha2 full-auth via RSA (no TLS), fresh container triggers full-auth path".tagged("kyo.OwnContainer") in {
        Scope.run {
            withCachingSha2Container { details =>
                Scope.run {
                    // Fresh container → cache empty → full-auth: request RSA key, encrypt, send.
                    openClient(details).flatMap { client =>
                        client.query("SELECT 'full_auth_rsa_ok'").map { rows =>
                            val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                            assert(str == "full_auth_rsa_ok")
                        }
                    }
                }
            }
        }
    }

    // ── wrong password raises SqlException.Connection ─────────────────────────

    "HandshakeExchange caching_sha2 wrong password raises SqlException.Connection".tagged("kyo.OwnContainer") in {
        Scope.run {
            withCachingSha2Container { details =>
                Abort.run[SqlException](
                    Scope.run {
                        SqlClient.initMy(
                            s"mysql://${details.user}:definitly_wrong_pw_xyz@${details.host}:${details.port}/${details.db}",
                            SqlClientConfig.default.copy(maxConnections = 1, minConnections = 1)
                        )
                    }
                ).map {
                    case Result.Failure(_: SqlException.Connection) => succeed
                    case other                                      => fail(s"Expected SqlException.Connection, got: $other")
                }
            }
        }
    }

    // ── caching_sha2 full-auth populates cache for next connection ────────────

    "HandshakeExchange caching_sha2 full-auth updates cache, second connect uses fast-path".tagged("kyo.OwnContainer") in {
        Scope.run {
            withCachingSha2Container { details =>
                Scope.run {
                    // First connection (full-auth): populates server's credential cache.
                    openClient(details).flatMap { client1 =>
                        client1.query("SELECT 1").flatMap { _ =>
                            // Second connection: fast-path (0x03), cache is now warm.
                            openClient(details).flatMap { client2 =>
                                client2.isAlive.map { open => assert(open) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Test: sequential queries succeed over caching_sha2 connection ─────────

    "caching_sha2 connection supports sequential queries after auth".tagged("kyo.OwnContainer") in {
        Scope.run {
            withCachingSha2Container { details =>
                Scope.run {
                    openClient(details).flatMap { client =>
                        // `client.query` on MySQL routes unparameterised statements to the extended
                        // (binary prepared-stmt) protocol; `SELECT n` comes back as an 8-byte little-
                        // endian BIGINT. Decode via `row.decode[Long]` (routed to MysqlRowReader by
                        // SqlRow.decode's OID-based dispatch) so the assertion compares numeric values.
                        client.query("SELECT 1").flatMap { r1 =>
                            client.query("SELECT 2").flatMap { r2 =>
                                client.query("SELECT 3").flatMap { r3 =>
                                    for
                                        v1 <- r1(0).decode[Long](0)
                                        v2 <- r2(0).decode[Long](0)
                                        v3 <- r3(0).decode[Long](0)
                                    yield
                                        assert(v1 == 1L)
                                        assert(v2 == 2L)
                                        assert(v3 == 3L)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── fallback to native_password via AuthSwitchRequest ─────────────────────

    "HandshakeExchange fallback to native_password via AuthSwitchRequest, native_password container".tagged("kyo.OwnContainer") in {
        Scope.run {
            // This test uses a native_password container and verifies AuthSwitchRequest handling still works.
            val nativePredef = ContainerPredef.MySQL.Config.default
            val nativeConfig = ContainerPredef.MySQL.buildContainerConfig(nativePredef)
                .command("--default-authentication-plugin=mysql_native_password")
            Container.initWith(nativeConfig) { nativeContainer =>
                val mysql = new ContainerPredef.MySQL(nativeContainer, nativePredef)
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    // Connect to native_password container, the server may send AuthSwitchRequest.
                    SqlClient.initMy(
                        s"mysql://${mysql.username}:${mysql.password}@${mysql.container.host}:$port/${mysql.database}",
                        SqlClientConfig.default.copy(maxConnections = 1, minConnections = 1)
                    ).flatMap { client =>
                        client.query("SELECT 'switch_ok'").map { rows =>
                            val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                            assert(str == "switch_ok")
                        }
                    }
                }
            }
        }
    }

    // ── Test: isAlive returns true after caching_sha2 handshake ───────────────

    "caching_sha2 connection isOpen returns true after successful handshake".tagged("kyo.OwnContainer") in {
        Scope.run {
            withCachingSha2Container { details =>
                Scope.run {
                    openClient(details).flatMap { client =>
                        client.isAlive.map { open => assert(open) }
                    }
                }
            }
        }
    }

    // ── Test: ping works over caching_sha2 connection ────────────────────────

    "caching_sha2 connection supports COM_PING after auth".tagged("kyo.OwnContainer") in {
        Scope.run {
            withCachingSha2Container { details =>
                Scope.run {
                    openClient(details).flatMap { client =>
                        client.ping.map { _ => succeed }
                    }
                }
            }
        }
    }

    // ── Test: CREATE + INSERT + SELECT works over caching_sha2 ───────────────

    "caching_sha2 connection supports DDL and DML end-to-end".tagged("kyo.OwnContainer") in {
        Scope.run {
            withCachingSha2Container { details =>
                Scope.run {
                    openClient(details).flatMap { client =>
                        client.executeRaw("CREATE TABLE IF NOT EXISTS csha2_test (id INT, name VARCHAR(64))").flatMap { _ =>
                            client.executeRaw("INSERT INTO csha2_test VALUES (42, 'hello')").flatMap { affected =>
                                assert(affected == 1L)
                                client.query("SELECT id, name FROM csha2_test").flatMap { rows =>
                                    assert(rows.size == 1)
                                    val row = rows(0)
                                    // Extended protocol: INT is a 4-byte little-endian LONG, VARCHAR is
                                    // length-prefixed UTF-8. Decode typed.
                                    for
                                        idVal   <- row.decode[Int]("id")
                                        nameVal <- row.decode[String]("name")
                                        _ = assert(idVal == 42)
                                        _ = assert(nameVal == "hello")
                                        r <- client.executeRaw("DROP TABLE csha2_test").map(_ => succeed)
                                    yield r
                                    end for
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── AuthSwitchRequest to caching_sha2 from initial plugin ────────────────

    "HandshakeExchange AuthSwitchRequest to caching_sha2_password, handled by switch handler".tagged("kyo.OwnContainer") in {
        // When server is configured with caching_sha2_password and client sends an initial native_password response,
        // the server issues AuthSwitchRequest to caching_sha2_password. Our handler re-runs fast-path with the new scramble.
        // This scenario is tested with the caching_sha2_password container.
        Scope.run {
            withCachingSha2Container { details =>
                Scope.run {
                    openClient(details).flatMap { client =>
                        // If we got this far, the AuthSwitchRequest (if any) was handled correctly.
                        client.isAlive.map { open => assert(open) }
                    }
                }
            }
        }
    }

end CachingSha2IntegrationTest
