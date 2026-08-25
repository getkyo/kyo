package kyo.internal

import kyo.*
import kyo.internal.mysql.MysqlConnection

/** The MySQL conformance test-backend descriptor: the one place the MySQL provisioning literals live, contributed by this module and
  * discovered by kyo-sql-tests through the `META-INF/services/kyo.internal.SqlTestBackend` entry so the backend-agnostic conformance battery
  * names no engine.
  *
  * Provisioning relocates [[SqlSharedContainers.withFreshMysqlSchema]] verbatim: a shared MySQL container memoized by the id `"mysql"`
  * through [[SqlTestContainers.getOrInit]], a freshly-created database per leaf, an admin connection that runs the CREATE/GRANT/DROP SQL, and a
  * scoped per-test connection, all dropped on scope exit even when the body fails. The container config carries the same `performance_schema`
  * override [[containerConfig]] documents, so this descriptor and kyo-sql-tests share one container for the id whichever inits first.
  *
  * Constructed by name: the services scan and the register fallback both instantiate this class, so its fully-qualified name is part of the
  * test artifact's contract and its zero-argument constructor stays public.
  *
  * @see
  *   [[SqlTestBackend]] the descriptor contract
  * @see
  *   [[SqlTestContainers.getOrInit]] the shared id-keyed singleton holder
  */
final class MysqlTestBackend extends SqlTestBackend:

    def id: String        = "mysql"
    def label: String     = "mysql"
    def urlScheme: String = "mysql"

    /** MySQL quotes identifiers with backticks. */
    def quoteIdent(name: String): String = s"`$name`"

    /** MySQL has no RETURNING clause. */
    def supportsReturning: Boolean = false

    /** MySQL 8 accepts `WITH RECURSIVE`. */
    def supportsRecursiveCte: Boolean = true

    def textColumnType: String = "TEXT"

    // BIGINT, not INT: the generated-key path decodes the returned key as a Long, so the auto-increment column must be
    // eight bytes to round-trip, matching the postgres descriptor's BIGSERIAL. The fragment carries the full column type
    // so a conformance body splices it straight after the column name with no engine branch.
    def autoIncrementPrimaryKey: String = "BIGINT AUTO_INCREMENT PRIMARY KEY"

    def columnType(key: SqlTestBackend.ColumnType): String =
        import SqlTestBackend.ColumnType.*
        key match
            case SmallInt         => "SMALLINT"
            case Int              => "INT"
            case BigInt           => "BIGINT"
            case Numeric          => "DECIMAL(38,10)"
            case Boolean          => "BOOLEAN"
            case Float32          => "FLOAT"
            case Float64          => "DOUBLE"
            case Bytes            => "BLOB"
            case Uuid             => "VARCHAR(36)"
            case Date             => "DATE"
            case Time             => "TIME"
            case TimeWithOffset   => "VARCHAR(64)"
            case DateTime         => "DATETIME"
            case Timestamp        => "DATETIME"
            case CalendarInterval => "VARCHAR(64)"
            case Duration         => "TIME"
            case Json             => "JSON"
            case IntArray         => "JSON"
            case TextArray        => "JSON"
            case JsonArray        => "JSON"
        end match
    end columnType

    def tableNotFoundSqlState: String = "42S02"

    def uniqueViolationSqlState: String = "23000"

    def sessionIdSql: String = "CONNECTION_ID()"

    /** The MySQL fixture config, identical to the one [[SqlSharedContainers.withFreshMysqlSchema]] builds so both share one container per id.
      *
      * `performance_schema.prepared_statements_instances` is the only place a client can ask MySQL how many server-side prepared statements a
      * session still holds, and the eviction suite is the only thing that watches cache eviction, which since the stream path stopped sending
      * COM_STMT_CLOSE is MySQL's only server-side close path. `ContainerPredef.MySQL.defaultServerArgs` passes `--performance-schema=OFF` to
      * keep fixtures small, so the table exists, reads as empty for every session, and answers 0 forever. That default is right for kyo-pod and
      * wrong for this fixture, so the override lands here rather than in the predef, and it works because `serverArgs` is appended after the
      * baseline and MySQL takes the last value of a repeated flag.
      *
      * The sizing flags pay for it. Measured on mysql:8.0 with the baseline args: 123 MB with performance_schema off, 362 MB with it on and
      * autosized, 252 MB with it on and sized as below. The event-history and digest tables are what autosizing spends the difference on and
      * nothing here reads them. Instrument and consumer flags are deliberately absent: `--performance-schema-instrument=%=OFF` leaves the table
      * permanently empty, the same silent zero this override exists to remove. The instance ceiling is explicit so the table cannot quietly
      * overflow into `Performance_schema_prepared_statements_lost` and make the probe under-report.
      */
    private val predefCfg: ContainerPredef.MySQL.Config =
        ContainerPredef.MySQL.Config.default
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

    def containerConfig: Container.Config = ContainerPredef.MySQL.buildContainerConfig(predefCfg)

    private def freshSchemaName(using Frame): String < Sync =
        Random.nextLong.map(v => s"test_${(v & Long.MaxValue).toHexString}")

    def withFreshSchema[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
        // Scope kyo-pod's podman/docker HttpClient to the leaf. Without this, the ambient process-shared
        // HttpClient's 60-second idle-connection pool accumulates one unix socket per (mappedPort, wait,
        // remove) call and trips the end-of-run file-descriptor leak check on Linux CI.
        HttpClient.init().flatMap(scopedClient => HttpClient.let(scopedClient)(withFreshSchemaBody(f)))

    private def withFreshSchemaBody[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope) =
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
            // The eviction suite counts this connection's server-side statements through
            // `performance_schema.prepared_statements_instances` and `sys.ps_thread_id`. The entrypoint's MYSQL_USER
            // lacks the privilege to READ either even once the engine is on (1142 on the table, 1370 on the routine),
            // which the two grants below fix. They are global rather than schema-scoped, so they cannot hang off the
            // GRANT above, and both are idempotent when a later fresh schema repeats them.
            _ <- admin.simpleExecute(s"GRANT SELECT ON performance_schema.* TO '${predefCfg.username}'@'%'")
            _ <- admin.simpleExecute(s"GRANT EXECUTE ON sys.* TO '${predefCfg.username}'@'%'")
            _ <- Scope.ensure(Abort.run(admin.simpleExecute(s"DROP DATABASE IF EXISTS `$schema`")).unit)
            // Per-test connection: default "test" user against the freshly-created (and GRANTed) schema.
            test <- MysqlConnection.connect(
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
            ctx = SqlTestBackend.Schema(
                host = host,
                port = port,
                username = predefCfg.username,
                password = predefCfg.password,
                database = schema,
                url = s"mysql://${predefCfg.username}:${predefCfg.password}@$host:$port/$schema"
            )
            result <- f(ctx)
        yield result
        end for
    end withFreshSchemaBody

end MysqlTestBackend
