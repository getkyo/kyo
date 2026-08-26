<!-- doctest:setup
```scala
import kyo.*
import kyo.net.NetTlsConfig

// Compile-only stand-ins. Doc blocks are type-checked and never executed, so nothing below is ever
// dereferenced. `url` is a runtime value on purpose: a string literal would resolve its scheme at compile
// time and need the engine artifact on the classpath, which the examples do not assume.
val client: SqlClient = ???
val url: String       = ???
```
-->

# kyo-sql

Kyo's SQL module supports both raw SQL and typed queries. The two build the same AST and run through
the same execution path, so a raw fragment embeds in a typed query and a typed query embeds in raw SQL,
and the typed DSL mirrors SQL syntax rather than translating collection operations: what you write keeps
the shape of the statement it renders to.

Every driver implements its server's wire protocol directly on Kyo's async network stack, with no JDBC
underneath. A statement suspends a fiber rather than blocking a thread, and the whole implementation,
protocols included, is one set of shared sources compiled for the JVM, Scala.js, Scala Native, and Wasm, so
every feature works the same on all four platforms. PostgreSQL and MySQL drivers ship with the module, each
exposing its engine's own capabilities (`COPY`, `LISTEN`/`NOTIFY`, `LOAD DATA LOCAL INFILE`) alongside the
portable surface, and the URL scheme selects among the drivers a build depends on.

Typed queries can be generated at compile time through ordinary Scala 3 inlining, with no annotation, build
step, or return type annotation restrictions. The SQL string lands in the artifact as a constant avoiding runtime
overhead.

## Getting started

```scala doctest:expect=skipped
libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-sql"          % "<latest version>",
    "io.getkyo" %% "kyo-sql-postgres" % "<latest version>",
    "io.getkyo" %% "kyo-sql-mysql"    % "<latest version>"
)
```

One import covers the surface: `import kyo.*`. A first program, end to end:

```scala
case class Ping(id: Long, note: String) derives SqlSchema

val program: Chunk[Ping] < (Async & Abort[SqlException]) =
    DB.run(url) {
        for
            _    <- sql"INSERT INTO ping VALUES (1, 'hello')".execute
            rows <- sql"SELECT id, note FROM ping".as[Ping].run
        yield rows
    }
```

`DB.run(url)` opens a connection pool, supplies it to every statement inside, and closes it when the block
ends. The `url` is an ordinary runtime `String`. `"postgres://user:pass@host:5432/app"` runs the program
against PostgreSQL; swapping in `"mysql://user:pass@host:3306/app"` runs it against MySQL, because the engine
is chosen by the URL, not by the code. For a URL written as a
literal, the scheme is resolved while compiling, and a scheme no driver on the classpath claims is a build
warning listing the schemes that are available (a warning rather than an error, since a backend registered
at startup can still claim it).

