# kyo-sql: structural cleanup plan

## Purpose

Ten TODO comments were left in the kyo-sql tree as feedback:
`SqlAst.scala:10`, `InsertResult.scala:3`, `SqlCancelHandle.scala:5`,
`SqlClient.scala:1045`, `SqlException.scala:46`, `SqlIsolationLevel.scala:2`,
`SqlNotification.scala:2`, `SqlStatementResult.scala:2` +
`SqlStatementResult.scala:29`, `SqlStatic.scala:7`.

This document is the plan for resolving them, ordered so downstream renames
land against the final names of their upstream dependencies.

## Anchor conventions

Every decision below is checked against these:

- **`/kyo:dev` rubric 6: Exception-hierarchy modeling.** All leaves top-level
  and prefixed; leaf-per-failure-mode with typed fields; message computed
  from typed fields (no free-form `message: String` at the call site);
  `Frame` captured per leaf via `(using Frame)`; `cause: String | Throwable = ""`
  on the base. Every message string is authored inside the exception file;
  call sites hand raw structured data only. Substrate: `kyo-http`
  `HttpException.scala` (four sealed sub-categories, ~20 concrete leaves) and
  `kyo-net` `NetException.scala` (same shape, backend-neutral).
- **No abbreviations in identifier positions.** Curated catalog at
  `~/.claude/plugins/cache/kyo-harness/kyo/0.5.0/skills/dev/catalogs/detectors/abbreviation.md`
  bans `cfg`, `ctx`, `mgr`, `req`, `resp`, `idx`, `buf`, `msg`, `tpe`, `acc`,
  `str`, `bldr`, `ref`, `hdr`, `ptr`, `svc`, `proc`, and similar. `Pg` /
  `My` / `MySql` are not on that list textually but violate the same rule:
  spell them out as `Postgres` / `Mysql`. Prior art in the codebase already
  spells the values out (`SqlBackend.Postgres`, `SqlBackend.Mysql`).
- **Failures are modeled as `Result` / `Abort`.** A `Success`/`Failure` sum
  type on the result element is redundant when `Chunk[Result[E, A]]` already
  says the same thing with the standard vocabulary.
- **One file = one type + its companion.** Auxiliary types nest under the
  primary type's companion; separate `.scala` files hold separate primary
  types. Non-companion siblings (e.g. `GeneratedKey` next to `InsertResult`)
  either move into the companion or move to their own file.
- **Nothing user-facing lives in `kyo.internal.*`.** Internal is
  implementation-only.

## Items, ordered

### 1. Remove `SqlStatic`

`SqlStatic.staticSql(q)` returns compile-time-rendered `Rendered(sql:
BackendSql, params: Chunk[BoundValue[?]])`: the SQL text and params as a
compile-time constant, without executing. Every use site in the tree is a
test in `SqlStatic*Test.scala`; no production caller. `.runStatic` on
`Query` / `Insert` / `Update` / `Delete` covers the execute case. Runtime
`.renderPostgres` / `.renderMysql` on `SqlAst.SqlAst` covers the inspect
case.

**Action:** delete `SqlStatic.scala`. `SqlStatic.staticSql`,
`SqlStatic.Rendered`, and `SqlStatic.BackendSql` all disappear. Tests
using `SqlStatic.staticSql(q)` migrate to `q.renderPostgres` and
`q.renderMysql` (returning runtime `Sql.Rendered` instead of compile-time
`SqlStatic.Rendered`). Assertions on the two backends read both renders.
Macro-emitted call sites (SqlRunMacro / SqlStaticMacro that lift
`SqlStatic.Rendered`) inline the type into their local shape or move it
to `kyo.internal.SqlBackendSql` if a macro-facing type is still needed.

### 2. `InsertResult`: out of the `kyo` package, into `SqlClient` companion (and rename to `InsertOutcome`)

Currently `kyo.InsertResult` (case class) + `kyo.GeneratedKey` (enum
sibling in the same file). Neither belongs at the top level. The type
name also violates the "no `Result` types in kyo-sql" rule (`kyo.Result`
is the framework's failure sum; a domain type named `Result` conflicts
with it in scope and misreads at every call site).

