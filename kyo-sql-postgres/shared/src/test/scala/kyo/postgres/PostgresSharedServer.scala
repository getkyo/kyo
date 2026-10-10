package kyo.postgres

import kyo.*
import kyo.internal.TestContainers

/** The `postgres:16-alpine` server shared by the kyo-sql-postgres leaves that need no server configuration of their own:
  * [[ContainerPredef.Postgres.Config.default]], plaintext only, `scram-sha-256` for every host connection.
  *
  * It is a [[TestContainers.initSingleton]] fixture under the same tag and config as kyo-sql-tests' shared Postgres, so a process of
  * either module adopts a live one left by the other. It outlives every leaf, so a leaf creates only uniquely named roles and databases
  * and drops them when its [[Scope]] closes.
  */
private[kyo] object PostgresSharedServer:

    private val predef = ContainerPredef.Postgres.Config.default

    private val tag = "postgres"

    def server(using Frame): ContainerPredef.Postgres < (Async & Abort[ContainerException]) =
        TestContainers.getOrInit(TestContainers.containers, tag)(
            TestContainers.initSingleton(ContainerPredef.Postgres.buildContainerConfig(predef), tag)
        ).map(new ContainerPredef.Postgres(_, predef))

    /** `postgres://` URL of the predef's `test` role on `database` of the shared server. */
    def url(database: String = predef.database)(using Frame): String < (Async & Abort[ContainerException]) =
        server.map { pg =>
            pg.container.mappedPort(pg.config.port).map { port =>
                s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/$database"
            }
        }

    /** A name no other leaf or process uses, for a role or database created on the shared server. */
    def uniqueName(prefix: String)(using Frame): String < Sync =
        Random.nextLong.map(v => s"${prefix}_${(v & Long.MaxValue).toHexString}")

end PostgresSharedServer