The `DB` in each statement's row is the database dependency, visible in the type: a computation that talks
to the database says so, and it cannot run until a `DB.run` says which database that is. There is no session
factory, no execution context, and no client value to thread. Most programs never hold a client. The cases
that need one are covered in [Holding a client](#holding-a-client).

The examples from here on run against a small billing domain:

```scala doctest:setup
enum InvoiceStatus derives SqlSchema.Column:
    case Pending, Paid, Cancelled

case class Customer(
    id: Long,
    email: String,
    @column("signed_up_at") signedUpAt: Instant
) derives SqlSchema

case class Invoice(
    id: Long,
    @column("customer_id") customerId: Long,
    total: BigDecimal,
    status: InvoiceStatus,
    note: Maybe[String]
) derives SqlSchema
```

`derives SqlSchema` makes a case class a row type, an enum stores itself by variant label with
`derives SqlSchema.Column`, and `@column` pins a SQL name where it differs from the field's. Those three
declarations carry every example below. Rows, names, and column types are covered in detail in
[their own section](#rows-names-and-column-types).

## Raw SQL

Everything the module does is reachable with raw SQL alone. This section covers the module in that form,
and the typed DSL follows it.

### Statements and binds

`sql"..."` builds a statement. Every interpolated value becomes a bind parameter, encoded through its column
codec, so the text written is exactly the text the server prepares and a value cannot splice into it:

```scala
val minTotal = BigDecimal(100)

val large: Chunk[Invoice] < (Abort[SqlException] & DB) =
    sql"SELECT id, customer_id, total, status, note FROM invoice WHERE total >= $minTotal".as[Invoice].run
```

Interpolating a value whose type has no column codec is a compile error at the `$` that lifted it. `.as[A]`
names what a row decodes to, since the interpolator cannot know the statement's result shape, and `.run`
executes and decodes.

Rows decode by column name: `SELECT customer_id, id, ...` into `Invoice` lands every value in its own field,
whatever order the SELECT listed them in. A field that matches no column fails the decode with a typed error
naming the missing column, never a silent positional match. A computed column is addressed by aliasing it to
the field that reads it (`SELECT sum(total) AS total ...`).

`.execute` is the terminal for statements that answer with a row count. `DB.executeRaw` is the entry point
for DDL and migrations, running on the simple-query protocol, which takes no binds:

```scala
val note = "settled early"

val updated: Long < (Abort[SqlException] & DB) =
    sql"UPDATE invoice SET note = $note WHERE status = 'Paid'".execute

val indexed: Long < (Abort[SqlException] & DB) =
    DB.executeRaw("CREATE INDEX IF NOT EXISTS idx_invoice_status ON invoice (status)")
```

### Fragments are values

Statements are assembled like any other value. `++` concatenates fragments, `Fragment.empty` is the
identity, and a clause built behind a condition is an ordinary expression:

```scala
val paidOnly = true

val listing: Chunk[Invoice] < (Abort[SqlException] & DB) =
    val base   = sql"SELECT id, customer_id, total, status, note FROM invoice"
    val filter = if paidOnly then sql" WHERE status = 'Paid'" else Sql.Fragment.empty[Any]
    (base ++ filter).as[Invoice].run
end listing
```

A composed fragment's row tag is the last part's, so the `.as[A]` goes on the whole statement, at the end,
rather than on a part of it.

### Streaming

`.stream` pulls rows as the consumer reads them, so a result larger than memory is a `Stream` rather than a
`Chunk`. It is the one run form carrying `Scope`: the server-side cursor stays open for the stream's life
and closes when the enclosing scope ends:

```scala
val revenue: BigDecimal < (Abort[SqlException] & Scope & DB) =
    sql"SELECT total FROM invoice".as[BigDecimal].stream
        .fold(BigDecimal(0))((acc, total) => acc + total)
```

## Typed queries

The typed DSL expresses a statement as type-checked structure over the same AST. The same listing in both
spellings, side by side:

```scala
val status = InvoiceStatus.Paid

val largestRaw: Chunk[BigDecimal] < (Abort[SqlException] & DB) =
    sql"""SELECT total
          FROM invoice
          WHERE status = $status
          ORDER BY total DESC
          LIMIT 20""".as[BigDecimal].run

val largest: Chunk[BigDecimal] < (Abort[SqlException] & DB) =
    Sql.from[Invoice]
        .where(r => r.invoice.status == InvoiceStatus.Paid)
        .select(r => r.invoice.total)
        .orderBy(r => r.invoice.total.desc)
        .limit(20)
        .run
```

The result type follows the projection: one column answers that column's type and a tuple projection
answers tuples. Joins carry both aliases, and an outer join lifts the unmatched side's columns to `Maybe`,
because an unmatched row holds no value in them:

```scala
val billed: Chunk[(String, BigDecimal)] < (Abort[SqlException] & DB) =
    Sql.from[Invoice]
        .innerJoin(Sql.from[Customer])
        .on(j => j.invoice.customerId == j.customer.id)
        .select(j => (j.customer.email, j.invoice.total))
        .run
```

`.to[B]` retargets a tuple projection at a case class whose field types line up, so the same query answers
named fields instead:

```scala
case class CustomerBill(email: String, total: BigDecimal)

val billed: Chunk[CustomerBill] < (Abort[SqlException] & DB) =
    Sql.from[Invoice]
        .innerJoin(Sql.from[Customer])
        .on(j => j.invoice.customerId == j.customer.id)
        .select(j => (j.customer.email, j.invoice.total))
        .to[CustomerBill]
        .run
```

### Names derive from the type

`Sql.from[Invoice]` names the record the lambdas receive after the type it queries: the key is the
decapitalized type name, so `r.invoice.total` reads the invoice table's column. The table name is not
written anywhere either: it derives from the type (`invoice`), and a
[naming convention](#rows-names-and-column-types) can case it. Both derived names take parameters when the
defaults do not fit. `Sql.from[Invoice]("i")` sets the alias, which re-keys the record (`r.i.total`) and is
how a self-join tells its two sides apart, and `Sql.from[Invoice]("i", "invoice_ledger")` names the table
outright beside it.

The override is per call site, which is a repetition where a schema's table names differ from its type names
throughout, plural tables being the common case. Name the source once instead:

```scala
transparent inline def invoices = Sql.from[Invoice]("i", "invoice_ledger")

val pending    = invoices.where(r => r.i.status == InvoiceStatus.Pending)
val topByTotal = invoices.orderBy(r => r.i.total.desc).limit(10)
```

Every query then starts from `invoices` and the table name is written once. `transparent` is what makes it
work: without it the record type widens and the lambdas cannot name a column, and with it the source still
folds at compile time (see [Static queries](#static-queries)).

In a self-join both sides need their own key, so the predicate can name one against the other, pairing distinct
invoices billed to the same customer:

```scala
val sameCustomer: Chunk[(Long, Long)] < (Abort[SqlException] & DB) =
    Sql.from[Invoice]("first")
        .innerJoin(Sql.from[Invoice]("second"))
        .on(j => j.first.customerId == j.second.customerId && j.first.id < j.second.id)
        .select(j => (j.first.id, j.second.id))
        .run
```

### Raw SQL composes into typed queries

A fragment, a column reference, and a typed query are the same kind of AST node, so the two forms embed in
both directions. A typed column and a bound value drop into a raw predicate, and the fragment sits inside a
typed query's `where` like any other predicate:

```scala
val cutoff = BigDecimal(100)

val flagged: Chunk[Invoice] < (Abort[SqlException] & DB) =
    Sql.from[Invoice]
        .where(r => sql"${r.invoice.total} >= $cutoff".as[Boolean])
        .run
```

A typed query embeds in raw SQL wherever a subquery belongs:

```scala
val payers: Chunk[String] < (Abort[SqlException] & DB) =
    val paidIds =
        Sql.from[Invoice]
            .where(r => r.invoice.status == InvoiceStatus.Paid)
            .select(r => r.invoice.customerId)
    sql"SELECT email FROM customer WHERE id IN ($paidIds)".as[String].run
end payers
```

Adoption is incremental in both directions: a raw program lifts the one query that benefits from
types, a typed program drops to a fragment for the clause the DSL does not model, and nothing around the
statement changes. `Sql.raw[A](text)` does the same at expression granularity, for a single function or
operator the DSL lacks.

### Typed queries compose as values

A query is an ordinary value at every step. There is no macro and no quotation: it is plain values composed
through `Record`, so the parts of a statement can be named separately, shared, and assembled. One filtered
trunk below serves two different reports:

```scala
val paid = Sql.from[Invoice].where(r => r.invoice.status == InvoiceStatus.Paid)

val firstPage: Chunk[(Long, BigDecimal)] < (Abort[SqlException] & DB) =
    paid.select(r => (r.invoice.customerId, r.invoice.total)).orderBy(r => r.invoice.total.desc).limit(20).run

val largestPaid: Chunk[Maybe[BigDecimal]] < (Abort[SqlException] & DB) =
    paid.max(r => r.invoice.total).run
```

Composition is plain Scala: no builder to close, no session to thread through, and a composed value renders
once, when a terminal runs it. The shape stays SQL's: a query carries one `where`, so a narrower trunk says
`status == Paid && total >= min` in that predicate rather than stacking a second filter later.

A statement held in a `val` still runs: `.run` accepts it and renders it at run time, and `.runDynamic`
says so explicitly. What a `val` cannot give is compile-time rendering: the compiler sees only a reference
to the variable, not the query construction behind it:

```scala
val paid = Sql.from[Invoice].where(r => r.invoice.status == InvoiceStatus.Paid)
val page = paid.select(r => (r.invoice.customerId, r.invoice.total)).orderBy(r => r.invoice.total.desc).limit(20)

val rows: Chunk[(Long, BigDecimal)] < (Abort[SqlException] & DB) =
    page.runDynamic
```

Calling `.runStatic` on the same value is a compile error, and the error says what to change rather than
falling back silently:

```text
9 |    page.runStatic
  |         ^
  |    .runStatic: this statement cannot be folded at compile time. The statement reaches this macro
  |    as a variable reference rather than as its construction, which is what a query stored in a
  |    `val` looks like here. Construct it at the call site, or name it with a `transparent inline
  |    def`. Use .run for opportunistic static folding with runtime fallback, or .runDynamic to skip
  |    static folding entirely.
```

The refusal names the construct it found rather than guessing, so a statement carrying a raw `sql"..."`
fragment or a `having` clause says so instead of blaming a `val`.

### Static queries

`.runStatic` requires the SQL to be produced while compiling, so the construction moves to the terminal,
written out where the macro can see it:

```scala doctest:expect=skipped
val firstPage: Chunk[(Long, BigDecimal)] < (Abort[SqlException] & DB) =
    Sql.from[Invoice]
        .where(r => r.invoice.status == InvoiceStatus.Paid)
        .select(r => (r.invoice.customerId, r.invoice.total))
        .orderBy(r => r.invoice.total.desc)
        .limit(20)
        .runStatic
```

The compile-time render works through ordinary Scala 3 inlining: no annotation, no code-generation step,
no macro configuration. When it
succeeds, the SQL text is a constant in the artifact and rendering costs nothing at run time. The render is
made once per driver on the compile classpath, each targeting its dialect's capability floor (PostgreSQL
11.0, MySQL 8.0.31), since compiling has no server to ask. Bind positions must land identically across the
rendered texts, and at run time the client picks the text matching its own dialect, which is how one
compiled statement serves PostgreSQL and MySQL from the same artifact. Every successful `.runStatic`
reports what it folded to at the call site, one line per driver, so the sbt log shows the exact SQL that
was produced (on by default, off with `-Dkyo.sql.static.log=false` on the compiler's JVM):

```text
[info]    |static SQL [postgres]: SELECT "invoice"."customer_id", "invoice"."total" FROM "invoice" "invoice" WHERE ("invoice"."status" = $1) ORDER BY "invoice"."total" DESC LIMIT 20
[info]    |static SQL [mysql]: SELECT `invoice`.`customer_id`, `invoice`.`total` FROM `invoice` `invoice` WHERE (`invoice`.`status` = ?) ORDER BY `invoice`.`total` DESC LIMIT 20
```

A statement that cannot fold, or whose dialects disagree on bind positions, is a compile error there rather
than a silent fallback, which is the difference from `.run`: the default terminal folds opportunistically
and falls back to the runtime renderer, which targets the version the server reported at handshake rather
than the capability floor. The three terminals:

| Terminal | SQL produced | When it cannot be |
|---|---|---|
| `.run` | while compiling, when the query's shape allows | falls back to the runtime renderer |
| `.runStatic` | while compiling, required | compile error |
| `.runDynamic` | at run time, always | not applicable |

The set of rendered dialects is fixed when the call site compiles. This matters when a driver is only
available at run time (registered through `kyo.db.Backend.register` after the compile-time warning about a
scheme no classpath driver claims). Raw fragments and `.runDynamic` render against the live client and work
against such a driver unchanged. A statically folded statement does not: running it against a dialect
outside the rendered set fails with `SqlStaticRenderMissingDialectException`, naming the dialect asked for
and the dialects that were rendered. Putting the driver on the compile classpath is what extends `.run` and
`.runStatic` to it.

Keeping a statement on the static path takes two habits beyond constructing at the terminal: declare
`SqlNaming` givens at the top level of the file that runs the queries, because a casing given local to a
method cannot be resolved statically and sends the render to run time, and write `.runStatic` where the
build should fail if a refactor breaks the compile-time render, because `.run` falls back silently and
reports nothing.

A `SqlNaming` given is found by ordinary implicit search **where the statement is constructed**, so where it
is declared decides which queries it reaches. Top level of the file, or an object the queries are written
inside of, both work. A companion object does not reach the class beside it: a given in `object Store` is
not in scope inside `class Store`, so a query written there runs uncased and asks for the verbatim Scala
field names.

What that failure looks like depends on the tier. The typed DSL names its columns in the projection, so the
statement goes out as `SELECT "signedUpAt" …` and the server rejects it before a row exists, arriving as a
`SqlServerException` carrying SQLSTATE 42703. A raw `sql"…"` decoded by name gets as far as the decode and
fails with `SqlDecodeColumnNotFoundException`, which lists the columns the row does have and says a casing
given is resolved elsewhere. Import it (`import Store.given`) or declare it where the queries are.

Note the construction site is not always the run site. A statement built at the terminal, and one named by a
`transparent inline def`, are constructed where they run; a shared trunk held in a `val` fixed its casing
where the `val` is defined, so moving a given next to the `.run` will not reach it.

### What the static path cannot fold

Two constructs are rendered at run time, so a statement carrying either one folds no further: an embedded
raw `sql"..."` fragment, and a `having` clause. Spelling the predicate with the typed operators is what keeps
it static.

Two more shapes refuse for a different reason, both about what the macro can read rather than what it can
render. A statement reaching the macro as a variable reference cannot be folded, which is what a query held
in a `val` looks like from there. And a statement embedding a value known only at run time, a `limit` count
read from configuration being the usual one, has nothing to fold that value to.

On `.runStatic` each of the four is a compile error naming which one it found; on `.run` the statement falls
back to the runtime renderer, which is the same SQL either way.

Reuse and the static path meet at the source. A source named by a `transparent inline def` folds, and
`transparent` is required, so the table-name override has a home rather than being repeated at every call
site (see [Names derive from the type](#names-derive-from-the-type)).

A source is as far as that goes: a `transparent inline def` naming a source **with a filter already on it**
does not compile, at the definition rather than at the use site. The record the predicate lambda reads is
typed through the `Fields` evidence summoned for the row type, and inside an inline def that evidence is not
resolved yet, so the lambda cannot name a column. Reuse a filtered trunk as a `val` and run it with `.run`,
which renders it at run time.

Whichever path a statement takes, its text is inspectable without executing anything: `ast.render(dialect)`
is pure and needs no client, and `client.render(ast)` targets a live client's flavor and server version.

### What the types are doing

The record the lambdas receive is an ordinary `kyo.Record`: field names mapped to values, here the alias
mapped to the table's columns, each column a `Term` of its Scala type. `r.invoice.total` is a
`Term[BigDecimal]`, so comparing it to a `String` does not compile.

Each combinator transforms that record, and the transformations line up with relational algebra. A join
combines two records with the same `&` that merges any two, widening the schema with the second table's columns
under its key rather than nesting tuples. `groupBy` narrows it the other way: the grouping keys keep their term
surface, every other column drops to the aggregate vocabulary, and `where(col.count > 5)` stops compiling
rather than rendering SQL no server accepts:

```scala
val perCustomer: Chunk[(Long, BigDecimal)] < (Abort[SqlException] & DB) =
    Sql.from[Invoice]
        .groupBy(r => r.invoice.customerId)
        .having(v => v.total.sum >= BigDecimal(500))
        .select(v => (v.customerId, v.total.sum))
        .run
```

Aggregate result types are the ones the servers return: `sum` over an `Int` column is a `Long`, `avg` over
an exact operand is a `BigDecimal`, so the widening both engines perform is present in the types. A typed
query's rows decode positionally, because the renderer emitted the columns itself in field order, so a
naming convention at the call site cannot reorder a typed read.

### Writes

Inserts, updates, and deletes are statements of the same kind:

```scala
val write: Unit < (Abort[SqlException] & DB) =
    for
        _ <- Sql.insert[Invoice].values(Invoice(1L, 7L, BigDecimal("49.90"), InvoiceStatus.Pending, Absent)).run
        _ <- Sql.update[Invoice].set(_.status := InvoiceStatus.Paid).where(_.id == 1L).run
        _ <- Sql.delete[Invoice].where(_.status == InvoiceStatus.Cancelled).run
    yield ()
```

An insert answers with an `InsertOutcome` carrying the affected count and any generated key the engine
surfaced. The upsert forms are on the same builder, with `Sql.Excluded` naming the incoming row's value:

```scala
val upsert: SqlClient.InsertOutcome < (Abort[SqlException] & DB) =
    Sql.insert[Invoice]
        .values(Invoice(1L, 7L, BigDecimal("49.90"), InvoiceStatus.Pending, Absent))
        .onConflictDoUpdate(_.id)(c => c.status := Sql.Excluded(c.status))
        .run
```

For a server-assigned key, `.overriding(_.id := Sql.default)` sends `DEFAULT` in that one cell.
`partialValues` names the columns to send, `fromSelect` inserts from a query, and `returning` is available
on insert, update, and delete. An unconditional update or delete is spelled `.build` rather than by leaving
`where` off, so it is always explicit.

The surface continues where SQL does: `distinct`, set operations, `exists`, scalar subqueries, `CASE WHEN`,
window functions, common table expressions (recursive included), `forUpdate` / `forShare`, rollups and
grouping sets, and `Sql.values` / `Sql.nested` / `Sql.lateral` as sources. Anything the DSL does not cover
can be written as a fragment.

## Transactions

`DB.transaction` pins one connection for the body, commits on success, and rolls back on any failure, abort
and panic alike. Every statement inside runs on that connection, with nothing threaded by hand:

```scala
val settle: Unit < (Abort[SqlException] & DB) =
    DB.transaction {
        for
            _ <- sql"UPDATE invoice SET status = 'Paid' WHERE id = 7".execute
            _ <- sql"INSERT INTO invoice_payment (invoice_id, amount_in_cents) VALUES (7, 4990)".execute
        yield ()
    }
```

- **A domain error rolls back and is re-raised.** A body that fails with its own `Abort` error rolls back
  exactly as an `SqlException` does, and after the rollback the caller receives that
  error unchanged, with the same `Abort` type the body already declared.
- **Nesting is a savepoint.** An inner `DB.transaction` issues a savepoint on the outer connection, so an
  inner failure rolls back to the savepoint and the outer transaction continues.
- **Concurrent fibers inside the body are safe.** A forked fiber inherits the transaction, which is what
  keeps its statements atomic with the rest, and statements racing onto the shared connection serialise on a
  per-session mutex instead of interleaving frames on one socket.

Isolation and read-only mode are the two-argument form:
`DB.transaction(Present(SqlClient.IsolationLevel.Serializable), readOnly = true)(body)`.

`DB.withAdvisoryLock(key, timeout)` is the same session pinning without the transactional semantics: a
cross-process critical section, held for the body and released by a scope finalizer on every exit edge.
Statements inside route to the lock's session, so a one-connection pool still makes progress. MySQL bounds
the wait with `timeout`. PostgreSQL's advisory lock takes none, so the wait is bounded by wrapping the
whole call in `Async.timeout`.

## Rows, names, and column types

`SqlSchema[A]` is the evidence that a type can cross the wire. The derivation builds it one
`SqlSchema.Column` per field, so the columns that prove support are the columns that serialize. The base
column set covers the standard Scala and `java.time` types, mapped natively on both shipped drivers, and
the full table is in the [reference](#type-support). `List`, `Vector`, `Set`, `Map`, and general `Chunk[A]` have
no column, and a nested case class does not flatten implicitly: each is a compile error naming the field or
bind position, because admitting a type to storage is a visible declaration rather than a library default.

The declaration is one given per type. A nested type stored as one JSON document:

```scala
case class ShippingAddress(street: String, city: String) derives Schema

given SqlSchema.Column[ShippingAddress] = Sql.jsonColumn
```

That line makes `ShippingAddress` a legal field, bind, and projection, stored as `jsonb` on PostgreSQL and
`JSON` on MySQL through kyo-schema's document codec, and an overload takes explicit encode and decode functions
for any other JSON library. `SqlSchema.of` and `SqlSchema.ofMulti` are the hand-written routes, one column
and several. `JsonText` carries a document whose shape is only known at run time. `Sql.enumText` is the
codec behind `derives SqlSchema.Column`, for installing on an enum that cannot take a `derives` clause. A
`Maybe[A]` field is the vocabulary for a nullable column: absent decodes to `Absent`, and NULL arriving at a
non-`Maybe` field is a typed decode failure rather than a null.

Names have one rule and two overrides. By default a column is the field name verbatim and a derived table
name is the type name lowercased. `@column("name")` pins one column, and an opt-in
`given SqlNaming = SqlNaming.SnakeCase` cases every unannotated name in scope (`signedUpAt` to
`signed_up_at`, `UserProfile` to `user_profile`). Precedence is narrowest first: the explicit name, then the
casing, then the default. `SnakeCase` keeps an acronym whole (`HTTPServer` to `http_server`, `userID` to
`user_id`), and the mapping does not change once released, because the produced names are a wire contract.
`SnakeCaseUpper`, `UpperCase`, and `LowerCase` round out the conventions; a name no convention gets right is
pinned with `@column`.

## Engines and configuration

```text
postgres://user:password@host:port/database[?options]
mysql://user:password@host:port/database[?options]
```

The scheme picks the driver: at compile time for a literal URL, through runtime discovery for a computed
one. URL options cover transport settings (`sslmode`, `sslrootcert`, `connectTimeout`, `socketTimeout`,
`application_name`), and an option value the parser cannot represent is a typed failure rather than a silent
default.

`DB.run(url, config)` states the pool settings, built from the defaults:

```scala
val tuned = SqlConfig.default
    .maxConnections(20)
    .minConnections(2)
    .queryTimeout(30.seconds)
    .retrySchedule(Schedule.exponentialBackoff(100.millis, 2.0, 5.seconds).take(5))

val opened: Chunk[Customer] < (Async & Abort[SqlException]) =
    DB.run(url, tuned) {
        sql"SELECT id, email, signed_up_at FROM customer".as[Customer].run
    }
```

The pool honours `maxConnections` exactly, warms `minConnections` eagerly, and bounds waiting and running
with `acquireTimeout` and `queryTimeout`. `retrySchedule` retries connection failures only: a statement the
server rejected would fail identically again, and a retried non-idempotent statement can land twice, so the
retry scope is deliberately the transport. `DB.withConfig(f)` adjusts the config in force for one block. The
full field list is in the [reference](#configuration).

TLS runs from `disable` through `verify-full` with chain and hostname checks. A mode that demands encryption
and reaches connect time without TLS settings fails typed rather than opening in the clear. Engine-specific
settings attach through `config.extension(...)`, and engine-specific capabilities live on the concrete
clients, reached by narrowing (`DB.clientAs[PostgresClient]`), so portable code does not reach
engine-specific behavior accidentally. Both are documented in the drivers:
[kyo-sql-postgres](../kyo-sql-postgres/README.md) (`COPY`, `LISTEN`/`NOTIFY`, hstore, ranges, custom types),
[kyo-sql-mysql](../kyo-sql-mysql/README.md) (`LOAD DATA LOCAL INFILE`, TLS modes).

## When things fail

Every operation fails through one typed channel, `Abort[SqlException]`, so failure is part of the signature.
Five categories split it by what a caller can do:

| Category | Cause |
|---|---|
| `SqlConnectionException` | Transport and pool: TCP refused, acquire timeout, TLS, authentication, closed pool. |
| `SqlRequestException` | The request could not be satisfied: an unencodable value, a refused advisory lock. |
| `SqlServerException` | The server answered with an error, carrying SQLSTATE, message, detail, hint, position. |
| `SqlDecodeException` | The row arrived and a column could not become the expected Scala type. |
| `SqlUnsupportedException` | The construct or type is not expressible on this engine. |

Server errors split further by SQLSTATE (`SqlServerConstraintViolationException`,
`SqlServerDeadlockException`, `SqlServerSyntaxException`, ...), and three marker traits cut across the tree
for recovery by property rather than by shape:

```scala
val handled: String < DB =
    Abort.run[SqlException](Sql.from[Invoice]("i").run).map {
        case Result.Success(rows)                     => s"${rows.size} rows"
        case Result.Failure(_: SqlIntegrityViolation) => "constraint violation"
        case Result.Failure(_: SqlRetryable)          => "transient, safe to retry"
        case Result.Failure(e: SqlServerException)    => s"[${e.sqlState}] ${e.serverMessage}"
        case Result.Failure(e)                        => s"failed: ${e.message}"
        case Result.Panic(t)                          => s"panic: ${t.getMessage}"
    }
```

Cancellation needs no registration: the fiber running the statement is the handle, so an `Async.timeout`, a
lost race, or a `Scope` exiting stops the statement. The pool then reclaims the connection (asking the
server to stop, draining what it still owes, rolling back an open transaction) inside one `cancelTimeout`
budget, and a reclaim that cannot prove the session clean destroys the connection rather than pooling a
corrupted one.

## Holding a client

Everything above runs through the `DB` effect. A client value in hand is for the cases that need a receiver:
several databases in one program, rows read without a row type, statements batched into one dispatch, or an
engine-only capability. `SqlClient.init(url)` opens a pool bound to the enclosing `Scope` (`initUnscoped`
hands `close` to the caller, and both have `With` variants). Neither installs the `DB` effect, because supplying
an effect is `DB.run`'s job alone:

```scala
// Two connection strings supplied at run time, one per database.
val primaryUrl: String = ???
val replicaUrl: String = ???

val audited: Long < (Async & Abort[SqlException] & Scope) =
    SqlClient.init(primaryUrl).map { primary =>
        SqlClient.init(replicaUrl).map { replica =>
            for
                totals <- DB.run(replica)(sql"SELECT count(*) FROM invoice".as[Long].run)
                n = totals.headMaybe.getOrElse(0L)
                _ <- DB.run(primary)(sql"INSERT INTO audit (note) VALUES ('counted')".execute)
            yield n
        }
    }
```

`client.query` and `client.simpleQuery` answer `Chunk[SqlRow]` for result shapes no case class describes;
`row.decode[A]`, `row.decode[A](idx)`, and `row.decode[A](name)` read columns, and `row.column` gives the
raw bytes. `client.pipeline` batches statements into one dispatch (a single TCP write on PostgreSQL),
answering one `Result` per statement so one failure does not void the batch. `ping`, `isAlive`, `reset`,
`close`, `serverVersion`, `DB.address`, and the pool metrics round out the operational surface.

## Cross-platform notes

The module and both drivers compile from single shared sources on the JVM, Scala.js, Scala Native, and Wasm,
down to the authentication crypto, which is pure Scala rather than `javax.crypto`. Two things are platform
work:

- **Driver registration.** Every driver ships a `META-INF/services/kyo.db.Backend` entry, which fully covers
  the JVM. On JS and Wasm each driver registers itself at module load (the shipped drivers already do). On
  Scala Native the application enlists the backend class in `nativeConfig.withServiceProviders`, and a
  program opening several engines by computed URL also calls each driver's `register()`, because Native
  embeds a single services file. Literal URLs resolve at compile time and need none of this. The driver
  READMEs carry the snippets.
- **The FFI plugin, on Scala Native.** Every connection goes through kyo-net, whose C shims are linked into
  the binary rather than loaded, so a Native build also needs `addSbtPlugin("io.getkyo" % "kyo-ffi-plugin" %
  kyoVersion)`, `.nativeConfigure(_.enablePlugins(kyo.ffi.sbt.KyoFfiPlugin))`, and the two
  `ffiNativeDependency*Options` tasks folded into `nativeConfig`. Without them the link fails on undefined
  symbols before any of the above matters. See kyo-net's
  [Scala Native builds](../kyo-net/README.md#scala-native-builds) for the exact block.
- **A third engine is one artifact.** `db.Backend` (a scheme, a dialect, an `open`), a dialect written by
  overriding only what diverges from standard SQL (four members are abstract), and the registration above.
  Nothing in kyo-sql names an engine, so an out-of-tree driver is an ordinary dependency.

## Reference

### Type support

Each entry is a `SqlSchema.Column` given with a native mapping on both shipped drivers. A case class or
tuple of these is row evidence, and `Maybe` / `Option` wrap any of them as a nullable column.

| Scala type | PostgreSQL | MySQL |
|---|---|---|
| `Byte`, `Short` | `int2` | `TINYINT`, `SMALLINT` |
| `Int`, `Long` | `int4`, `int8` | `INT`, `BIGINT` |
| `Float`, `Double` | `float4`, `float8` | `FLOAT`, `DOUBLE` |
| `BigDecimal`, `BigInt` | `numeric` | `DECIMAL` |
| `Boolean` | `bool` | `TINYINT(1)` |
| `String`, `Char` | `text` | `VARCHAR` / `TEXT` |
| `Span[Byte]` | `bytea` | `BLOB` |
| `Instant`, `java.time.Instant` | `timestamptz` | `DATETIME` in UTC |
| `java.time.LocalDate`, `LocalTime`, `LocalDateTime` | `date`, `time`, `timestamp` | `DATE`, `TIME`, `DATETIME` |
| `java.time.OffsetTime` | `timetz` | ISO-8601 text |
| `java.time.OffsetDateTime`, `ZonedDateTime` | `timestamptz`, normalised to UTC | `DATETIME` in UTC |
| `java.time.Duration`, `FiniteDuration` | `interval` | `TIME` |
| `Duration` | `int8`, total nanoseconds | `BIGINT`, total nanoseconds |
| `java.time.Period` | `interval` | ISO-8601 text |
| `java.util.UUID` | `uuid` | 36-character string |
| `UUID` | `text` | `VARCHAR` |
| `java.net.URI`, `java.util.Locale`, `java.util.Currency` | `text` | `VARCHAR` |
| `Chunk[Int]`, `Chunk[String]`, `Chunk[JsonText]` | `int4[]`, `text[]`, `jsonb[]` | `JSON` |
| `JsonText` | `jsonb` | `JSON` |

`OffsetDateTime` and `ZonedDateTime` normalise to UTC on the way out, because that is what both engines
store. A codec that keeps the offset is a two-column `SqlSchema.ofMulti`.

### Configuration

| Field | Default | Meaning |
|---|---|---|
| `maxConnections` | 10 | Concurrent connections, honoured exactly. `1` serialises a whole program onto one session. |
| `minConnections` | 0 | Connections opened eagerly at open time. A warm-up failure aborts the open. |
| `acquireTimeout` | 5 seconds | Wait for a pooled connection before a typed timeout failure. |
| `queryTimeout` | 30 seconds | Bound on one query or execute. |
| `idleTimeout` | 10 minutes | Idle time after which a pooled connection is replaced. |
| `retrySchedule` | `Absent` | Retry policy for `SqlConnectionException` only. |
| `tlsMode` / `tls` / `caCertPath` | `Disable` / `Absent` / `Absent` | TLS policy and settings. A URL's `sslmode` overrides the mode. |
| `preparedStatementCacheSize` | 64 | Server-side prepared statements cached per connection. |
| `preparedStatementTtl` | `Infinity` | Unused time after which a cached statement expires. |
| `cancelTimeout` | 2 seconds | One budget for the whole reclaim chain after an interrupt. |
| `metricsEnabled` / `metricsScope` | `true` / `"kyo.sql"` | `Stat` counters, histograms, and a pool gauge. |
| `connectionTestQuery` | `Absent` | Probe before lending a pooled connection. `Absent` checks the socket only. |
| `closeGrace` | 30 seconds | Grace period for `close` when given none. |
| `streamBatchSize` | 64 | Rows per PostgreSQL portal fetch in a stream (informational on MySQL). |
| `extensions` | empty | Engine-specific settings, attached with `.extension(...)`. |

### Protocol capabilities

| Capability | PostgreSQL | MySQL |
|---|---|---|
| Oldest server targeted | 11.0 | 8.0.31 |
| Authentication | trust, cleartext, MD5, SCRAM-SHA-256, SCRAM-SHA-256-PLUS | `mysql_native_password`, `caching_sha2_password`, `sha256_password`, `mysql_clear_password` |
| TLS | `SSLRequest` upgrade | `CLIENT_SSL` upgrade |
| Extended protocol | prepared and binary, per-connection cache | prepared and binary, per-connection cache |
| Streaming | portal fetches of `streamBatchSize` | rows as the server frames them |
| Pipelining | one TCP write per batch | sequential on one connection |
| Cancellation | `CancelRequest` on a fresh connection | `KILL QUERY` on a sidecar |
| Bulk transfer | `COPY` in and out | `LOAD DATA LOCAL INFILE` |
| Notifications | `LISTEN` / `NOTIFY` | none |

A construct one engine lacks (`RETURNING` and `CUBE` on MySQL, for instance) fails as a typed
`SqlUnsupportedDialectFeatureException` at render time, never as SQL the server rejects.

## Adding a backend

An engine kyo-sql does not ship plugs in through `db.Backend`. A backend is one artifact with four pieces: the
`db.Backend` (the plug point, naming a scheme and opening a client), the `SqlClient` subclass its `open` returns
(the surface a user holds, where an engine adds its own operations like `COPY` or `LOAD DATA LOCAL INFILE`), the
`db.Idiom` it renders in, and the `db.Connection` it opens. Nothing above `Backend` names an engine, which is
what makes the two shipped drivers ordinary dependencies rather than privileged ones.

```scala
object ExampleIdiom extends db.Idiom:
    def id: db.Idiom.Id                         = db.Idiom.Id("example")
    def capabilityFloor: db.Idiom.ServerVersion = db.Idiom.ServerVersion(1, 0, 0)
    def quoteIdent(ident: String): String       = "\"" + ident.replace("\"", "\"\"") + "\""
    def placeholder(position: Int): String      = "?"
end ExampleIdiom

object ExampleBackend extends db.Backend:
    def scheme: String       = "example"
    def aliases: Set[String] = Set.empty
    def dialect: db.Idiom    = ExampleIdiom

    def open(url: SqlConfig.Url, config: SqlConfig)(using Frame): SqlClient < (Async & Abort[SqlException]) = ???
end ExampleBackend
```

The class needs a public zero-argument constructor with no side effects: it is constructed at every literal-URL
call site while compiling, as well as at run time.

### Registering it

One `META-INF/services/kyo.db.Backend` entry in shared resources is the whole services contract; the dialect is
reached through `Backend.dialect`, never registered on its own. The rest is per platform:

- **JVM**: nothing further, `ServiceLoader` covers runtime discovery.
- **JS and Wasm**: one `@JSExportTopLevel` object whose initializer calls `Backend.register`, because linker
  dead-code elimination drops an initializer nothing references.
- **Native**: the application enlists the class in
  `nativeConfig.withServiceProviders(Map("kyo.db.Backend" -> Seq("com.vendor.VendorBackend")))`, since Scala
  Native resolves service providers at link time. Native also embeds a single `META-INF/services/kyo.db.Backend`
  file when several jars declare the service rather than concatenating them, so a program that opens more than
  one flavor by computed URL calls each additional backend's own `register()` as well.

> **A missing platform registration fails silently in one direction.** The backend stays reachable through a
> literal URL, which resolves against the compile classpath, and is invisible to a computed one, which resolves
> through runtime discovery. Neither path reports the omission, so the gap shows up as a scheme that works in
> one program and not in another.

A scheme resolves against a `Backend.Registry`: the compile-time backends first, then runtime discovery for a
scheme none of them claims. `Backend.Registry.current` derives from the caller's own compile classpath, so a
library that leaves the engine to its caller expands against the application's classpath, not its own.

### The dialect: `db.Idiom`

Four members are abstract, the facts no default can guess: `id`, `capabilityFloor`, `quoteIdent`, `placeholder`.
Every emitting method has a standard-SQL body reached through `this`, so a subclass declares only its divergences
and an override retargets every path leading to it (overriding `limit` changes pagination under plain queries,
set operations, CTE bodies, and subqueries at once). Capability claims default to the standard: a `supports*`
predicate, a `*Since` floor, and the `supports*(version)` gates derived from those floors. The common shape
intercepts one arm of a family and delegates the rest:

```scala
object ModIdiom extends db.Idiom:
    def id: db.Idiom.Id                         = db.Idiom.Id("example")
    def capabilityFloor: db.Idiom.ServerVersion = db.Idiom.ServerVersion(1, 0, 0)
    def quoteIdent(ident: String): String       = "\"" + ident + "\""
    def placeholder(position: Int): String      = "?"

    override def arithmetic(ctx: db.Idiom.Ctx, ar: Sql.Arithmetic[?]): Unit =
        ar.op match
            case Sql.Arithmetic.Op.Mod =>
                ctx.append("MOD(")
                term(ctx, ar.left)
                ctx.append(", ")
                term(ctx, ar.right)
                ctx.append(")")
            case _ => super.arithmetic(ctx, ar)
end ModIdiom
```

A dialect is a stateless value shared across fibers; per-render state (text, binds, resolved server version,
call-site frame) lives in `Idiom.Ctx`, whose `append`, `appendBind`, `quoted`, `appendQuoted`, and `joinWith` an
override writes through. A construct the target cannot express goes out through `Ctx.unsupported`, never as text
the server would reject. Caller text stays verbatim (`Sql.RawSql`, a fragment's literal parts), and `placeholder`
is the single authority on placeholder spelling.

### The session: `db.Connection`

A `Connection` has two halves. The **statement half** (query, execute, stream, transaction, savepoint, advisory
lock) is what a caller invokes. The **reclaim half** (`inFlight`, `inOpenTransaction`, `cancelInFlight`,
`rollbackIfOpenTransaction`, `drainToIdle`) is what the pool invokes after an interrupt; no caller calls it.

The `inFlight` flag is the contract that matters most: it must read `true` before the first request byte and
stay `true` until the terminal response is drained or the session is reclaimed, because the pool's lease
finalizer reads it from outside the interrupted fiber to decide whether to send a cancel.

`Connection.Factory` is how the pool opens one; `leftSessionIdle`, `isProtocolFatal`, `closingOnFailure`,
`requireUser`, and `errorOf` are shared helpers to reuse rather than re-decide.

### Assembly and rows

`open` calls `db.Runtime.init`, which merges the URL's declarations under the config, builds the pool over the
backend's `Connection.Factory`, and warms up `minConnections` sessions behind a bracket that closes what it
opened on any failure. It returns a `Runtime` the backend wraps in its client class, with a deliberately narrow
surface (`openDedicated` for a caller-owned session such as a notification listener; nothing that bypasses the
client's routing).

`SqlRow.Codec` is the backend's decoder for the rows it produced, one instance per result set carrying whatever
decode context it needs. `Codec.catchingColumn` and `Codec.catching` wrap a decode so a thrown failure arrives
as the typed `SqlDecodeException` the transport expects.