**Action:**

```scala
object SqlClient:
    final case class InsertOutcome(
        affectedRows: Long,
        generatedKey: InsertOutcome.GeneratedKey
    ) derives CanEqual

    object InsertOutcome:
        enum GeneratedKey derives CanEqual:
            case Value(key: Long)
            case NoAutoKey
            case Unavailable

        object GeneratedKey:
            def isPresent(gk: GeneratedKey): Boolean = ...
            inline def foldKey[A](gk: GeneratedKey)(ifAbsent: => A)(f: Long => A): A = ...
```

Delete `InsertResult.scala`. `SqlClient.InsertOutcome` is the return
type of `.run` on `Insert`. Callers refer to it via
`SqlClient.InsertOutcome` or `import SqlClient.InsertOutcome`.

### 3. Nest simple types under the producer's companion

Same pattern as `SqlClient.PipelineBuilder` / `SqlClient.Metrics` (already
done earlier in this PR).

| Current | New | Rationale |
|---|---|---|
| `kyo.SqlCancelHandle` (sealed + `Pg` / `My` variants) | `SqlClient.CancelHandle` (sealed + `Postgres` / `Mysql` variants) | Only reachable via `client.cancellableQuery` / `client.cancel(handle)`. Nested variants get their full names as part of Item 4. |
| `kyo.SqlIsolationLevel` (enum) | `SqlClient.IsolationLevel` | Used exclusively at `client.transaction(isolation = ...)`. |
| `kyo.SqlNotification` (case class) | `SqlClient.Postgres.Notification` | Postgres-only feature (`LISTEN` / `NOTIFY`); belongs on the Postgres-specific client class introduced by Item 6. |

All three delete their standalone `.scala` files.

### 4. Backend-abbreviated identifiers → full names

Prior art: `SqlBackend.Postgres`, `SqlBackend.Mysql` (already spelled out).
The rest of the module is inconsistent: sweep it.

Types:

| Current | New |
|---|---|
| `PgSqlClient` | `SqlClient.Postgres` (Item 6 nests it) |
| `MySqlSqlClient` | `SqlClient.Mysql` |
| `SqlCancelHandle.Pg` | `SqlClient.CancelHandle.Postgres` |
| `SqlCancelHandle.My` | `SqlClient.CancelHandle.Mysql` |
| `TransactionContext.Pg` | `TransactionContext.Postgres` |
| `TransactionContext.My` | `TransactionContext.Mysql` |
| `PgSqlClientBackend` (internal) | `kyo.internal.SqlClientBackend.Postgres` |
| `MySqlClientBackend` (internal) | `kyo.internal.SqlClientBackend.Mysql` |

Methods on `SqlClient` companion:

| Current | New |
|---|---|
| `initMy` / `initMyWith` / `useMy` / `initMyUnscoped` / `initMyUnscopedWith` | `initMysql` / `initMysqlWith` / `useMysql` / `initMysqlUnscoped` / `initMysqlUnscopedWith` |

Internal helpers: `queryMysql`, `executeMysql`, `boundToMysql`,
`mysqlRowToRow` and similar keep their `Mysql` spelling (already full).
Internal `Pg*` / `My*` short forms already present in the tree get
renamed too: `private[kyo] def queryPgFragment` and similar.

### 5. Restructure the pipeline result via `kyo.Result`

Current:

```scala
sealed abstract class SqlStatementResult
object SqlStatementResult:
    final case class Success(rows: Chunk[SqlRow], affectedRowCount: Long) extends SqlStatementResult
    final case class Failure(error: SqlException) extends SqlStatementResult

def pipeline(...): Chunk[SqlStatementResult] < ...
```

`Failure(error)` is a `Result.Failure(error)` rewritten as a nominal class.
Kyo's convention is to reach for `kyo.Result` for the same shape.

Target:

```scala
object SqlClient:
    object PipelineBuilder:
        final case class Outcome(
            rows: Chunk[SqlRow],
            affectedRowCount: Long
        ) derives CanEqual

def pipeline(...): Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]] < ...
```

