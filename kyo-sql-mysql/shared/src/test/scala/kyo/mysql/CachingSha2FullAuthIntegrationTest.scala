package kyo.mysql

import kyo.*
import kyo.OwnContainer
import kyo.internal.TestContainers
import kyo.internal.mysql.MysqlConnection
import kyo.internal.mysql.exchange.MysqlAuthPath

/** Integration test for caching_sha2_password full-auth via pure-Scala RSA-OAEP.
  *
  * On a cache miss the server sends full-auth required (AuthMoreData 0x04). The client requests the RSA public key, encrypts the password
  * with kyo-crypto's [[kyo.crypto.RsaOaep]] through [[kyo.internal.mysql.auth.PasswordEncryption]], and the server decrypts it.
  *
  * A fresh container does not give a cache miss for `test`: the readiness probe has already logged it in. The leaf authenticates as an
  * account created for it, which has never logged in, and asserts the path through [[MysqlConnection.authPath]].
  */
class CachingSha2FullAuthIntegrationTest extends SqlContainerTest:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 4.minutes

    private def connect(host: String, port: Int, user: String, password: String, db: String)(using
        Frame
    ): MysqlConnection < (Async & Abort[SqlException] & Scope) =
        MysqlConnection.connect(host, port, user, Present(password), Present(db), Absent, 64, Duration.Infinity)
            .flatMap(conn => Scope.ensure(Abort.run(conn.quit()).unit).andThen(conn))

    // ─── Integration leaf: cache-miss full-auth via pure-Scala RSA-OAEP ──────────

    "caching_sha2_password full-auth via pure-Scala RSA-OAEP succeeds against fresh MySQL container".tagged(OwnContainer.name) in {
        Scope.run {
            // Through `TestContainers` rather than `ContainerPredef.MySQL.initWith` directly, so the
            // container carries the `kyo-test-container` and `kyo-test-owner-pid` labels and a killed test
            // process leaves something the reaper can find.
            TestContainers.initScopedMysql(ContainerPredef.MySQL.Config.default, "mysql-caching-sha2-full-auth").map { mysql =>
                mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                    val host = mysql.container.host
                    val user = "full_auth"
                    val pass = "full_auth_pw"
                    Scope.run {
                        connect(host, port, "root", mysql.config.rootPassword, mysql.database).flatMap { root =>
                            root.simpleExecute(s"CREATE USER '$user'@'%' IDENTIFIED WITH caching_sha2_password BY '$pass'")
                                .andThen(root.simpleExecute(s"GRANT ALL ON `${mysql.database}`.* TO '$user'@'%'"))
                        }
                    }.andThen {
                        // Connect without TLS: forces the RSA-OAEP encrypted full-auth path.
                        connect(host, port, user, pass, mysql.database).flatMap { conn =>
                            conn.simpleQuery("SELECT 1").map { rows =>
                                assert(
                                    conn.authPath == MysqlAuthPath(Absent, Present(MysqlAuthPath.CachingSha2.FullAuth)),
                                    s"a never-used account must take full auth, took ${conn.authPath}"
                                )
                                assert(rows.size == 1)
                                assert(new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8) == "1")
                            }
                        }
                    }
                }
            }
        }
    }

end CachingSha2FullAuthIntegrationTest
