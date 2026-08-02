package kyo.internal

import kyo.*
import kyo.internal.mysql.MysqlConnection
import kyo.internal.postgres.PostgresConnection

/** The postgres/mysql `withFreshSchema` API for kyo-sql tests: it hands the caller a freshly-created database/schema scoped to the test's
  * lifetime, provisioned against the per-JVM shared container fixture.
  *
  * The singleton container is memoized by descriptor id through [[SqlTestContainers.getOrInit]] over the shared
  * [[SqlTestContainers.containers]] table, which lives in the core test tree so both kyo-sql-tests and each backend module share one entry
  * per id: the first call for an id lazily initializes its container via [[SqlTestContainers.initSingleton]], concurrent callers await the
  * same [[kyo.Promise]] rather than starting a duplicate, and a failed startup removes that id's slot so the next caller may retry.
  *
  * Containers are NOT torn down in-process. They carry the `kyo-sql-singleton` and `kyo-sql-owner-pid` labels, and
  * [[SqlTestContainers.initSingleton]] removes every dead-owner container, together with its anonymous volumes, before creating a new
  * singleton. Nothing in the build reaps them and nothing can: a force-killed test process runs no sbt hook either, which is why the reap
  * sits on the creation path.
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

    /** Run `f` against a fresh schema in the per-JVM shared container for `backend`. The schema is created on entry, the connection is scoped
      * to it, and the schema is dropped on exit (via [[Scope.ensure]], even if `f` fails).
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
        // Scope kyo-pod's podman/docker HttpClient to the leaf. Without this, the ambient process-shared
        // HttpClient's 60-second idle-connection pool accumulates one unix socket per (mappedPort, wait,
        // remove) call and trips the end-of-run file-descriptor leak check on Linux CI.
        HttpClient.init().flatMap(scopedClient => HttpClient.let(scopedClient)(withFreshPgSchemaBody(f)))

    private def withFreshPgSchemaBody[A, S](f: SchemaCtx => A < S)(using
        Frame
    )
        : A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        val predefCfg = ContainerPredef.Postgres.Config.default
        for
            container <- SqlTestContainers.getOrInit(SqlTestContainers.containers, "postgres")(
                SqlTestContainers.initSingleton(ContainerPredef.Postgres.buildContainerConfig(predefCfg), "postgres")
            )
            port   <- container.mappedPort(predefCfg.port)
            schema <- freshSchemaName
            host = container.host
            // Admin connection: connect to the default DB ("test"); used for CREATE/DROP DATABASE.
            admin <- PostgresConnection.connect(
                host,
                port,
                predefCfg.username,
                predefCfg.database,
                Present(predefCfg.password),
                Absent,
                64,
                Duration.Infinity
            )
            _ <- Scope.ensure(Abort.run(admin.terminate).unit)
            _ <- admin.simpleExecute(s"""CREATE DATABASE "$schema"""")
            _ <- Scope.ensure(Abort.run(dropPgSchema(admin, schema)).unit)
            // Per-test connection: superuser "test" against the freshly-created schema.
            test <- PostgresConnection.connect(
                host,
                port,
                predefCfg.username,
                schema,
                Present(predefCfg.password),
                Absent,
                64,
                Duration.Infinity
            )
            _ <- Scope.ensure(Abort.run(test.terminate).unit)
            ctx = SchemaCtx(Backend.Postgres, host, port, predefCfg.username, predefCfg.password, schema)
            result <- f(ctx)
        yield result
        end for
    end withFreshPgSchemaBody

    private def withFreshMysqlSchema[A, S](f: SchemaCtx => A < S)(using
        Frame
    )
        : A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        HttpClient.init().flatMap(scopedClient => HttpClient.let(scopedClient)(withFreshMysqlSchemaBody(f)))

    private def withFreshMysqlSchemaBody[A, S](f: SchemaCtx => A < S)(using
        Frame
    )
        : A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        // `performance_schema.prepared_statements_instances` is the only place a client can ask MySQL how many
        // server-side prepared statements a session still holds, and `PreparedStmtEvictionIntegrationTest` is the only
        // thing that watches cache eviction, which since the stream path stopped sending COM_STMT_CLOSE is MySQL's only
        // server-side close path. `ContainerPredef.MySQL.defaultServerArgs` passes `--performance-schema=OFF` to keep
        // fixtures small, so the table exists, reads as empty for every session, and answers 0 forever. That default is
        // right for kyo-pod and wrong for this fixture, so the override lands here rather than in the predef, and it
        // works because `serverArgs` is appended after the baseline and MySQL takes the last value of a repeated flag.
        //
        // The sizing flags pay for it. Measured on mysql:8.0 with the baseline args: 123 MB with performance_schema off,
        // 362 MB with it on and autosized, 252 MB with it on and sized as below. The event-history and digest tables are
        // what autosizing spends the difference on and nothing here reads them. Instrument and consumer flags are
        // deliberately absent: `--performance-schema-instrument=%=OFF` leaves the table permanently empty, which is the
        // same silent zero this override exists to remove. The instance ceiling is explicit so the table cannot quietly
        // overflow into `Performance_schema_prepared_statements_lost` and make the probe under-report.
        val predefCfg = ContainerPredef.MySQL.Config.default
            .appendServerArgs(
                "--default-authentication-plugin=mysql_native_password",
                "--performance-schema=ON",
                "--performance-schema-max-prepared-statements-instances=1024",
                "--performance-schema-digests-size=0",
                "--performance-schema-events-waits-history-size=0",
                "--performance-schema-events-waits-history-long-size=0",
                "--performance-schema-events-statements-history-size=0",
                "--performance-schema-events-statements-history-long-size=0",
                "--performance-schema-events-stages-history-size=0",
                "--performance-schema-events-stages-history-long-size=0",
                "--performance-schema-events-transactions-history-size=0",
                "--performance-schema-events-transactions-history-long-size=0"
            )
        for
            container <- SqlTestContainers.getOrInit(SqlTestContainers.containers, "mysql")(
                SqlTestContainers.initSingleton(ContainerPredef.MySQL.buildContainerConfig(predefCfg), "mysql")
            )
            port   <- container.mappedPort(predefCfg.port)
            schema <- freshSchemaName
            host = container.host
            // Admin connection: root with no default DB selected; used for CREATE/GRANT/DROP DATABASE.
            admin <- MysqlConnection.connect(host, port, "root", Present(predefCfg.rootPassword), Absent, Absent, 64, Duration.Infinity)
            _     <- Scope.ensure(Abort.run(admin.quit()).unit)
            _     <- admin.simpleExecute(s"CREATE DATABASE `$schema`")
            _     <- admin.simpleExecute(s"GRANT ALL ON `$schema`.* TO '${predefCfg.username}'@'%'")
            // The prepared-statement eviction suite counts this connection's server-side statements through
            // `performance_schema.prepared_statements_instances` and `sys.ps_thread_id`. TWO independent conditions gate
            // that, and satisfying either one alone still reads zero. First, the ENGINE is off unless the container args
            // turn it on, which the performance_schema override above does. Second, and independently, the entrypoint's
            // MYSQL_USER lacks the privilege to READ the table even once the engine is on, failing 1142 on the table and
            // 1370 on the routine, which the two grants below fix. They are global rather than schema-scoped, so they
            // cannot hang off the GRANT above, and both are idempotent when a later fresh schema repeats them.
            //
            // Neither half makes the other unnecessary: with the grants in place and the engine off, the table exists,
            // reads as empty for every session, and answers a quiet zero that no assertion can tell from real eviction.
            _ <- admin.simpleExecute(s"GRANT SELECT ON performance_schema.* TO '${predefCfg.username}'@'%'")
            _ <- admin.simpleExecute(s"GRANT EXECUTE ON sys.* TO '${predefCfg.username}'@'%'")
            _ <- Scope.ensure(
                Abort.run(admin.simpleExecute(s"DROP DATABASE IF EXISTS `$schema`")).unit
            )
            // Per-test connection: default "test" user against the freshly-created (and GRANTed) schema.
            test <-
                MysqlConnection.connect(
                    host,
                    port,
                    predefCfg.username,
                    Present(predefCfg.password),
                    Present(schema),
                    Absent,
                    64,
                    Duration.Infinity
                )
            _ <- Scope.ensure(Abort.run(test.quit()).unit)
            ctx = SchemaCtx(Backend.MySQL, host, port, predefCfg.username, predefCfg.password, schema)
            result <- f(ctx)
        yield result
        end for
    end withFreshMysqlSchemaBody

end SqlSharedContainers