The success payload is `SqlClient.PipelineBuilder.Outcome`, not
`Result`: the domain type must not shadow or collide with `kyo.Result`.
Callers pattern-match `Result.Success` / `Result.Failure` (already the
standard idiom elsewhere in kyo), never a bespoke `Success` / `Failure`
sum. Delete `SqlStatementResult.scala`.

### 6. Nest `PgSqlClient` / `MySqlSqlClient` under `SqlClient`

Currently three top-level types (`SqlClient`, `PgSqlClient`,
`MySqlSqlClient`) share `SqlClient.scala`. Move the two concrete classes
into the `SqlClient` companion.

```scala
sealed abstract class SqlClient:
    val backend: SqlClientBackend
    val url: SqlConfig.Url
    val config: SqlConfig
    val closedRef: AtomicBoolean
    // ... shared methods ...

object SqlClient:
    final class Postgres(...) extends SqlClient:
        // Postgres-specific: copyIn, copyOut, cancellableQuery,
        // cancellableQueryFiber, parameters, simpleQuery,
        // Notification (Item 3), ...

    final class Mysql(...) extends SqlClient:
        // MySQL-specific: cancellableQuery, cancellableQueryFiber,
        // loadLocalInfile, ...

    // Rest of the companion (init, initWith, use, IsolationLevel,
    // CancelHandle, InsertOutcome, Metrics, PipelineBuilder) ...
```

`SqlClient.scala` stays one file: primary type `SqlClient` + its companion
holding all nested types. "One file = one type + its companion" is
preserved: `SqlClient` is the type; everything else is companion content.

### 7. Move DSL builders into producer-type companions

Rejected: a single `SqlBuilder` or `SqlBatch` mega-object holding every
builder. That's a namespace catch-all with no invariant tying its members
together, and `SqlBatch` collides with the domain term "batch execution"
(sending N statements in one round-trip: different concept).

Adopted: each builder lives in the companion of the type it PRODUCES,
inside `SqlAst`. Where a builder chain has no single produced AST node
(the CASE / WHEN chain, whose steps produce further steps and terminate
at `Term[A]`), the members share a namespace-marker object named for the
DSL vocabulary the caller uses (`Sql.when(...)` → `SqlAst.Case.*`):

| Current | New |
|---|---|
| `SqlAst.InsertBuilder[T, F]` | `SqlAst.Insert.Builder[T, F]` |
| `SqlAst.OnConflictDoUpdateBuilder[T, F]` | `SqlAst.Insert.OnConflict.DoUpdateBuilder[T, F]` |
| `SqlAst.UpdateBuilder[T, F]` | `SqlAst.Update.Builder[T, F]` |
| `SqlAst.UpdateReturningBuilder[T, F]` | `SqlAst.Update.ReturningBuilder[T, F]` |
| `SqlAst.DeleteBuilder[T, F]` | `SqlAst.Delete.Builder[T, F]` |
| `SqlAst.DeleteReturningBuilder[T, F]` | `SqlAst.Delete.ReturningBuilder[T, F]` |
| `SqlAst.JoinOnBuilder[T1, F1, T2, F2]` | `SqlAst.Join.OnBuilder[T1, F1, T2, F2]` |
| `SqlAst.WhenBuilder` | `SqlAst.Case.WhenBuilder` |
| `SqlAst.CaseBuilder[A]` | `SqlAst.Case.Builder[A]` |
| `SqlAst.WhenContinuation[A]` | `SqlAst.Case.WhenContinuation[A]` |
| `SqlAst.FromBuilder[T]` | `SqlAst.Table.Builder[T]` (produces `Table[T, F]`) |
| `SqlAst.NestedBuilder[T]` | `SqlAst.Nested.Builder[T]` (produces `Nested[T, F]`) |
| `SqlAst.LateralBuilder[T]` | `SqlAst.Lateral.Builder[T]` (produces `Lateral[T, F]`) |
| `SqlAst.ValuesBuilder[T]` | `SqlAst.ValuesFrom.Builder[T]` (produces `ValuesFrom[T, F]`) |
| `SqlAst.WindowSpecBuilder` | `SqlAst.WindowSpec.Builder` |

