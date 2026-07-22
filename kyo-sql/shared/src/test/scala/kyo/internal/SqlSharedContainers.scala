package kyo.internal

import kyo.*
import kyo.internal.mysql.MysqlConnection
import kyo.internal.postgres.PostgresConnection
import kyo.internal.postgres.types.EncodingRegistry

/** Per-fork-JVM singleton holder for the cross-backend container fixtures used by kyo-sql tests, plus a `withFreshSchema` API that hands
  * the caller a freshly-created database/schema scoped to the test's lifetime.
  *
  * The first call for a given backend lazily initializes the container via [[Container.initUnscoped]]; concurrent callers that lose the
  * compare-and-set race await the same [[Promise]] rather than starting a duplicate container. If startup fails, the singleton slot is
  * reset so the next caller may retry.
  *
  * Container labels are tagged `kyo-sql-singleton=<backend>-<gitSha>` for external CI cleanup. The git SHA is taken from the `GIT_SHA`
  * environment variable; when unset (e.g. local development), the label suffix is `local`.
  *
  * Containers are NOT torn down in-process; orphan cleanup is handled by the sbt `Test / testOptions` setup task that runs before each test
  * invocation across all platforms.
  */
object SqlSharedContainers:

    enum Backend derives CanEqual:
        case Postgres, MySQL

    final case class SchemaCtx(
        backend: Backend,
        host: String,
        port: Int,
        username: String,
        password: String,
        // The freshly-created schema/database name. CREATE happens on entry; DROP on exit (LIFO Scope.ensure).
        database: String
    )

    // --- Singleton state ---

    private type PgPromise    = Promise[ContainerPredef.Postgres, Abort[ContainerException]]
    private type MysqlPromise = Promise[ContainerPredef.MySQL, Abort[ContainerException]]

    // Unsafe boundary: module-load AtomicRef init (no live Frame yet). Uses Unsafe.init().safe
    // to construct the wrapper without requiring a Frame implicit; subsequent accesses use
    // the safe AtomicRef[*] API so the Promise inside is filled via safe APIs below.
    private val pgRef: AtomicRef[Maybe[PgPromise]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Maybe[PgPromise]](Maybe.empty).safe

    // Unsafe boundary: module-load AtomicRef init (no live Frame yet).
    private val mysqlRef: AtomicRef[Maybe[MysqlPromise]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Maybe[MysqlPromise]](Maybe.empty).safe

    // Unsafe boundary: module-load env read (no live Frame yet). Falls back to "local" when unset.
    private val gitSha: String =
        import AllowUnsafe.embrace.danger
        kyo.System.live.unsafe.env("GIT_SHA").getOrElse("local")

    // --- Singleton init via CAS + Promise ---

    private def getOrInitPg(using Frame): ContainerPredef.Postgres < (Async & Abort[ContainerException]) =
        pgRef.use {
            case Present(p) => p.get
            case Absent =>
                Promise.init[ContainerPredef.Postgres, Abort[ContainerException]].flatMap { p =>
                    pgRef.compareAndSet(Maybe.empty, Present(p)).flatMap {
                        case false =>
                            // Lost the race; await whoever won.
                            pgRef.use {
                                case Present(winner) => winner.get
                                case Absent          =>
                                    // Winner already reset due to failure; recurse to retry.
                                    getOrInitPg
                            }
                        case true =>
                            val predefCfg = ContainerPredef.Postgres.Config.default
                            val cfg = ContainerPredef.Postgres
                                .buildContainerConfig(predefCfg)
                                .label("kyo-sql-singleton", s"postgres-$gitSha")
                            Fiber.initUnscoped(
                                Container.initUnscoped(cfg).map(c => new ContainerPredef.Postgres(c, predefCfg))
                            ).flatMap { fiber =>
                                fiber.getResult.flatMap {
                                    case Result.Success(handle) =>
                                        p.completeDiscard(Result.succeed(handle)).andThen(handle)
                                    case Result.Failure(e: ContainerException) =>
                                        // Reset slot first so the next caller retries instead of seeing a poisoned Promise.
                                        pgRef.set(Maybe.empty).andThen(p.completeDiscard(Result.fail(e))).andThen(p.get)
                                    case Result.Panic(t) =>
                                        pgRef.set(Maybe.empty).andThen(p.completeDiscard(Result.panic(t))).andThen(p.get)
                                }
                            }
                    }
                }
        }

    private def getOrInitMysql(using Frame): ContainerPredef.MySQL < (Async & Abort[ContainerException]) =
        mysqlRef.use {
            case Present(p) => p.get
            case Absent =>
                Promise.init[ContainerPredef.MySQL, Abort[ContainerException]].flatMap { p =>
                    mysqlRef.compareAndSet(Maybe.empty, Present(p)).flatMap {
                        case false =>
                            mysqlRef.use {
                                case Present(winner) => winner.get
                                case Absent          => getOrInitMysql
                            }
                        case true =>
                            val predefCfg = ContainerPredef.MySQL.Config.default
                                .appendServerArgs("--default-authentication-plugin=mysql_native_password")
                            val cfg = ContainerPredef.MySQL
                                .buildContainerConfig(predefCfg)
                                .label("kyo-sql-singleton", s"mysql-$gitSha")
                            Fiber.initUnscoped(
                                Container.initUnscoped(cfg).map(c => new ContainerPredef.MySQL(c, predefCfg))
                            ).flatMap { fiber =>
                                fiber.getResult.flatMap {
                                    case Result.Success(handle) =>
                                        p.completeDiscard(Result.succeed(handle)).andThen(handle)
                                    case Result.Failure(e: ContainerException) =>
                                        mysqlRef.set(Maybe.empty).andThen(p.completeDiscard(Result.fail(e))).andThen(p.get)
                                    case Result.Panic(t) =>
                                        mysqlRef.set(Maybe.empty).andThen(p.completeDiscard(Result.panic(t))).andThen(p.get)
                                }
                            }
                    }
                }
        }

    // --- Schema name generation ---

    private def freshSchemaName(using Frame): String < Sync =
        Random.nextLong.map(v => s"test_${(v & Long.MaxValue).toHexString}")

    // --- Postgres DROP DATABASE retry on SQLSTATE 55006 ---

    private def dropPgSchema(
        admin: PostgresConnection,
        schema: String
    )(using Frame): Unit < (Async & Abort[SqlException]) =
        def attempt(remaining: Int): Unit < (Async & Abort[SqlException]) =
            Abort.run[SqlException](
                admin.simpleExecute(s"""DROP DATABASE IF EXISTS "$schema"""")
            ).flatMap {
                case Result.Success(_) => Kyo.unit
                case Result.Failure(s: SqlServerException) if s.sqlState == "55006" && remaining > 0 =>
                    Async.sleep(50.millis).andThen(attempt(remaining - 1))
                case Result.Failure(e) => Abort.fail(e)
                case Result.Panic(t)   => Abort.panic(t)
            }
        attempt(3)
    end dropPgSchema

    // --- Public API ---

    /** Run `f` against a fresh schema in the per-JVM shared container for `backend`. The schema is created on entry, the connection is
      * scoped to it, and the schema is dropped on exit (via [[Scope.ensure]], even if `f` fails).
      */
    def withFreshSchema[A, S](backend: Backend)(f: SchemaCtx => A < S)(using
        Frame
    )
        : A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        backend match
            case Backend.Postgres => withFreshPgSchema(f)
            case Backend.MySQL    => withFreshMysqlSchema(f)

    private def withFreshPgSchema[A, S](f: SchemaCtx => A < S)(using
        Frame
    )
        : A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        for
            pg     <- getOrInitPg
            port   <- pg.container.mappedPort(pg.config.port)
            schema <- freshSchemaName
            host = pg.container.host
            // Admin connection: connect to the default DB ("test"); used for CREATE/DROP DATABASE.
            admin <- PostgresConnection.connect(
                host,
                port,
                pg.username,
                pg.database,
                Present(pg.password),
                Absent,
                64,
                Duration.Infinity,
                EncodingRegistry.builtin
            )
            _ <- Scope.ensure(Abort.run(admin.terminate).unit)
            _ <- admin.simpleExecute(s"""CREATE DATABASE "$schema"""")
            _ <- Scope.ensure(Abort.run(dropPgSchema(admin, schema)).unit)
            // Per-test connection: superuser "test" against the freshly-created schema.
            test <- PostgresConnection.connect(
                host,
                port,
                pg.username,
                schema,
                Present(pg.password),
                Absent,
                64,
                Duration.Infinity,
                EncodingRegistry.builtin
            )
            _ <- Scope.ensure(Abort.run(test.terminate).unit)
            ctx = SchemaCtx(Backend.Postgres, host, port, pg.username, pg.password, schema)
            result <- f(ctx)
        yield result

    private def withFreshMysqlSchema[A, S](f: SchemaCtx => A < S)(using
        Frame
    )
        : A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        for
            my     <- getOrInitMysql
            port   <- my.container.mappedPort(my.config.port)
            schema <- freshSchemaName
            host = my.container.host
            // Admin connection: root with no default DB selected; used for CREATE/GRANT/DROP DATABASE.
            admin <- MysqlConnection.connect(host, port, "root", Present(my.config.rootPassword), Absent, Absent, 64, Duration.Infinity)
            _     <- Scope.ensure(Abort.run(admin.quit()).unit)
            _     <- admin.simpleExecute(s"CREATE DATABASE `$schema`")
            _     <- admin.simpleExecute(s"GRANT ALL ON `$schema`.* TO '${my.username}'@'%'")
            _ <- Scope.ensure(
                Abort.run(admin.simpleExecute(s"DROP DATABASE IF EXISTS `$schema`")).unit
            )
            // Per-test connection: default "test" user against the freshly-created (and GRANTed) schema.
            test <- MysqlConnection.connect(host, port, my.username, Present(my.password), Present(schema), Absent, 64, Duration.Infinity)
            _    <- Scope.ensure(Abort.run(test.quit()).unit)
            ctx = SchemaCtx(Backend.MySQL, host, port, my.username, my.password, schema)
            result <- f(ctx)
        yield result

end SqlSharedContainers
