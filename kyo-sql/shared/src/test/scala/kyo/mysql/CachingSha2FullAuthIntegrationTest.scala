package kyo.mysql

import kyo.*
import kyo.OwnContainer

/** Integration test for caching_sha2_password full-auth via pure-Scala RSA-OAEP.
  *
  * Connects to a fresh MySQL 8.0 container (cache is cold → server sends full-auth required / AuthMoreData 0x04). The client requests the
  * RSA public key, encrypts the password with the pure-Scala [[kyo.internal.auth.RsaOaep]] implementation, and the server decrypts it.
  *
  * A fresh container per test ensures the server-side credential cache is empty so the full-auth path is always taken (no fast-path
  * bypass).
  */
class CachingSha2FullAuthIntegrationTest extends kyo.Test:

    override def timeout: Duration = 4.minutes

    // ─── Integration leaf: cache-miss full-auth via pure-Scala RSA-OAEP ──────────

    "caching_sha2_password full-auth via pure-Scala RSA-OAEP succeeds against fresh MySQL container".tagged("kyo.OwnContainer") in {
        Scope.run {
            // Fresh container = cold auth cache → server will issue full-auth (AuthMoreData 0x04).
            ContainerPredef.MySQL.initWith(ContainerPredef.MySQL.Config.default) { mysql =>
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    // Connect without TLS: forces the RSA-OAEP encrypted full-auth path.
                    SqlClient.initMy(
                        s"mysql://${mysql.username}:${mysql.password}@${mysql.container.host}:$port/${mysql.database}",
                        SqlClientConfig.default.copy(maxConnections = 1, minConnections = 1)
                    ).flatMap { client =>
                        // Verify the connection works by running SELECT 1.
                        client.query("SELECT 1").map { rows =>
                            val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                            assert(str == "1")
                        }
                    }
                }
            }
        }
    }

end CachingSha2FullAuthIntegrationTest