Every builder is user-facing (the DSL threads them through `Sql.from[T]`,
`Sql.insert[T]`, `Sql.update[T]`, `Sql.delete[T]`, `Sql.when(pred)`,
`Sql.windowSpec`, and the returning / on-conflict continuations); no
builder moves to `kyo.internal.*`.

All builders stay in `SqlAst.scala` alongside their producing type's
companion. No `SqlAst<TypeName>Builders.scala` split files: the builder
is part of its type's construction surface and separating them adds a
navigation hop for no invariant gain.

### 8. Flat `SqlException` hierarchy (target of item 5's TODO)

Rewrite `SqlException.scala` following the `HttpException.scala` /
`NetException.scala` substrate:

- `SqlException` (sealed abstract): base, extends `KyoException`, `cause:
  String | Throwable = ""`.
- Sealed abstract sub-categories at top level: `SqlConnectionException`,
  `SqlRequestException`, `SqlServerException`, `SqlDecodeException`,
  `SqlUnsupportedException`.
- Concrete leaves at top level, one per failure mode. Structured typed
  fields; message built from them inside the file. `(using Frame)` on
  every leaf. Nothing in `object SqlException`: every type is a
  top-level `sealed abstract class` or `final case class`.

Additionally: **every failure raised from `kyo-sql` main code becomes an
`SqlException` leaf.** Any `throw new SomeException(msg)`,
`Abort.fail(SomeOtherException(...))`, `bug(...)`, or ad-hoc
`RuntimeException` currently escaping the module gets routed to the
appropriate `Sql<Category><Detail>Exception`. The exception catalog below
enumerates every raise site and the leaf it collapses into.

Cross-category classification via marker traits, spelled out in the
"Trait taxonomy" section below. Callers `Abort.recover[SqlRetryable]`
etc. for property-based recovery without spelling out each leaf.

**Catalog.** Enumerated by a subagent over `kyo-sql/{shared,jvm}/src/main`.
Totals: 376 `SqlException.*` construction sites (+1 JVM), 9 non-`SqlException`
throws that collapse into new leaves, 26 `bug()` invariant sites (see
note below).

Grouped by sub-category:

#### `SqlConnectionException` (leaves)

| Leaf | # sites | Fields |
|---|---|---|
| `SqlConnectionUrlParseException` | 9 | `rawUrl: String, scheme: String` |
| `SqlConnectionConnectFailedException` | 10 | `host: String, port: Int, cause: Throwable` |
| `SqlConnectionSslRequestFailedException` | 7 | `host: String, port: Int, responseByte: Byte` |
| `SqlConnectionTlsNotAdvertisedException` | 1 | `host: String, port: Int` |
| `SqlConnectionTlsConfigException` | 2 | `sslMode: String` |
| `SqlConnectionPoolClosedException` | 8 | (none) |
| `SqlConnectionAcquireTimeoutException` | 4 | `acquireTimeout: Duration` |
| `SqlConnectionEstablishTimeoutException` | 2 | `timeout: Duration, host: String, port: Int` |
| `SqlConnectionQueryTimeoutException` | 2 | `queryTimeout: Duration` |
| `SqlConnectionCancelTimeoutException` | 2 | `cancelTimeout: Duration` |
| `SqlConnectionWarmupPanicException` | 2 | `cause: Throwable` |
| `SqlConnectionClosedException` | 10 | `phase: String` |
| `SqlConnectionWritePanicException` | 10 | `cause: Throwable` |
| `SqlConnectionProtocolCorruptedException` | 2 | `operation: String` (COPY / LOAD DATA) |
| `SqlConnectionProtocolDecodeException` | ~40 | `packetType: String, cause: Throwable` |
| `SqlConnectionUnexpectedMessageException` | ~35 | `phase: String, expected: String, actual: String` |
| `SqlConnectionUnsupportedAuthMethodException` | 3 | `mechanism: String` |
| `SqlConnectionUnsupportedAuthPluginException` | 1 | `pluginName: String` |
| `SqlConnectionScramFailedException` | 3 | `reason: String` |
| `SqlConnectionAuthenticationFailedException` | 1 | `sqlState: String, errorCode: Int, serverMessage: String` |
| `SqlConnectionClearPasswordRequiresTlsException` | 2 | (none) |
| `SqlConnectionCachingSha2EmptyPayloadException` | 1 | (none) |
| `SqlConnectionUnexpectedSentinelException` | 3 | `context: String` |
| `SqlConnectionNotificationPanicException` | 1 | `cause: Throwable` |
| `SqlConnectionTypeLookupMissingException` | 1 | `missingTypes: Chunk[String]` |
| `SqlConnectionInvalidTypeNameException` | 1 | `typeNames: Chunk[String]` |
| `SqlConnectionResetFailedException` | 1 | `errorCode: Int, errorMessage: String` |
| `SqlConnectionBackendMismatchException` | 22 | `requiredDriver: SqlBackend, activeDriver: SqlBackend, operation: String` |
| `SqlConnectionNoActiveClientException` | 4 | (none) |

