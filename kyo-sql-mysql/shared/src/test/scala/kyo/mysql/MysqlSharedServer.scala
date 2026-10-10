package kyo.mysql

import kyo.*
import kyo.internal.TestContainers

/** The `mysql:8.0` server shared by the kyo-sql-mysql leaves that need no server flag of their own: [[ContainerPredef.MySQL.Config.default]],
  * so `caching_sha2_password` is the server's default plugin and the image's auto-generated certificate makes TLS available.
  *
  * It is a [[TestContainers.initSingleton]] fixture, so it outlives every leaf and a failing leaf cannot leave it half torn down. A leaf
  * therefore changes nothing it does not own: no `FLUSH PRIVILEGES`, no `ALTER USER` on the `test` account. The predef's readiness probe
  * logs in as `test` over TCP, which leaves that account's caching_sha2 cache entry warm, and the leaves on `test` depend on it staying
  * warm. A leaf that needs an account in another state takes one of its own through [[withAccount]].
  */
private[kyo] object MysqlSharedServer:

    final case class Account(host: String, port: Int, user: String, password: String, database: String):
        def url: String = s"mysql://$user:$password@$host:$port/$database"

    private val predef = ContainerPredef.MySQL.Config.default

    private val tag = "mysql-caching-sha2"

    def server(using Frame): ContainerPredef.MySQL < (Async & Abort[ContainerException]) =
        TestContainers.getOrInit(TestContainers.containers, tag)(
            TestContainers.initSingleton(ContainerPredef.MySQL.buildContainerConfig(predef), tag)
        ).map(new ContainerPredef.MySQL(_, predef))

    /** The predef's `test` account and database on the shared server. */
    def testAccount(using Frame): Account < (Async & Abort[ContainerException]) =
        server.map { mysql =>
            mysql.container.mappedPort(mysql.config.port).map { port =>
                Account(mysql.container.host, port, mysql.username, mysql.password, mysql.database)
            }
        }

    /** A new account authenticating with `plugin`, owning a new database, both dropped when the enclosing [[Scope]] closes. */
    def withAccount[A, S](plugin: String)(f: Account => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        for
            base   <- testAccount
            suffix <- Random.nextLong.map(v => (v & Long.MaxValue).toHexString)
            account = base.copy(user = s"kyo_$suffix", password = s"pw_$suffix", database = s"kyo_$suffix")
            admin <- MysqlClient.init(
                s"mysql://root:${predef.rootPassword}@${account.host}:${account.port}/mysql",
                SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
            )
            _      <- admin.executeRaw(s"CREATE DATABASE `${account.database}`")
            _      <- Scope.ensure(Abort.run(admin.executeRaw(s"DROP DATABASE IF EXISTS `${account.database}`")).unit)
            _      <- admin.executeRaw(s"CREATE USER '${account.user}'@'%' IDENTIFIED WITH $plugin BY '${account.password}'")
            _      <- Scope.ensure(Abort.run(admin.executeRaw(s"DROP USER IF EXISTS '${account.user}'@'%'")).unit)
            _      <- admin.executeRaw(s"GRANT ALL ON `${account.database}`.* TO '${account.user}'@'%'")
            result <- f(account)
        yield result

end MysqlSharedServer
