package kyo.mysql

import kyo.*
import kyo.OwnContainer
import kyo.net.NetTlsConfig

/** Integration test for MySQL sha256_password auth plugin.
  *
  * sha256_password is the legacy RSA-auth plugin used by some MySQL 5.7 / 8.0 installations. Unlike caching_sha2_password there is no
  * fast-path cache, every connection either encrypts the password with the server's RSA public key (non-TLS) or sends cleartext over TLS.
  *
  * Container strategy, and which handshake path each leaf actually takes:
  *   - Root connects and runs `ALTER USER 'test'@'%' IDENTIFIED WITH sha256_password BY 'test'` to switch the test user to sha256_password.
  *   - Leaves 1 and 2 leave the server's own `default_authentication_plugin` at `caching_sha2_password`, so the server still names that plugin
  *     in its `HandshakeV10` and reaches `sha256_password` through an `AuthSwitchRequest`. They therefore cover `performSha256Auth`, not the
  *     initial-response branch: leaf 1 the plaintext RSA-OAEP round via [[kyo.internal.auth.RsaOaep]], leaf 2 the TLS path where the client
  *     sends the cleartext NUL-terminated password and skips RSA.
  *   - Leaf 3 starts the server with `--default-authentication-plugin=sha256_password`, which is the only configuration in which the client's
  *     `HandshakeResponse41` carries a `sha256_password` initial auth response at all. Without it that branch is unreachable, which is how it
  *     came to send the wrong bytes with every leaf here passing.
  */
class Sha256PasswordIntegrationTest extends SqlContainerTest:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 6.minutes

    // ─── Container + user-switch helper ─────────────────────────────────────

    /** Starts a fresh MySQL container, switches the "test" user to sha256_password via root, and runs `f` with connection details.
      *
      * `serverArgs` reaches `mysqld`'s command line, which is the only way to move the server's default authentication plugin: the plugin named
      * in `HandshakeV10` is the server's default, not the account's, so an `ALTER USER` alone never changes which branch the client's initial
      * auth response comes from.
      */
    private def withSha256User[A, S](
        tls: Maybe[NetTlsConfig],
        serverArgs: Chunk[String] = Chunk.empty
    )(
        f: (String, Int, String, String, String) => A < (S & Async & Abort[SqlException] & Scope)
    )(using Frame): A < (S & Async & Abort[Throwable] & Scope) =
        ContainerPredef.MySQL.initWith(ContainerPredef.MySQL.Config.default.serverArgs(serverArgs)) { mysql =>
            mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                val host = mysql.container.host
                val user = mysql.username
                val pass = mysql.password
                val db   = mysql.database
                // Root connection: alter user plugin to sha256_password.
                val rootSetup =
                    Scope.run {
                        MysqlClient.init(
                            s"mysql://root:${mysql.config.rootPassword}@$host:$port/mysql",
                            SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                        ).flatMap { root =>
                            val alterSql = s"ALTER USER '$user'@'%' IDENTIFIED WITH sha256_password BY '$pass'"
                            Abort.run[SqlException](root.executeRaw(alterSql).andThen(root.executeRaw("FLUSH PRIVILEGES"))).flatMap {
                                case Result.Success(_)   => Kyo.unit
                                case Result.Failure(err) => Abort.fail(err: Throwable)
                                case Result.Panic(t)     => Abort.fail(t)
                            }
                        }
                    }
                rootSetup.flatMap { _ =>
                    // Now connect as the altered user.
                    Abort.run[SqlException](f(host, port, user, pass, db)).flatMap {
                        case Result.Success(a) => a
                        case Result.Failure(e) => Abort.fail(e: Throwable)
                        case Result.Panic(t)   => Abort.fail(t)
                    }
                }
            }
        }
    end withSha256User

    // ─── Leaf 1: non-TLS → RSA-OAEP path ────────────────────────────────────

    "MySQL user configured with sha256_password authenticates via RSA-OAEP (non-TLS)".tagged("kyo.OwnContainer") in {
        Scope.run {
            withSha256User(Maybe.Absent) { (host, port, user, pass, db) =>
                // Connect without TLS: HandshakeExchange sends empty auth → receives PEM key → XOR+RSA-OAEP encrypts → server decrypts.
                MysqlClient.init(
                    s"mysql://$user:$pass@$host:$port/$db",
                    SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                ).flatMap { client =>
                    client.query("SELECT 'sha256_rsa_ok'").map { rows =>
                        val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                        assert(str == "sha256_rsa_ok", s"Expected 'sha256_rsa_ok', got '$str'")
                    }
                }
            }
        }
    }

    // ─── Leaf 2: TLS path skips RSA encryption ───────────────────────────────

    "TLS path skips RSA encryption for sha256_password (cleartext NUL-terminated)".tagged("kyo.OwnContainer") in {
        Scope.run {
            withSha256User(Maybe.Present(NetTlsConfig(trustAll = true))) { (host, port, user, pass, db) =>
                // Connect with TLS (trustAll): sends cleartext NUL-terminated password in HandshakeResponse41, no RSA involved.
                MysqlClient.init(
                    s"mysql://$user:$pass@$host:$port/$db",
                    SqlConfig.default.copy(
                        tls = Present(NetTlsConfig(trustAll = true)),
                        maxConnections = 1,
                        minConnections = 1
                    )
                ).flatMap { client =>
                    client.query("SELECT 'sha256_tls_ok'").map { rows =>
                        val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                        assert(str == "sha256_tls_ok", s"Expected 'sha256_tls_ok', got '$str'")
                    }
                }
            }
        }
    }

    // ─── Leaf 3: sha256_password as the SERVER's default plugin ──────────────

    "sha256_password named by the server's own HandshakeV10 authenticates over plaintext".tagged("kyo.OwnContainer") in {
        // The only configuration that reaches computeAuthResponse's sha256_password branch. The client's initial auth
        // response has to be the single byte 0x01, which is what makes the server answer with its RSA public key; an
        // empty response is read as an empty password and the connection is refused with a real password set.
        Scope.run {
            withSha256User(Maybe.Absent, Chunk("--default-authentication-plugin=sha256_password")) { (host, port, user, pass, db) =>
                MysqlClient.init(
                    s"mysql://$user:$pass@$host:$port/$db",
                    SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                ).flatMap { client =>
                    client.query("SELECT 'sha256_default_ok'").map { rows =>
                        val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                        assert(str == "sha256_default_ok", s"Expected 'sha256_default_ok', got '$str'")
                    }
                }
            }
        }
    }

end Sha256PasswordIntegrationTest