#### `SqlRequestException` (leaves)

| Leaf | # sites | Fields |
|---|---|---|
| `SqlRequestMysqlTxRequiresConnectionApiException` | 6 | `operation: String` |
| `SqlRequestRsaOaepException` | 10 (incl. JVM) | `position: String, tag: String, cause: Throwable` |
| `SqlRequestMysqlGetLockException` | 1 | `name: String, timeoutSeconds: Int, lockKey: String` |
| `SqlRequestDurationOverflowException` | 1 | `totalDays: Long` **(absorbs `throw new ArithmeticException` at MysqlEncoder.scala:251)** |

#### `SqlServerException` (leaves: split by SQL-state family)

| Leaf | SQL-state | Fields |
|---|---|---|
| `SqlServerConstraintViolationException` | `23xxx` | `sqlState, severity, detail, hint, position, extra, sqlText, paramCount, connectionId` |
| `SqlServerDeadlockException` | `40001`, `40P01` | same field set + `SqlRetryable` mix-in |
| `SqlServerSyntaxException` | `42xxx` | same |
| `SqlServerConnectionException` | `08xxx` | same |
| `SqlServerErrorException` | fallback | same |

Constructed via a single factory in the exception file that dispatches on
`sqlState` prefix; the 4 current raise sites
(`QueryResultExchange:155`, `MysqlConnection:345`, `MysqlErrors:23`,
`LocalInfileExchange:267`) call the factory unchanged.

#### `SqlDecodeException` (leaves)

| Leaf | # sites | Fields |
|---|---|---|
| `SqlDecodeInsufficientBytesException` | 29 | `typeName: String, expected: Int, actual: Int, position: Int` |
| `SqlDecodeColumnNullException` | 7 | `columnIndex: Int` or `columnName: String` |
| `SqlDecodeColumnOutOfBoundsException` | 2 | `columnIndex: Int, columnCount: Int` |
| `SqlDecodeColumnNotFoundException` | 2 | `columnName: String` |
| `SqlDecodeColumnDecodeException` | 5 | `columnIndex: Int, cause: Throwable` |
| `SqlDecodeArrayNullElementException` | 16 | `scalaType: String, arrayIndex: Int` |
| `SqlDecodeArrayFormatException` | 6 | `ndim: Int, length: Int, offset: Int` |
| `SqlDecodeHstoreFormatException` | 9 | `count: Int, keyLength: Int, valueLength: Int, offset: Int` |
| `SqlDecodeJsonException` | 7 | `jsonPreview: String, cause: Throwable` |
| `SqlDecodeSumTypeUnknownLabelException` | 1 | `label: String, validLabels: Chunk[String]` |
| `SqlDecodeEmptyStringForCharException` | 3 | `columnIndex: Int` |
| `SqlDecodeNumericException` | 7 | `text: String, subtype: NumericSubtype` (`NaN`/`PosInf`/`NegInf`/`Parse`) |
| `SqlDecodeIntervalException` | 5 | `field: String, value: String` |
| `SqlDecodeInetException` | 4 | `typeName: String, family: Int, addressLength: Int, byteSize: Int` |
| `SqlDecodeUuidException` | 1 | `byteSize: Int` |
| `SqlDecodeInstantException` | 1 | `text: String, cause: Throwable` |
| `SqlDecodeDurationException` | 1 | `text: String, cause: Throwable` |
| `SqlDecodeTemporalException` | 15 | `year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int, structLength: Int` |
| `SqlDecodeScramFormatException` | 5 | `field: String, text: String` |
| `SqlDecodeUnknownAuthTypeException` | 1 | `subType: Int` |
| `SqlDecodeUnknownBackendMessageException` | 1 | `messageByte: Byte` |
| `SqlDecodeProtocolFormatException` | 6 | `messageByte: Byte, position: Int` |

