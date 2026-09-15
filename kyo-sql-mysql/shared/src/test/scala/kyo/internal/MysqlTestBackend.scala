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
    // Matches MysqlDialect.quoteIdent. Without the doubling, a name carrying a backtick closes the quoting early.
    def quoteIdent(name: String): String =
        val escaped = name.replace("`", "``")
        s"`$escaped`"
    end quoteIdent

    /** MySQL has no RETURNING clause. */
    def supportsReturning: Boolean = false

    /** MySQL 8 accepts `WITH RECURSIVE`. */
    def supportsRecursiveCte: Boolean = true

    // Named rather than inherited, which is why string comparison agrees across engines: this server's default is case-
    // AND accent-insensitive. Declaring it at the COLUMN is the only fix reaching every operation, since a
    // connection-level pin reaches literal-against-literal comparison only and a per-query COLLATE misses a unique index.
    def textColumnType: String = "TEXT COLLATE utf8mb4_0900_as_cs"

    // BIGINT, not INT: the generated-key path decodes the returned key as a Long, so the auto-increment column must be
    // eight bytes to round-trip, matching the postgres descriptor's BIGSERIAL. The fragment carries the full column type
    // so a conformance body splices it straight after the column name with no engine branch.
    def autoIncrementPrimaryKey: String = "BIGINT AUTO_INCREMENT PRIMARY KEY"

    def columnType(key: SqlTestBackend.ColumnType): String =
        import SqlTestBackend.ColumnType.*
        key match
            case SmallInt => "SMALLINT"
            case Int      => "INT"
            case BigInt   => "BIGINT"
            case Numeric  => "DECIMAL(38,10)"
            case Boolean  => "BOOLEAN"
            case Float32  => "FLOAT"
            case Float64  => "DOUBLE"
            case Bytes    => "BLOB"
            case Uuid     => "VARCHAR(36)"
            case Date     => "DATE"
            // Precision named on all three: an unqualified temporal column is precision 0 here and 6 on the other
            // engine, so the same neutral kind would round `12:00:00.5` to `12:00:01` on one backend only.
            case Time           => "TIME(6)"
            case TimeWithOffset => "VARCHAR(64)"
            case DateTime       => "DATETIME(6)"
            // This engine's INSTANT type, matching what the other descriptor names. A wall-clock column would pass the
            // instant leaves anyway (the UTC pin makes the two coincide) while testing nothing. The trade is the
            // narrower range, 1970..2038, which the value-domain battery covers; `DateTime` stays the wall-clock kind.
            case Timestamp        => "TIMESTAMP(6)"
            case CalendarInterval => "VARCHAR(64)"
            // TIME(6) for the reason above: at precision 0 a sub-second `Duration` stores rounded here only.
            case Duration  => "TIME(6)"
            case Json      => "JSON"
            case IntArray  => "JSON"
            case TextArray => "JSON"
            case JsonArray => "JSON"
        end match
    end columnType

    def typeNameFor(kind: SqlTestBackend.ColumnType): String =
        import SqlTestBackend.ColumnType.*
        kind match
            case Int     => "INT"
            case BigInt  => "BIGINT"
            case Float64 => "DOUBLE"
            case Date    => "DATE"
            // A BOOLEAN declaration is stored as the smallest integer, which is the name reported for it.
            case Boolean => "TINYINT"
            case other   => throw new IllegalArgumentException(s"no reported type name pinned for $other on mysql")
        end match
    end typeNameFor

    def bytesLiteral(hexDigits: String): String = s"X'${hexDigits.toUpperCase}'"

    /** No equivalent settings: this engine's simple protocol has no knob that respells a value it already stores. */
    def outputAffectingSettings: Chunk[String] = Chunk.empty

    /** MySQL has no boolean type. A `BOOLEAN` declaration is `TINYINT(1)`, and the column reports the integer it is. */
    def booleanColumnKind: SqlRow.ColumnKind = SqlRow.ColumnKind.Integer

    /** A MySQL `TIMESTAMP` arrives converted to the session zone with NO offset beside it, so the wire form has nowhere to put one and the
      * driver pins the session at connect instead.
      */
    def instantWireCarriesOffset: Boolean = false

    /** No array column type: a collection is carried inside a JSON document and reported as one. */
    def hasNativeArrayColumns: Boolean = false

    /** MySQL's `TIME` is a signed span from -838:59:59 to 838:59:59, so roughly half its range has no time-of-day reading. */
    def timeColumnIsSignedSpan: Boolean = true

    /** No calendar-interval column type; `CalendarInterval` maps to text here. */
    def hasCalendarIntervalColumn: Boolean = false

    def hasNetworkAddressColumn: Boolean = false

    /** No time-with-offset column type; `TimeWithOffset` maps to text here. */
    def hasTimeWithOffsetColumn: Boolean = false

    /** `DECIMAL` refuses `NaN` and the infinities, and `DATE` and `TIMESTAMP` have no infinity value. */
    def hasNonFiniteSpecialValues: Boolean = false

    /** This engine has no placement keyword, so the dialect lowers the placement into a second ordering term, which this frame forbids. */
    def windowRangeOffsetHonoursAbsentPlacement: Boolean = false

    /** Every column type this engine has carries a value this module renders, so there is nothing here to refuse. */
    def unrenderableColumns: Chunk[(String, String)] = Chunk.empty

    /** No array column type, so there are no array elements to render. */
    def arrayRenderCases: Chunk[(String, String, String)] = Chunk.empty

    /** `BIT` is unsigned and reaches 64 bits. A signed accumulation answers -1 for an all-ones `BIT(64)` under BOTH protocols, which a
      * cross-protocol comparison cannot see: it agrees, and both answers are wrong.
      */
    def protocolAgreementCases: Chunk[(String, String, String)] = Chunk(
        ("BIT(64)", "b'" + ("1" * 64) + "'", "18446744073709551615")
    )

    def tableNotFoundSqlState: String = "42S02"

    def uniqueViolationSqlState: String = "23000"

    def sessionIdSql: String = "CONNECTION_ID()"

    // Reads the level of the transaction actually in progress, which `@@transaction_isolation` does NOT report.
    // `SET TRANSACTION ISOLATION LEVEL` without a scope applies to the next transaction while the session variable
    // keeps its old value, so reading the variable answers the session default and calls every level a mismatch
    // except the one that happens to be the default. performance_schema is the only place the running
    // transaction's own level is visible, and the fixture already enables it. The thread lookup goes through
    // `performance_schema.threads` rather than `PS_CURRENT_THREAD_ID()`, which needs 8.0.16 or newer.
    def isolationIntrospectionSql: String =
        """SELECT ISOLATION_LEVEL FROM performance_schema.events_transactions_current
          | WHERE THREAD_ID = (SELECT THREAD_ID FROM performance_schema.threads WHERE PROCESSLIST_ID = CONNECTION_ID())""".stripMargin

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
            // The charset is named rather than inherited. A database created with no CHARACTER SET takes the server's
            // default, so a conformance leaf storing an astral character passes or fails on a property of the image
            // rather than of the driver: utf8mb3 cannot hold U+1F600 at all and refuses the insert. Naming it here
            // makes every schema this fixture hands out mean the same thing.
            _ <- admin.simpleExecute(s"CREATE DATABASE `$schema` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
            _ <- admin.simpleExecute(s"GRANT ALL ON `$schema`.* TO '${predefCfg.username}'@'%'")
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
