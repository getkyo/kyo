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

    /** The container configuration this backend's shared singleton fixture starts from, or [[Absent]] for an engine with nothing to
      * provision.
      *
      * [[Absent]] is not "no container configured yet"; it is the statement that this backend needs no daemon, which is what makes
      * [[reachable]] answer true for it on a host that has none.
      */
    def containerConfig: Maybe[Container.Config]

    /** Whether this descriptor can actually be exercised on this host.
      *
      * Separate from being registered, because the two fail for different reasons: a descriptor is registered when the classpath carries
      * it, and reachable when the environment can serve it. A server engine needs a container daemon; an embedded one needs nothing, so
      * it is reachable wherever the test JVM runs.
      *
      * Derived rather than abstract, so a backend answers it by saying whether it has a container rather than by reasoning about hosts. A
      * backend whose reachability is neither of these overrides it.
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

    /** `SET` statements that change how this engine's simple protocol spells a value it already stores, empty where it has none. These are what
      * make "the server's own rendering" undefined as a target.
      */
    def outputAffectingSettings: Chunk[String]

    /** This engine's statements for moving the session's time zone, or [[Absent]] where it has no session zone to move.
      *
      * The two instant leaves need to shift a session and read the same instant back, and each engine spells that its own way. Absent is not
      * a gap: an engine with no session zone has nothing that could shift the reading, so the property is vacuous there rather than unproven.
      *
      * @see
      *   [[SqlTestBackend.SessionZone]]
      */
    def sessionZoneStatements: Maybe[SqlTestBackend.SessionZone]

    /** The kind a `BOOLEAN` declaration reports itself as.
      *
      * Pinned per engine rather than asserted as a disjunction, for two different reasons depending on the engine. One that HAS a boolean
      * type must not start reporting the integer unnoticed. One that has no boolean type at all reports `Bool` on the strength of the
      * declared NAME, which is a claim about its own type mapping rather than about the engine, and pinning it is what keeps that claim
      * honest.
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

    /** Whether `upper` and `lower` fold case past ASCII.
      *
      * A real capability limit rather than a driver gap: an engine whose built-in case functions are ASCII-only has no SQL the driver could
      * render instead, and the mapping is per-character and locale-sensitive, so doing it client-side would change which rows a predicate
      * over the folded value matches. Both ends need a leaf, since an engine that folds only ASCII must still leave the rest UNTOUCHED
      * rather than mangle it.
      */
    def caseFoldingReachesPastAscii: Boolean

    /** Whether `LIKE` takes its case sensitivity from the COLUMN's collation.
      *
      * The engines that do are made case-sensitive at the column, so `like` and `ilike` mean different things there. An engine whose `LIKE`
      * ignores collation entirely cannot be made to agree by declaring the column differently, and a connection-wide setting is not the same
      * property: it would decide the question for every column at once, including one declared case-insensitive on purpose.
      *
      * Both ends need a leaf. The engine that does not follow the column still has a definite behaviour, and pinning it is what catches a
      * change to it.
      */
    def likeFollowsColumnCollation: Boolean

    /** The DDL column fragment declaring `name` as text bounded to `maxChars`, refusing anything longer.
      *
      * An engine that enforces a declared width spells it as the width; an engine whose declared width is ADVISORY has to spell the refusal
      * itself, which is the difference this absorbs. It is a descriptor member rather than a capability flag on purpose: the claim the leaf
      * makes, that over-long text is refused and never truncated, is one every engine CAN honour, and only the DDL differs.
      */
    def boundedTextColumn(name: String, maxChars: Int): String

    /** Whether two write transactions can be OPEN on this engine at the same time.
      *
      * An engine with row-level locking lets both begin and only contends when they touch the same row; an engine with one writer over the
      * whole database takes the write lock at BEGIN, so the second transaction cannot start until the first ends. A body that synchronises
      * two open write transactions against each other can only run on the first kind: on the second the rendezvous can never be reached,
      * because one of the parties is waiting for a lock the other will not release until it arrives.
      *
      * This says nothing about which loses a write. That is what the leaves assert, and both kinds are held to it.
      */
    def allowsConcurrentWriteTransactions: Boolean

    /** Whether this engine has keyed advisory locks, the kind [[SqlClient.withAdvisoryLock]] takes.
      *
      * A real capability limit rather than a gap in a driver: an engine whose concurrency is one writer over the whole database has no
      * per-key lock to hand out, so there is nothing for the acquire to do but refuse. The two ends need different leaves, and neither can
      * run the other's: an engine that has them must exclude a concurrent session, and one that does not must say so with a typed failure
      * instead of blocking forever.
      */
    def hasAdvisoryLocks: Boolean

    /** Whether this engine's time column is a signed SPAN rather than a time of day.
      *
      * Both ends need a leaf, and an engine with a real time-of-day type cannot run the span one. An engine whose time column holds whatever
      * the driver renders into it can run both, so the flag picks which claim it is held to rather than describing something it cannot do.
      */
    def timeColumnIsSignedSpan: Boolean

    /** Whether this engine has a calendar-interval column type, carrying months and days and seconds at once. */
    def hasCalendarIntervalColumn: Boolean

    /** Whether this engine has a network-address column type. */
    def hasNetworkAddressColumn: Boolean

    /** Whether [[SqlTestBackend.ColumnType.TimeWithOffset]] is a real column here rather than text standing in for one. */
    def hasTimeWithOffsetColumn: Boolean

    /** Whether this engine's numeric and temporal columns hold `NaN` and the infinities. They have no Scala counterpart, so only an engine
      * that accepts them can exercise the rendering.
      *
      * One AND, and an engine may split it: holding the infinities while turning NaN into NULL, which is silent data loss rather than a
      * refusal. `false` is the answer for such an engine, since the leaf this gates needs both, and the driver refuses NaN at bind so the
      * loss cannot happen quietly.
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

    /** A SQL EXPRESSION returning the server's identifier for the current session, so a conformance body proves two statements ran on the same
      * connection without naming an engine session function. An expression rather than a statement: it is interpolated into a larger one.
      *
      * [[Absent]] where the engine has no per-session identifier to ask for. That is not a gap to paper over with a literal: a constant would
      * be the same on every session, so the leaf comparing it against the pinned connection would assert nothing it could fail. Such an engine
      * proves the same property through visibility instead.
      */
    def sessionIdSql: Maybe[String]

    /** The isolation levels this engine accepts and delivers AT LEAST, so a body asks only for what the engine can deliver.
      *
      * At least, not exactly: an engine that runs a weaker request at a stronger level harms nobody, so that level belongs in this set. What
      * the contract forbids is the other direction, and an engine with no way to produce a level must REFUSE it rather than substitute,
      * because asking for one level and quietly getting a weaker one is the lost-update bug this suite exists to catch. The levels outside
      * this set are covered by the refusal leaf instead of the runs-and-commits ones.
      */
    def honouredIsolationLevels: Set[SqlClient.IsolationLevel]

    /** The level a transaction that names none actually runs at here.
      *
      * Which anomalies an unnamed transaction shows follows entirely from this, so an anomaly leaf reads it instead of assuming every engine
      * defaults alike. Two engines pinning different defaults is not a disagreement to fix; it is the fact a caller needs to know.
      */
    def defaultIsolationLevel: SqlClient.IsolationLevel

    /** A statement answering the level the CURRENT transaction is running under, as one text column, so a body can ask the server what it
      * applied rather than trust what was sent. Read through [[SqlTestBackend.canonicalIsolationName]].
      *
      * [[Absent]] where the engine has no way to report it. That is not the same as having no isolation: it means the assertion "the server
      * applied what was asked" cannot be made here, and answering a LITERAL instead would be the test comparing a string it wrote itself,
      * which agrees with itself by construction. Such an engine is covered by the commits-a-normal-write leaf and the refusal leaf.
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

    /** One engine's spelling of the two session-zone moves the instant leaves make.
      *
      * `west` must land the session exactly three hours west of UTC, because that offset is what those leaves assert the reading did NOT
      * follow. How it gets there, a named zone or a numeric offset, is the engine's business.
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
