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

    /** The container configuration this backend's shared singleton fixture starts from. */
    def containerConfig: Container.Config

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

    /** The kind a `BOOLEAN` declaration reports itself as. Pinned per engine rather than asserted as a disjunction, so an engine that HAS the
      * type cannot start reporting the integer unnoticed.
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

    /** Whether this engine's time column is a signed SPAN rather than a time of day. Both ends need a leaf and neither engine can run the
      * other's.
      */
    def timeColumnIsSignedSpan: Boolean

    /** Whether this engine has a calendar-interval column type, carrying months and days and seconds at once. */
    def hasCalendarIntervalColumn: Boolean

    /** Whether this engine has a network-address column type. */
    def hasNetworkAddressColumn: Boolean

    /** Whether [[SqlTestBackend.ColumnType.TimeWithOffset]] is a real column here rather than text standing in for one. */
    def hasTimeWithOffsetColumn: Boolean

    /** Whether this engine's numeric and temporal columns hold `NaN` and the infinities. They have no Scala counterpart, so only an engine that
      * accepts them can exercise the rendering.
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

    /** A SQL expression returning the server's identifier for the current session, so a conformance body proves two statements ran on the same
      * connection without naming an engine session function.
      */
    def sessionIdSql: String

    /** A statement answering the level the CURRENT transaction is running under, as one text column, so a body can ask the server what it
      * applied rather than trust what was sent. Read through [[SqlTestBackend.canonicalIsolationName]].
      */
    def isolationIntrospectionSql: String

    /** Provisions a fresh schema in this backend's shared container and runs `f` against it, dropping the schema on scope exit even when `f`
      * fails.
      */
    def withFreshSchema[A, S](f: SqlTestBackend.Schema => A < S)(using
        Frame
    ): A < (S & Async & Abort[SqlException | ContainerException] & Scope)

end SqlTestBackend

object SqlTestBackend:

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