#### `SqlUnsupportedException` (leaves)

| Leaf | # sites | Fields |
|---|---|---|
| `SqlUnsupportedMysqlVersionFeatureException` | 3 + 3 collapsed | `feature: String, requiredVersion: String, serverVersion: String` **(absorbs 3 `throw new IllegalStateException` at SqlRender:334,365,406)** |
| `SqlUnsupportedReturningOnMysqlException` | 3 + 3 collapsed | (none) **(absorbs 3 `throw new IllegalStateException` at SqlRender:851,959,984)** |
| `SqlUnsupportedUpsertWhereClauseOnMysqlException` | 1 + 1 collapsed | (none) **(absorbs 1 `throw new IllegalStateException` at SqlRender:927)** |
| `SqlUnsupportedStructuralReadException` | 1 | `methodName: String` **(reclassified from Decode → Unsupported: BufferedSqlReader:187)** |

#### Trait taxonomy

Three cross-cutting properties as marker traits. Each leaf's `extends`
list is fixed at the row shown below; there is no per-caller
customization.

| Trait | Mixed into |
|---|---|
| `SqlRetryable` | `SqlServerDeadlockException`, `SqlConnectionAcquireTimeoutException`, `SqlConnectionEstablishTimeoutException`, `SqlConnectionQueryTimeoutException`, `SqlConnectionCancelTimeoutException`, `SqlConnectionConnectFailedException` |
| `SqlIntegrityViolation` | `SqlServerConstraintViolationException` |
| `SqlAuthenticationFailure` | `SqlConnectionAuthenticationFailedException`, `SqlConnectionScramFailedException`, `SqlConnectionUnsupportedAuthMethodException`, `SqlConnectionUnsupportedAuthPluginException`, `SqlConnectionClearPasswordRequiresTlsException`, `SqlConnectionCachingSha2EmptyPayloadException` |

Callers recover on the trait when they care about the property, on the
leaf when they need the fields. No further traits ship in this PR; new
ones require a leaf touched by two callers with the same recovery
predicate.

#### `bug()` sites

The 26 `bug(...)` sites at
`SqlSchema.scala:172,187,247,252,285,290,330,344,359,373,396,409,428,441,590,603,625,650,672,697,724,738,758,782,813,826`,
`SqlClient.scala:203`, and `internal/auth/RsaOaep.scala:235` **stay as
`bug()`**. They are pattern-match "impossible" branches guarding
schema-derivation invariants (`SqlSchema[X] cannot read/write from
${x.getClass}`, `Unknown driver`, `RSA-OAEP I2OSP: integer too large`).
`KyoBugException` is the framework-wide signal for compiler-blessed
unreachable code, treated as an assertion violation, not a runtime SQL
failure. The "all failures raised from `kyo-sql` main code are
`SqlException`" rule targets runtime failure paths, not invariant
assertions; folding these into `SqlException` would misrepresent what
they are and encourage callers to catch them.

### 9. Split `SqlAst.scala`

Current file: 1300+ lines carrying the sealed AST hierarchy, all
builders, and the `internal` sub-object (`buildColumns`,
`buildRowColumns`, `resolveSqlName`, `type RecordF[R]`, ...). The
`internal` name is misleading: `RecordF` leaks into user-visible
inferred types via `InsertBuilder[T, F]` field types.

