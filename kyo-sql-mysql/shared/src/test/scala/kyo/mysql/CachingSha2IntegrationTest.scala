package kyo.mysql

import kyo.*
import kyo.OwnContainer
import kyo.internal.TestContainers
import kyo.internal.mysql.MysqlConnection
import kyo.internal.mysql.exchange.MysqlAuthPath

/** Integration tests for caching_sha2_password authentication.
  *
  * Each leaf starts its own MySQL 8.0 container, by default without `--default-authentication-plugin=mysql_native_password`, so the
  * server's default `caching_sha2_password` plugin is active.
  *
  * A fresh container does NOT mean a cold credential cache: the container's readiness probe logs in as `test` over TCP, which leaves that
  * account's cache entry warm. A leaf that needs a cache miss therefore authenticates as an account created for it, which has never logged
  * in. The handshake leaves read [[MysqlConnection.authPath]] to assert the path they are named for, since the server records none.
  */
class CachingSha2IntegrationTest extends SqlContainerTest:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 4.minutes

    // Case class to carry connection details from the Kyo fiber to openClient.
    private case class ConnDetails(host: String, port: Int, user: String, password: String, db: String, rootPassword: String)

    private val fullAuth = MysqlAuthPath(Absent, Present(MysqlAuthPath.CachingSha2.FullAuth))
    private val fastPath = MysqlAuthPath(Absent, Present(MysqlAuthPath.CachingSha2.FastPath))

    /** Starts a fresh MySQL container with `serverArgs` appended to the predef's, runs `f`, then stops the container. */
    private def withServer[A](serverArgs: String*)(
        f: ConnDetails => A < (Async & Abort[SqlException] & Scope)
    )(using Frame): A < (Async & Abort[Throwable] & Scope) =
        // Through `TestContainers` rather than `ContainerPredef.MySQL.initWith` directly, so the
        // container carries the `kyo-test-container` and `kyo-test-owner-pid` labels and a killed test
        // process leaves something the reaper can find.
        TestContainers.initScopedMysql(ContainerPredef.MySQL.Config.default.appendServerArgs(serverArgs*), "mysql-caching-sha2").map {
            mysql =>
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    val details = ConnDetails(
                        mysql.container.host,
                        port,
                        mysql.username,
                        mysql.password,
                        mysql.database,
                        mysql.config.rootPassword
                    )
                    Abort.run[SqlException](f(details)).flatMap {
                        case Result.Success(a) => a
                        case Result.Failure(e) => Abort.fail(e: Throwable)
                        case Result.Panic(t)   =>
                            scala.Console.err.println(s"[CachingSha2IntegrationTest] panic: ${t.getMessage}")
                            Abort.fail(t)
                    }
                }
        }

    /** Starts a fresh MySQL container using caching_sha2_password as the default auth plugin, runs `f`, then stops the container. */
    private def withCachingSha2Container[A](
        f: ConnDetails => A < (Async & Abort[SqlException] & Scope)
    )(using Frame): A < (Async & Abort[Throwable] & Scope) =
        withServer()(f)

    /** An account authenticating with `plugin` that has never logged in, so the server holds no caching_sha2 cache entry for it. */
    private def newAccount(details: ConnDetails, plugin: String)(using Frame): ConnDetails < (Async & Abort[SqlException]) =
        Scope.run {
            connect(details.copy(user = "root", password = details.rootPassword)).flatMap { root =>
                Random.nextLong.map(v => s"u_${(v & Long.MaxValue).toHexString}").flatMap { user =>
                    val password = s"pw_$user"
                    root.simpleExecute(s"CREATE USER '$user'@'%' IDENTIFIED WITH $plugin BY '$password'")
                        .andThen(root.simpleExecute(s"GRANT ALL ON `${details.db}`.* TO '$user'@'%'"))
                        .andThen(details.copy(user = user, password = password))
                }
            }
        }

    /** Opens one plaintext connection, closed when the enclosing Scope exits. */
    private def connect(details: ConnDetails)(using Frame): MysqlConnection < (Async & Abort[SqlException] & Scope) =
        MysqlConnection.connect(
            details.host,
            details.port,
            details.user,
            Present(details.password),
            Present(details.db),
            Absent,
            64,
            Duration.Infinity
        ).flatMap(conn => Scope.ensure(Abort.run(conn.quit()).unit).andThen(conn))

    private def text(rows: Chunk[kyo.internal.mysql.MysqlRow]): String =
        new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)

    /** Connects to the given connection details and returns a [[SqlClient]]. The client is closed when the outer Scope exits. */
    private def openClient(
        details: ConnDetails
    )(using Frame): SqlClient < (Async & Scope & Abort[SqlException]) =
        MysqlClient.init(
            s"mysql://${details.user}:${details.password}@${details.host}:${details.port}/${details.db}",
            SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
        )

    // ── caching_sha2 fast-path (warm cache) ───────────────────────────────────

    "HandshakeExchange caching_sha2 fast-path (cache hit), second connection uses fast path".tagged(OwnContainer.name) in {
        Scope.run {
            withCachingSha2Container { details =>
                newAccount(details, "caching_sha2_password").flatMap { account =>
                    // First connection: cache miss → full-auth via RSA, populates server cache.
                    connect(account).flatMap { first =>
                        // Second connection: same user → server cache is warm → fast-path success (AuthMoreData 0x03).
                        connect(account).flatMap { second =>
                            second.simpleQuery("SELECT 'fast_path_ok'").map { rows =>
                                assert(first.authPath == fullAuth, s"the first connection must miss the cache, took ${first.authPath}")
                                assert(second.authPath == fastPath, s"the second connection must hit the cache, took ${second.authPath}")
                                assert(text(rows) == "fast_path_ok")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── caching_sha2 full-auth via RSA (no TLS) ───────────────────────────────

    "HandshakeExchange caching_sha2 full-auth via RSA (no TLS), a never-used account triggers full-auth path".tagged(OwnContainer.name) in {
        Scope.run {
            withCachingSha2Container { details =>
                newAccount(details, "caching_sha2_password").flatMap { account =>
                    // Cache empty for this account → full-auth: request RSA key, encrypt, send.
                    connect(account).flatMap { conn =>
                        conn.simpleQuery("SELECT 'full_auth_rsa_ok'").map { rows =>
                            assert(conn.authPath == fullAuth, s"a never-used account must take full auth, took ${conn.authPath}")
                            assert(text(rows) == "full_auth_rsa_ok")
                        }
                    }
                }
            }
        }
    }

    // ── wrong password raises SqlConnectionException ─────────────────────────

    "HandshakeExchange caching_sha2 wrong password raises SqlConnectionException".tagged(OwnContainer.name) in {
        Scope.run {
            withCachingSha2Container { details =>
                Abort.run[SqlException](
                    Scope.run {
                        MysqlClient.init(
                            s"mysql://${details.user}:definitly_wrong_pw_xyz@${details.host}:${details.port}/${details.db}",
                            SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                        )
                    }
                ).map {
                    case Result.Failure(_: SqlConnectionException) => succeed
                    case other                                     => fail(s"Expected SqlConnectionException, got: $other")
                }
            }
        }
    }

    // ── caching_sha2 full-auth populates cache for next connection ────────────

    "HandshakeExchange caching_sha2 full-auth updates cache, second connect uses fast-path".tagged(OwnContainer.name) in {
        Scope.run {
            withCachingSha2Container { details =>
                newAccount(details, "caching_sha2_password").flatMap { account =>
                    // First connection (full-auth): populates server's credential cache.
                    connect(account).flatMap { first =>
                        // Second connection: fast-path (0x03), cache is now warm.
                        connect(account).flatMap { second =>
                            second.ping().map { _ =>
                                assert(first.authPath == fullAuth, s"the first connection must take full auth, took ${first.authPath}")
                                assert(second.authPath == fastPath, s"full auth must leave the cache warm, took ${second.authPath}")
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Test: sequential queries succeed over caching_sha2 connection ─────────

    "caching_sha2 connection supports sequential queries after auth".tagged(OwnContainer.name) in {
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

    "HandshakeExchange fallback to native_password via AuthSwitchRequest, native_password account".tagged(OwnContainer.name) in {
        // The server names its default plugin, caching_sha2_password, in HandshakeV10; the account's plugin differs, so the server
        // answers the client's caching_sha2 response with an AuthSwitchRequest to mysql_native_password.
        Scope.run {
            withServer() { details =>
                newAccount(details, "mysql_native_password").flatMap { account =>
                    connect(account).flatMap { conn =>
                        conn.simpleQuery("SELECT 'switch_ok'").map { rows =>
                            assert(
                                conn.authPath == MysqlAuthPath(Present("mysql_native_password"), Absent),
                                s"the server must switch the exchange to mysql_native_password, took ${conn.authPath}"
                            )
                            assert(text(rows) == "switch_ok")
                        }
                    }
                }
            }
        }
    }

    // ── Test: isAlive returns true after caching_sha2 handshake ───────────────

    "caching_sha2 connection isOpen returns true after successful handshake".tagged(OwnContainer.name) in {
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

    "caching_sha2 connection supports COM_PING after auth".tagged(OwnContainer.name) in {
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

    "caching_sha2 connection supports DDL and DML end-to-end".tagged(OwnContainer.name) in {
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

    "HandshakeExchange AuthSwitchRequest to caching_sha2_password, handled by switch handler".tagged(OwnContainer.name) in {
        // A server whose default plugin is mysql_native_password names it in HandshakeV10, so the client's first response is a
        // native_password scramble; the account authenticates with caching_sha2_password, so the server switches to it. The handler
        // re-runs the caching_sha2 scramble with the new nonce, and the never-used account then takes full auth.
        Scope.run {
            withServer("--default-authentication-plugin=mysql_native_password") { details =>
                newAccount(details, "caching_sha2_password").flatMap { account =>
                    connect(account).flatMap { conn =>
                        conn.ping().map { _ =>
                            assert(
                                conn.authPath ==
                                    MysqlAuthPath(Present("caching_sha2_password"), Present(MysqlAuthPath.CachingSha2.FullAuth)),
                                s"the server must switch the exchange to caching_sha2_password, took ${conn.authPath}"
                            )
                        }
                    }
                }
            }
        }
    }

end CachingSha2IntegrationTest
