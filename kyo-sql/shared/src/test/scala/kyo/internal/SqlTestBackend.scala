package kyo.internal

import kyo.*

/** One engine's conformance test descriptor: everything the backend-agnostic conformance battery needs to run against a live server of that
  * engine, contributed by the backend module rather than named in kyo-sql-tests.
  *
  * A descriptor lives in the kyo-sql core test tree so both kyo-sql-tests and each backend module's test tree can see it: kyo-sql-tests
  * consumes descriptors through [[withFreshSchema]] and the capability flags, and each backend module implements one against its own
  * `ContainerPredef` and connection type. The descriptor set is discovered at run time, so kyo-sql-tests iterates whatever descriptors are on
  * the test classpath and names no engine.
  *
  * Capability flags exist so a conformance body branches on a behavior, never on an engine name: a leaf that needs a recursive CTE reads
  * [[supportsRecursiveCte]], and DDL that differs between engines is generated from [[textColumnType]], [[autoIncrementPrimaryKey]], and
  * [[quoteIdent]] rather than from an inline literal. A flag is added only when a conformance body actually branches on it.
  *
  * @see
  *   [[SqlTestBackend.Schema]] the fresh-schema context [[withFreshSchema]] hands back
  */
abstract class SqlTestBackend:

    /** The stable id this descriptor registers and is looked up under, typically its URL scheme. */
    def id: String

    /** The label naming this backend's conformance leaves, such as the engine's own name. */
    def label: String

    /** The URL scheme a client opens this backend with, such as `postgres`. */
    def urlScheme: String

    /** The container configuration this backend's shared singleton fixture starts from, or [[Absent]] for an engine that needs no daemon.
      *
      * [[Absent]] is what makes [[reachable]] answer true on a host with no container runtime.
      */
    def containerConfig: Maybe[Container.Config]

    /** Whether this descriptor can actually be exercised on this host: registered means the classpath carries it, reachable means the
      * environment can serve it. A server engine needs a container daemon; an embedded one is reachable wherever the test JVM runs.
      */
    def reachable: Boolean =
        containerConfig.isEmpty || ContainerRuntimeProbe.reachable

    /** Quotes `name` as an identifier the way this engine's dialect does. */
    def quoteIdent(name: String): String

    /** Whether this engine returns a generated key through a RETURNING clause. */
    def supportsReturning: Boolean

    /** Whether this engine accepts the RECURSIVE keyword on a common table expression. */
    def supportsRecursiveCte: Boolean

    /** The DDL column type this engine uses for unbounded text. */
    def textColumnType: String

    /** The DDL column fragment this engine uses to declare an auto-incrementing primary key. */
    def autoIncrementPrimaryKey: String

    /** The DDL column type this engine uses for the given portable column kind, so a conformance body builds a table without an engine
      * literal. See [[SqlTestBackend.ColumnType]].
      */
    def columnType(key: SqlTestBackend.ColumnType): String

    /** This engine's own name for a column of the given kind, as row metadata reports it (`int4` against `INT`). Only the kinds a body asks
      * about need answer; the rest may raise.
      */
    def typeNameFor(kind: SqlTestBackend.ColumnType): String

    /** This engine's spelling of a byte-string literal, `'\xdeadbeef'` against `X'DEADBEEF'`. Literal syntax, not a capability. */
    def bytesLiteral(hexDigits: String): String

    /** Whether this engine's DEFAULT isolation prevents a lost update, which its read level does not determine: one engine repeats reads,
      * so its level is honestly REPEATABLE READ, and still lets two concurrent increments lose one with neither writer refused.
      */
    def defaultPreventsLostUpdate: Boolean

    /** Whether this engine computes a RANGE window frame with an offset bound, rather than widening it to the whole partition.
      *
      * One engine gets it silently wrong: over 1, 2, 2, 3 and NULL, a `RANGE BETWEEN 2 PRECEDING AND CURRENT ROW` sum answers the partition
      * total for every row. Its dialect refuses the construct, and the leaves that exercise such a frame assert the refusal.
      */
    def computesRangeOffsetFrames: Boolean

    /** Whether a JSON object read back holds its keys in the order they were written. Unordered by the spec, so this is a storage property
      * rather than a correctness one: two engines keep the written order and one sorts the keys.
      */
    def jsonPreservesKeyOrder: Boolean

    /** Whether a conflict clause still lets a foreign-key violation fail. A conflict clause is about duplicate keys, so the engines that
      * fail are the correct ones; one engine returns a zero-row outcome instead.
      */
    def conflictClauseEnforcesForeignKeys: Boolean

    /** This engine's spelling of an instant literal at UTC, given the wall-clock text `yyyy-MM-dd HH:mm:ss`.
      *
      * Two engines take a trailing `+00:00`; a third refuses that form and reads a bare literal against the session zone, which the driver
      * pins at UTC. Both spellings denote the same instant.
      */
    def instantLiteral(wallClockUtc: String): String

    /** `SET` statements that change how this engine's simple protocol spells a value it already stores, empty where it has none. These are what
      * make "the server's own rendering" undefined as a target.
      */
    def outputAffectingSettings: Chunk[String]

    /** This engine's statements for moving the session's time zone, or [[Absent]] where it has none, which makes the instant leaves vacuous
      * there rather than unproven.
      *
      * @see
      *   [[SqlTestBackend.SessionZone]]
      */
    def sessionZoneStatements: Maybe[SqlTestBackend.SessionZone]

    /** The kind a `BOOLEAN` declaration reports itself as. Pinned per engine rather than asserted as a disjunction: an engine with a real
      * boolean type must not start reporting the integer unnoticed, and one without reports `Bool` from the declared name alone.
      */
    def booleanColumnKind: SqlRow.ColumnKind

    /** Whether this engine's instant column carries its offset on the wire.
      *
      * The capability behind two mirrored leaves: an engine that carries it can be read from any session zone, and an engine that cannot must
      * have the session pinned at connect instead.
      */
    def instantWireCarriesOffset: Boolean

    /** Whether this engine has a native array column type, as opposed to carrying a collection inside a document type. */
    def hasNativeArrayColumns: Boolean

    /** Whether `upper` and `lower` fold case past ASCII. An engine whose built-in case functions are ASCII-only has no SQL to render
      * instead, and folding client-side would change which rows a predicate over the folded value matches. Both ends need a leaf: an engine
      * that folds only ASCII must still leave the rest untouched rather than mangle it.
      */
    def caseFoldingReachesPastAscii: Boolean

    /** Whether `LIKE` takes its case sensitivity from the COLUMN's collation, which is what makes `like` and `ilike` differ. An engine whose
      * `LIKE` ignores collation cannot be made to agree by declaring the column differently, and a connection-wide setting would decide the
      * question for every column at once. Both ends need a leaf.
      */
    def likeFollowsColumnCollation: Boolean

    /** The DDL column fragment declaring `name` as text bounded to `maxChars`, refusing anything longer. An engine that enforces a declared
      * width spells it as the width; an engine whose declared width is advisory spells the refusal itself. Every engine can honour the
      * claim, so this is DDL rather than a capability flag.
      */
    def boundedTextColumn(name: String, maxChars: Int): String

    /** Whether two write transactions can be OPEN on this engine at the same time. An engine with row-level locking lets both begin; an
      * engine with one writer over the whole database takes the write lock at BEGIN, so a body that synchronises two open write transactions
      * deadlocks there. This says nothing about which loses a write, which the leaves assert of both kinds.
      */
    def allowsConcurrentWriteTransactions: Boolean

    /** Whether this engine has keyed advisory locks, the kind [[SqlClient.withAdvisoryLock]] takes. An engine whose concurrency is one
      * writer over the whole database has no per-key lock to hand out. Each end needs its own leaf: one must exclude a concurrent session,
      * the other must refuse with a typed failure rather than block.
      */
    def hasAdvisoryLocks: Boolean

    /** Whether this engine's time column is a signed SPAN rather than a time of day. Each end has its own leaf, and the flag picks which
      * claim an engine is held to.
      */
    def timeColumnIsSignedSpan: Boolean

    /** Whether this engine has a calendar-interval column type, carrying months and days and seconds at once. */
    def hasCalendarIntervalColumn: Boolean

    /** Whether this engine has a network-address column type. */
    def hasNetworkAddressColumn: Boolean

    /** Whether [[SqlTestBackend.ColumnType.TimeWithOffset]] is a real column here rather than text standing in for one. */
    def hasTimeWithOffsetColumn: Boolean

    /** Whether this engine's numeric and temporal columns hold `NaN` AND the infinities, which the leaf this gates needs together. An
      * engine that holds the infinities while turning NaN into NULL answers `false`; the driver refuses NaN at bind, so that silent loss
      * cannot happen.
      */
    def hasNonFiniteSpecialValues: Boolean

    /** Whether this engine can honour the pinned absent-placement inside a window `ORDER BY` under a `RANGE` frame with a numeric offset.
      *
      * A real capability limit: that frame demands exactly one ordering expression, so an engine that lowers the placement into a second term
      * has nowhere to put it, and no SQL it could render instead.
      */
    def windowRangeOffsetHonoursAbsentPlacement: Boolean

    /** Column types this engine has and this module declines to render, each with a literal. Empty where it has none.
      *
      * A type whose text form is chosen by a session setting the connection is never told has no neutral value to render from. The ARRAY of
      * such a type is a separate entry: an array dispatches its own elements, so the scalar refusal says nothing about it.
      */
    def unrenderableColumns: Chunk[(String, String)]

    /** Array columns worth probing for cross-protocol agreement, as (column type, literal, expected rendering).
      *
      * Choose element types where a passthrough of the server's spelling would DIFFER from the pinned answer; one where they coincide proves
      * nothing.
      */
    def arrayRenderCases: Chunk[(String, String, String)]

    /** Engine-specific columns worth probing for cross-protocol agreement, as (column type, literal, expected rendering).
      *
      * Values only one engine holds, so no cross-ENGINE claim exists; the two wire protocols must still answer alike, which is what fails when
      * a text arm is narrower than its binary twin. Read each off a live server rather than deriving it.
      */
    def protocolAgreementCases: Chunk[(String, String, String)]

    /** The SQLSTATE this engine reports when a statement references a table that does not exist (class 42). */
    def tableNotFoundSqlState: String

    /** The SQLSTATE this engine reports on a unique or primary-key violation (class 23). */
    def uniqueViolationSqlState: String

    /** A SQL EXPRESSION returning the server's identifier for the current session, so a conformance body proves two statements ran on the
      * same connection without naming an engine session function. It is interpolated into a larger statement.
      *
      * [[Absent]] where the engine has no per-session identifier; a constant would be equal on every session and assert nothing. Such an
      * engine proves the same property through visibility instead.
      */
    def sessionIdSql: Maybe[String]

    /** The isolation levels this engine accepts and delivers AT LEAST, so a body asks only for what the engine can deliver.
      *
      * At least, not exactly: running a weaker request at a stronger level is allowed, while quietly substituting a weaker one is the
      * lost-update bug this suite exists to catch. An engine that cannot produce a level must refuse it, which the refusal leaf covers.
      */
    def honouredIsolationLevels: Set[SqlClient.IsolationLevel]

    /** The level a transaction that names none actually runs at here, which decides the anomalies it shows. Engines default differently,
      * and an anomaly leaf reads this rather than assuming.
      */
    def defaultIsolationLevel: SqlClient.IsolationLevel

    /** A statement answering the level the CURRENT transaction is running under, as one text column, so a body can ask the server what it
      * applied rather than trust what was sent. Read through [[SqlTestBackend.canonicalIsolationName]].
      *
      * [[Absent]] where the engine cannot report it, which means "the server applied what was asked" cannot be asserted here rather than that
      * the engine has no isolation. The commits-a-normal-write and refusal leaves cover such an engine.
      */
    def isolationIntrospectionSql: Maybe[String]

    /** Provisions a fresh schema in this backend's shared container and runs `f` against it, dropping the schema on scope exit even when `f`
      * fails.
      */
    def withFreshSchema[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope)