**Actions (fixed):**

1. **Promote user-visible types out of `internal`.** `type RecordF[R]`
   moves from `SqlAst.internal.RecordF` to `SqlAst.RecordF`. It appears
   in inferred variable types at every DSL call site; the `internal.`
   prefix is a lie. Update every reference (`Sql.scala:39,40,50,51,55,56`
   plus SqlAst-internal callers) to the new path.
2. **Move genuine macro helpers to `kyo.internal.SqlAstInternal`.**
   `buildColumns[T, N]`, `buildRowColumns[T]`, `resolveSqlName`, and any
   other macro-emission-facing scaffolding move to
   `kyo/internal/SqlAstInternal.scala`. Not user API; `kyo.internal.*`
   is the correct home. Update the macro emitters
   (`kyo.internal.SqlMacros`, `kyo.internal.SqlRunMacro`,
   `kyo.internal.SqlStaticMacro`) to emit `_root_.kyo.internal.SqlAstInternal.X`
   in place of the current `_root_.kyo.SqlAst.internal.X`.
3. **Keep the core sealed hierarchy** (`SqlAst`, `Executable`, `Term`,
   `Column`, `Query`, `Action`, `Insert`, `Update`, `Delete`, `Values`,
   `Fragment`, plus their case-class arms and per-Item-7 builders in
   companions) **in `SqlAst.scala`**. Scala 3 `sealed` requires it;
   post-split size lands at ~1000 lines (~300 line reduction from
   pulling out `internal` and promoting `RecordF`).
4. **Delete `SqlAst.internal`** once (1) and (2) land. Nothing left in
   it.

## Execution order

Downstream renames depend on upstream names. Do them in this order:

1. **Item 1** (delete `SqlStatic`): independent.
2. **Item 4** partial rename (types + methods only, not yet nested):
   `PgSqlClient` → `PostgresSqlClient`, `MySqlSqlClient` → `MysqlSqlClient`,
   `SqlCancelHandle.Pg` → `.Postgres`, `TransactionContext.Pg` →
   `.Postgres`, `initMy*` → `initMysql*`. Names stay top-level at this
   step; only the abbreviations die. Doing the rename first minimizes
   churn in the later nest-into-companion steps.
3. **Item 6**: nest `PostgresSqlClient` → `SqlClient.Postgres`,
   `MysqlSqlClient` → `SqlClient.Mysql` (fold Item 4's rename into the
   nest).
4. **Item 3**: nest `SqlCancelHandle`, `SqlIsolationLevel`,
   `SqlNotification` under `SqlClient` (using the now-final
   `SqlClient.Postgres` name where relevant).
5. **Item 2**: nest and rename `InsertResult` → `SqlClient.InsertOutcome`.
6. **Item 5**: replace `SqlStatementResult` with
   `Chunk[Result[SqlException, SqlClient.PipelineBuilder.Outcome]]`.
7. **Item 7**: move DSL builders into producer-type companions.
8. **Item 9**: split `SqlAst.scala` (extract `internal.*` sub-object;
   post-Item-7 the file is already smaller).
9. **Item 8**: flat `SqlException` rewrite. Dedicated commit series; the
   biggest change and standalone from the structural nests.

Each item is a single commit (or a small commit series where the catalog
naturally splits, e.g. Item 4's rename can be one commit per type family).

## Anti-scope

Explicitly not doing under this plan:

- Renaming to `PostgreSQL` / `MySQL` (mixed case). Prior art
  (`SqlBackend.Postgres`, `SqlBackend.Mysql`) is `Postgres` / `Mysql`;
  match it.
- Keeping any nested `SqlException.Xxx` variant for backwards compat
 (the flat rewrite replaces the current shape wholesale).
- Extracting DSL types beyond the SqlAst tree (naming strategy,
  isolation level, etc.): those are handled by the individual items
  above.
- Moving `SqlSchema` / `Sql` / other core types: untouched.
