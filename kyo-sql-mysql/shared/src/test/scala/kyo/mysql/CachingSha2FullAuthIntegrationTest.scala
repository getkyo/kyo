package kyo.mysql

import kyo.*

/** Integration test for caching_sha2_password full-auth via pure-Scala RSA-OAEP.
  *
  * Connects to a MySQL 8.0 server whose credential cache was just emptied (cache is cold → server sends full-auth required / AuthMoreData
  * 0x04). The client requests the RSA public key, encrypts the password with kyo-crypto's [[kyo.crypto.RsaOaep]] through
  * [[kyo.internal.mysql.auth.PasswordEncryption]], and the server decrypts it.
  */
class CachingSha2FullAuthIntegrationTest extends SqlContainerTest:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 4.minutes

    // ─── Integration leaf: cache-miss full-auth via pure-Scala RSA-OAEP ──────────

    "caching_sha2_password full-auth via pure-Scala RSA-OAEP succeeds against a cold credential cache" in {
        Scope.run {
            // Emptied auth cache → server will issue full-auth (AuthMoreData 0x04).
            MysqlSharedServers.default.map { mysql =>
                MysqlSharedServers.emptyCredentialCache(mysql).andThen(mysql.container.mappedPort(mysql.config.port)).flatMap { port =>
                    // Connect without TLS: forces the RSA-OAEP encrypted full-auth path.
                    MysqlClient.init(
                        s"mysql://${mysql.username}:${mysql.password}@${mysql.container.host}:$port/${mysql.database}",
                        SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                    ).flatMap { client =>
                        // Verify the connection works by running SELECT 1. `client.query` on MySQL routes
                        // unparameterised queries to the binary extended protocol; `SELECT 1` comes back
                        // as an 8-byte little-endian BIGINT, decoded via row.decode[Long] which routes
                        // to MysqlRowReader (SqlRow.decode dispatches by field OID presence).
                        client.query("SELECT 1").flatMap { rows =>
                            rows(0).decode[Long](0).map { v =>
                                assert(v == 1L)
                            }
                        }
                    }
                }
            }
        }
    }

end CachingSha2FullAuthIntegrationTest
