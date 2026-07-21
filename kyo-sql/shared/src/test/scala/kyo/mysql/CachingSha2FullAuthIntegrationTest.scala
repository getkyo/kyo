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

    // Each leaf spins its own MySQL container. Serialize leaves so at most one MySQL boot races
    // for host resources at a time; the 30-second port-binding budget is unreliable under parallel
    // container starts on a single Docker host.
    override def config = super.config.sequential

    override def timeout: Duration = 4.minutes

    // ─── Integration leaf: cache-miss full-auth via pure-Scala RSA-OAEP ──────────

    "caching_sha2_password full-auth via pure-Scala RSA-OAEP succeeds against fresh MySQL container".tagged("kyo.OwnContainer") in {
        Scope.run {
            // Fresh container = cold auth cache → server will issue full-auth (AuthMoreData 0x04).
            ContainerPredef.MySQL.initWith(ContainerPredef.MySQL.Config.default) { mysql =>
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    // Connect without TLS: forces the RSA-OAEP encrypted full-auth path.
                    SqlClient.initMysql(
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
