package kyo.mysql

import kyo.*
import kyo.OwnContainer
import kyo.net.NetTlsConfig

/** Integration test for MySQL sha256_password auth plugin.
  *
  * sha256_password is the legacy RSA-auth plugin used by some MySQL 5.7 / 8.0 installations. Unlike caching_sha2_password there is no
  * fast-path cache, every connection either encrypts the password with the server's RSA public key (non-TLS) or sends cleartext over TLS.
  *
  * Container strategy:
  *   - A fresh MySQL 8.0 container is started with default settings.
  *   - Root connects and runs `ALTER USER 'test'@'%' IDENTIFIED WITH sha256_password BY 'test'` to switch the test user to sha256_password.
  *   - Non-TLS leaf: client sends empty initial auth; server replies with RSA public key in AuthMoreData; client XOR+RSA-encrypts and sends
  *     the ciphertext via [[kyo.internal.auth.RsaOaep]].
  *   - TLS leaf: same container, but [[NetTlsConfig]](trustAll=true); over TLS the client sends the cleartext NUL-terminated password
  *     directly in the initial HandshakeResponse41, skipping RSA.
  *
  * Both leaves exercise distinct code paths in [[kyo.internal.mysql.exchange.HandshakeExchange]].
  */
class Sha256PasswordIntegrationTest extends kyo.Test:

    override def timeout: Duration = 6.minutes

    // ─── Container + user-switch helper ─────────────────────────────────────

    /** Starts a fresh MySQL container, switches the "test" user to sha256_password via root, and runs `f` with connection details. */
    private def withSha256User[A, S](
        tls: Maybe[NetTlsConfig]
    )(
        f: (String, Int, String, String, String) => A < (S & Async & Abort[SqlException] & Scope)
    )(using Frame): A < (S & Async & Abort[Throwable] & Scope) =
        ContainerPredef.MySQL.initWith(ContainerPredef.MySQL.Config.default) { mysql =>
            mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                val host = mysql.container.host
                val user = mysql.username
                val pass = mysql.password
                val db   = mysql.database
                // Root connection: alter user plugin to sha256_password.
                val rootSetup =
                    Scope.run {
                        SqlClient.initMy(
                            s"mysql://root:${mysql.config.rootPassword}@$host:$port/mysql",
                            SqlClientConfig.default.copy(maxConnections = 1, minConnections = 1)
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
                SqlClient.initMy(
                    s"mysql://$user:$pass@$host:$port/$db",
                    SqlClientConfig.default.copy(maxConnections = 1, minConnections = 1)
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
                SqlClient.initMy(
                    s"mysql://$user:$pass@$host:$port/$db",
                    SqlClientConfig.default.copy(
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

end Sha256PasswordIntegrationTest