end SqlTestBackend

object SqlTestBackend:

    /** One engine's spelling of the two session-zone moves the instant leaves make. `west` must land the session exactly three hours west of
      * UTC, since that offset is what those leaves assert the reading did not follow; a named zone or a numeric offset both work.
      *
      * @param west
      *   the statement putting this session three hours west of UTC
      * @param utc
      *   the statement putting it back at UTC
      */
    final case class SessionZone(west: String, utc: String) derives CanEqual

    /** Folds an engine's spelling of a level into one comparable name: `read committed` and `READ-COMMITTED` are the same level. */
    def canonicalIsolationName(serverAnswer: String): String =
        serverAnswer.trim.toUpperCase.replace('-', ' ')

    /** The portable column kinds a conformance body asks a descriptor to spell as DDL, so the codec battery names no engine type. Each maps to
      * the engine column type that carries the matching [[kyo.SqlSchema]] wire codec.
      */
    /** `DateTime` is a WALL CLOCK; `Timestamp` is the engine's INSTANT type. One word apart and different things, so a descriptor mapping
      * `Timestamp` to a wall-clock column would pass the instant leaves anyway (the driver pins every session to UTC, which makes the two
      * coincide) while being weaker than the property it tests.
      *
      * Both name a microsecond-capable column for each, since an unqualified temporal column is precision 0 on one engine and 6 on the other.
      */
    enum ColumnType derives CanEqual:
        case SmallInt, Int, BigInt, Numeric, Boolean, Float32, Float64, Bytes, Uuid, Date, Time, TimeWithOffset, DateTime, Timestamp,
            CalendarInterval, Duration, Json, IntArray, TextArray, JsonArray

    /** The connection context for a freshly-provisioned schema: the reachable coordinates plus the ready-to-open `url`, which a conformance
      * body passes straight to `SqlClient.init` so it never assembles an engine-specific URL itself.
      */
    final case class Schema(
        host: String,
        port: Int,
        username: String,
        password: String,
        database: String,
        url: String
    ) derives CanEqual

end SqlTestBackend
