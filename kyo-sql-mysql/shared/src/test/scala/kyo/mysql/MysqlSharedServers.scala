package kyo.mysql

import kyo.*
import kyo.internal.TestContainers

/** The MySQL servers the integration suites of this module share, one per config per test process. */
private[mysql] object MysqlSharedServers:

    /** The `ContainerPredef.MySQL.Config.default` server: caching_sha2_password as the default plugin, `test` as the only account. */
    def default(using Frame): ContainerPredef.MySQL < (Async & Abort[ContainerException]) =
        TestContainers.initSharedMysql(ContainerPredef.MySQL.Config.default, "mysql-default")

    /** Empties the server's caching_sha2_password credential cache, so the next login of every account takes the full-auth path. On a
      * shared server the cache is never empty otherwise: the predef's health check logs `test` in before any leaf runs, and so does
      * every earlier leaf.
      */
    def emptyCredentialCache(mysql: ContainerPredef.MySQL)(using Frame): Unit < (Async & Abort[SqlException | ContainerException]) =
        mysql.container.mappedPort(mysql.config.port).flatMap { port =>
            Scope.run {
                MysqlClient.init(
                    s"mysql://root:${mysql.config.rootPassword}@${mysql.container.host}:$port/mysql",
                    SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                ).flatMap(root => root.executeRaw("FLUSH PRIVILEGES").unit)
            }
        }

end MysqlSharedServers
