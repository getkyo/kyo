package kyo.mysql

import kyo.*

class MysqlSharedServersTest extends SqlContainerTest:

    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    override def timeout: Duration = 4.minutes

    // A plaintext login that refuses the RSA key exchange completes only on the fast path, which needs the account in the
    // credential cache; with the cache empty the CLI stops with error 2061. Run inside the container, so it reads the server's
    // cache state and nothing of the driver under test.
    private def fastPathLogin(mysql: ContainerPredef.MySQL)(using Frame): Container.ExecResult < (Async & Abort[ContainerException]) =
        mysql.container.exec(
            "mysql",
            "-h",
            "127.0.0.1",
            s"-u${mysql.username}",
            s"-p${mysql.password}",
            mysql.database,
            "--ssl-mode=DISABLED",
            "--get-server-public-key=0",
            "-N",
            "-e",
            "SELECT 1"
        )

    "emptying the credential cache sends the next login down the full-auth path" in {
        Scope.run {
            MysqlSharedServers.default.map { mysql =>
                for
                    warm <- fastPathLogin(mysql)
                    _    <- MysqlSharedServers.emptyCredentialCache(mysql)
                    cold <- fastPathLogin(mysql)
                yield
                    assert(warm.isSuccess, s"the health check's login should leave test cached: ${warm.stderr}")
                    assert(!cold.isSuccess, "a login after the flush took the fast path")
                    assert(
                        cold.stderr.contains("2061"),
                        s"expected error 2061 (full auth requires a secure connection), got: ${cold.stderr}"
                    )
                end for
            }
        }
    }

end MysqlSharedServersTest
