# kyo-sql-sqlite

The SQLite driver for [kyo-sql](../kyo-sql/README.md).

[kyo-sql](../kyo-sql/README.md) is the SQL module, and it names no engine: the `sql"…"` interpolator, the typed
DSL, `SqlSchema` and row decoding, transactions, streaming, pooling, configuration, and error handling all live
there and are documented there. This artifact is what makes `sqlite://` one of the URLs that module can open.

What changes here is where the database is. SQLite is embedded, so the URL is a path rather than an endpoint,
there is no server to reach, nothing to authenticate to, no TLS to negotiate, and no container to start. The
engine itself ships inside the artifact, compiled from SQLite's own C source.

```scala
val merchants: Chunk[String] < (Async & Abort[SqlException]) =
    DB.run("sqlite://data/app.db") {
        sql"SELECT merchant FROM expense ORDER BY id".as[String].run
    }
```

Nothing in that body names SQLite. The URL scheme is what picked this driver, and for a string literal it picked
it while compiling. Point the same body at a `postgres://` URL and it runs against PostgreSQL.

Two properties of this engine do reach a program that writes nothing SQLite-specific, and between them they are
what the rest of this README is about.

The first is that the DDL is part of the codec. SQLite's per-value storage class has five members and cannot say
that a column holds a date, so the driver dispatches on the column's DECLARED type instead, which is whatever
string the `CREATE TABLE` wrote. Writing `spent DATE TEXT` rather than `spent DATE` is what makes a date come
back as one.

The second is that SQLite resolves types and names permissively where a server would fail, which turns a
construct it does not have into a wrong answer rather than an error. So what this driver adds is not methods but
refusals: an isolation level it cannot run at, an advisory lock it does not have, `GROUP BY ROLLUP`, `LATERAL`,
a row-locking clause, `INTERSECT ALL` and `EXCEPT ALL`, `DEFAULT` in an expression position, and most `cast`
targets each fail typed rather than reaching an engine that would answer them quietly and wrongly.

The examples run over a small expense ledger: a `trip` table and the `expense` rows belonging to it.

## Adding the driver

Depend on kyo-sql and this artifact. There is nothing to wire:

```scala doctest:expect=skipped
libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-sql"        % "<latest version>",
    "io.getkyo" %% "kyo-sql-sqlite" % "<latest version>"
)
```

The artifact carries one `META-INF/services/kyo.db.Backend` entry naming `SqliteBackendFactory`, which claims
the scheme `sqlite` and the alias `sqlite3`, so a URL copied from the CLI or from other tooling opens unchanged.
That one entry is the whole services contract: the dialect is not registered separately, it is the backend's own
`dialect` member, which is also what a statically rendered query reaches for this engine.

### Building this module from a checkout

SQLite's own C source is not committed. It is fetched at a pinned version, verified by sha256, and staged into a
gitignored directory, so a fresh checkout runs the staging script once before the module compiles:

```sh
kyo-sql-sqlite/scripts/build-sqlite.sh
```

Until it has run, the build stops with an error naming the script and the directory it stages into. The version
is pinned rather than tracked because behavior depends on it: `RETURNING` arrived in 3.35.0 and the `RIGHT` and
`FULL JOIN` floor is 3.39.0, so a build that quietly fetched a different release would move what this driver can
promise. `SqlClient.serverVersion` reports the library that was actually linked.

### Making the driver discoverable at run time

A URL that is a string literal resolves while compiling, against the drivers on the compile classpath, so
nothing below matters for it. A URL built at run time falls through to runtime discovery instead, and that is
where the platforms differ.

| Platform | How a computed URL finds this driver |
|---|---|
| JVM | `ServiceLoader` reads the services entry. Nothing further. |
| JS, Wasm | An exported initializer in this artifact calls `Backend.register` at module load, because linker dead-code elimination would drop an initializer nothing references. Nothing further. |
| Native | Service providers are resolved at link time, so the application enlists the class itself. |

On Scala Native, add the factory to the link-time provider map:

```scala doctest:expect=skipped
nativeConfig ~= { config =>
    config.withServiceProviders(
        Map("kyo.db.Backend" -> Seq("kyo.internal.sqlite.SqliteBackendFactory"))
    )
}
```

Native embeds a single `META-INF/services/kyo.db.Backend` file when several jars declare the service, so a
Native program that opens more than one flavor by computed URL needs `SqliteClient.register()` as well. It is
redundant but harmless on the JVM, and on JS and Wasm the same registration already runs at module load.

```scala
val registered: Unit = SqliteClient.register()
```

> **Caution:** omitting a platform's runtime registration leaves the driver reachable through a literal URL and
> invisible to a computed one, with no error at either point.

The module builds for the JVM, Scala Native, Node, and Wasm from one shared source set. It does not run in a
browser: the library is loaded through kyo-ffi, which refuses a host with neither `process` nor `require`, and a
platform with no filesystem has no database file for two connections to share in any case.

## Opening a database

`DB.run` takes a URL, opens a pool for it, and supplies the `DB` effect to everything inside. The pool belongs
to the enclosing `Scope`, which closes it on the way out, so connecting and running statements are one
expression. Everything between `://` and the first `?` is the database path, read verbatim:

| URL | What it opens |
|---|---|
| `sqlite:///var/lib/app.db` | the absolute path `/var/lib/app.db` |
| `sqlite://data/app.db` | the relative path `data/app.db` |
| `sqlite://C:\data\app.db` | the Windows path, backslashes and all |
| `sqlite://:memory:` | a database held in memory, private to each connection |
| `sqlite://` | a temporary database, deleted when the connection closes |
| `sqlite:///var/lib/app.db?connectTimeout=5s` | the path, plus kyo-sql's own URL options |

Reading the path verbatim is what keeps the network URL's delimiters out of the way. A `@`, a `:`, a `/`, and a
`\` are ordinary path characters here, so `:memory:` needs no escaping and a path holding an at-sign is not read
as credentials. The file is created if it does not exist.

> **Caution:** each connection that opens `:memory:` gets its OWN database. The pool defaults to ten
> connections, so `sqlite://:memory:` under the default config is ten separate empty databases, and a table
> created through one lease is missing from the next. An in-memory database means a pool of exactly one
> connection.

```scala
val inMemory: Chunk[String] < (Async & Abort[SqlException]) =
    DB.run("sqlite://:memory:", SqlConfig.default.maxConnections(1)) {
        for
            _    <- DB.executeRaw("CREATE TABLE expense (id INTEGER PRIMARY KEY, merchant TEXT)")
            _    <- DB.executeRaw("INSERT INTO expense (merchant) VALUES ('rail')")
            rows <- sql"SELECT merchant FROM expense".as[String].run
        yield rows
    }
```

Against a file, a pool larger than one is worth having and means something different from a network pool. It
amortises no handshake, there being none to amortise. Several connections over one file is what SQLite's own
locking is built for, and is how readers proceed while a writer holds the write lock.

### What every connection gets

Six settings are applied before a connection is handed out, none of them SQLite's default, and each of them
changes an answer rather than a performance figure:

| Setting | Why |
|---|---|
| Double-quoted string literals off, for DML and DDL | Otherwise a quoted identifier that does not resolve silently becomes a string literal, so a renamed column yields rows carrying its own name as text. The driver quotes every identifier it emits. |
| Extended result codes on | The constraint classes are distinguishable only through them, and that is what the SQLSTATE mapping keys on. |
| `PRAGMA foreign_keys=ON` | Off by default, which would mean declaring a reference and enforcing nothing. |
| `PRAGMA trusted_schema=OFF` | A database file is untrusted input. |
| `sqlite3_busy_timeout` | `SQLITE_BUSY` under concurrency is a routine outcome rather than a fault. |
| `PRAGMA journal_mode = WAL`, for a file | Under the default rollback journal a reader inside a transaction blocks every writer outright. WAL is what lets a writer proceed beside readers. |

The busy timeout is where one portable setting does double duty on this backend. `SqlConfig.acquireTimeout`,
five seconds by default, bounds the wait for a connection from the pool everywhere; here it is also what
`sqlite3_busy_timeout` gets, so it bounds how long a statement waits on a database lock before `SQLITE_BUSY`.
WAL is the one of the six that is allowed to fail: it is persistent in the FILE rather than per connection, an
in-memory database has no file to hold it, and refusing to open over a mode that could not be set would make a
usable database unusable.

### When the open fails

`SqliteOpenFailedException` is this backend's own leaf, carrying the path and SQLite's own message, because
kyo-sql's portable connect failure names a host and a port and a path has neither. The usual causes are a
directory that does not exist, a permission, and a file that is not a database.

### Holding the client as a value

`DB.run(url)` covers the common case: one pool, one scope, one program. A program that has to hold the client
itself, to close it on its own schedule or to hand it to a library that takes a `SqlClient`, opens it with the
portable factory:

```scala
val held: Chunk[String] < (Async & Scope & Abort[SqlException]) =
    SqlClient.init("sqlite://data/app.db").map { client =>
        DB.run(client) {
            sql"SELECT merchant FROM expense ORDER BY id".as[String].run
        }
    }
```

`init` binds the close to the enclosing `Scope`; `initWith` is the same plus a callback the client is handed to,
and `initUnscoped` and `initUnscopedWith` register no cleanup and leave `SqlClient.close` to the caller. Each
has a `SqlConfig` overload.

This module ships no engine-named counterpart to `PostgresClient.init` and `MysqlClient.init`, and the absence
is the point. Those exist to hand back a concrete client carrying operations `SqlClient` does not have, and
`SqliteClient` has none: what SQLite has that the others do not is an absence of things, reported through the
typed refusals below rather than through methods. `DB.clientAs[SqliteClient]` narrows to it where a program
wants the engine named, and it widens straight back to `SqlClient`.

## Declaring columns so rows decode

A network backend asks the server what a column is and gets a type back. Here there is nobody to ask: the
storage class a value carries is one of five, so a date and a decimal are both text and a boolean and an integer
are both integers. The declared type is the only place the answer lives, which is why the DDL below is part of
the program rather than setup around it.

```scala doctest:setup
case class Trip(id: Long, name: String, started: java.time.LocalDate) derives SqlSchema

case class Expense(
    id: Long,
    trip: Long,
    merchant: String,
    amount: BigDecimal,
    reimbursed: BigDecimal,
    spent: java.time.LocalDate,
    tags: Chunk[String]
) derives SqlSchema
```

Those two rows decode against this DDL, and only against DDL shaped like it:

```sql
CREATE TABLE trip (
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name    TEXT NOT NULL,
    started DATE TEXT NOT NULL
);

CREATE TABLE expense (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    trip       INTEGER NOT NULL REFERENCES trip(id),
    merchant   TEXT NOT NULL,
    amount     DECIMAL TEXT(38,10) NOT NULL,
    reimbursed DECIMAL TEXT(38,10) NOT NULL,
    spent      DATE TEXT NOT NULL,
    tags       JSON TEXT NOT NULL
);
```

### Why the names are multi-word

`DATE TEXT` and `DECIMAL TEXT(38,10)` are two jobs in one name, and each word does one of them.

The leading word carries the KIND the codec dispatches on. `DATE` is what makes the driver read `spent` as a
date rather than as text that happens to look like one; `DECIMAL` is what makes `amount` a decimal rather than a
double. The whole vocabulary:

| Declared type begins with | Column kind |
|---|---|
| `INTEGER`, `INT`, `BIGINT`, `SMALLINT`, `TINYINT` | `Integer` |
| `DECIMAL`, `NUMERIC` | `Decimal` |
| `BOOLEAN`, `BOOL` | `Bool` |
| `REAL`, `FLOAT`, `DOUBLE` | `Float` |
| `BLOB` | `Bytes` |
| `UUID` | `Uuid` |
| `JSON` | `Json` |
| `DATE` | `Date` |
| `TIME` | `Time` |
| `TIMETZ` | `TimeWithOffset` |
| `DATETIME` | `DateTime` |
| `TIMESTAMP` | `Timestamp` |
| `INTERVAL`, `DURATION` | `Interval` |
| `ARRAY` | `Array` |
| `TEXT`, `VARCHAR`, `CHAR`, `CHARACTER`, `CLOB` | `Text` |
| anything else, or nothing | `Unknown` |

The trailing `TEXT` does the other job: it forces the column to hold text. Without it SQLite rewrites a value
that looks numeric on the way in, so a 20-digit decimal becomes a double and loses its tail, a JSON document
that is a bare number becomes an integer, and an all-digit date becomes an integer. Each of those is silent. The
trailing word is why `amount` survives as the digits it was written with.

> **Caution:** the parameters must follow the whole name. `DECIMAL TEXT(38,10)` is the spelling;
> `DECIMAL(38,10) TEXT` is a syntax error.

The declared scale is load-bearing on the way back too, because SQLite applies none of its own. A
`DECIMAL TEXT(38,10)` column holding the three characters `2.5` decodes as `2.5000000000`: the DDL is the only
place the intended scale survives, so it is where the reader looks for it.

> **Note:** the coupling is real and nothing at compile time states it. The same `derives SqlSchema` row decodes
> differently against `DATE` than against `DATE TEXT`, so the two declarations belong next to each other in a
> migration, not in different files.

### Reading what a column was declared as

The portable readers report it, and the type name comes back exactly as the DDL wrote it:

```scala
val declared: Chunk[(SqlRow.ColumnKind, Maybe[String])] < (Async & Abort[SqlException]) =
    DB.run("sqlite://data/app.db") {
        DB.client.map(_.query("SELECT amount FROM expense LIMIT 1")).map(
            _.map(row => (row.columnKind(0), row.columnTypeName(0)))
        )
    }
```

That answers `ColumnKind.Decimal` and `Present("DECIMAL TEXT(38,10)")`, the name being the DDL's own string
rather than a normalisation of it. Select `amount + 0` instead and the column is traceable to no declaration at
all: it answers `ColumnKind.Unknown` and carries the server's own rendering, which is the same answer the other
two engines give for an expression.

That split decides one more thing. Reading a column whose declared type is not textual as a `String` is refused
here with `SqlDecodeColumnTypeMismatchException`, though SQLite itself would happily answer `"42"` for an
integer column. Without the check the same program would be type-checked by two engines and not by the third. A
column with no declared type stays permissive, for the same reason it reports `Unknown`: an expression or a
`CAST` has no declared type on any engine, so refusing one would reject correct code.

## What this engine refuses

Every item below is a typed failure. The reason they are refusals rather than substitutions is one property of
the engine: SQLite answers where a server would error, so silently accepting any of these would produce a wrong
result with nothing red.

### Isolation levels

A transaction that names no level gets REPEATABLE READ here, where the two server engines pin READ COMMITTED.
Naming a weaker level is refused rather than quietly upgraded:

| Level | On SQLite |
|---|---|
| `ReadUncommitted` | `SqliteIsolationLevelUnsupportedException` |
| `ReadCommitted` | `SqliteIsolationLevelUnsupportedException` |
| `RepeatableRead` | runs, and is what an unnamed level gets |
| `Serializable` | runs, by exceeding what was asked for |

```scala
val refusedLevel: Chunk[String] < (Async & Abort[SqlException]) =
    DB.run("sqlite://data/app.db") {
        DB.transaction(Present(SqlClient.IsolationLevel.ReadCommitted)) {
            sql"SELECT merchant FROM expense".as[String].run
        }
    }
```

That aborts before anything runs, carrying both the level asked for and the level the engine runs at. SQLite has
no isolation vocabulary and no knob that produces the two weaker levels, so accepting one would be a caller
asking for READ COMMITTED and getting snapshot isolation, which is how a lost update happens with nothing red.
`Serializable` is accepted because the engine refuses a second writer rather than losing an update, which is
stronger than what was asked for.

### Advisory locks

`withAdvisoryLock` aborts with `SqliteAdvisoryLockUnsupportedException`, naming the key, at every version.
SQLite's concurrency is one writer over the whole database rather than per row or per key, so there is nothing
for a named lock to mean, and a caller that wanted mutual exclusion between writers has it already.

### Constructs with no SQLite spelling

These fail at render time, which is to say the failure replaces the SQL rather than accompanying it, so nothing
is ever sent. None of them carries a required version, because none of them arrives in any release:

| Construct | Why it is not emitted |
|---|---|
| `GROUP BY ROLLUP`, grouping sets, `CUBE` | `GROUP BY ROLLUP (x)` is not a parse error on SQLite. It parses as a scalar function call, so a connection carrying a user-defined function of that name would run it and return silently wrong groups. |
| `LATERAL` | No keyword and no equivalent; a correlated subquery in `FROM` position fails too. |
| `FOR UPDATE`, `FOR SHARE` | No row locking, so the `OF` list and the `NOWAIT` and `SKIP LOCKED` behaviors have no spelling either. |
| `INTERSECT ALL`, `EXCEPT ALL` | The plain `INTERSECT` and `EXCEPT` have always been there. The `ALL` forms never have. |
| `DEFAULT` in an expression position | No expression means "this column's declared default", so an `UPDATE` or a conflict-update assigning it is refused. An `INSERT` is the one place it works, by omitting the column entirely. |

```scala
val perMerchant: Unit < (Abort[SqlException] & DB) =
    Sql.from[Expense]("e")
        .groupByRollup(c => c.e.merchant)
        .select(view => (view.merchant, view.amount.sum))
        .run.unit
```

That aborts with `SqlUnsupportedDialectFeatureException` naming `GROUP BY ROLLUP`. The compile-time fold behind
`.run` declines the statement rather than failing the build, so it falls through to the runtime renderer, which
is what raises. Spelled `.runStatic` the same statement is a compile error instead, naming the dialect and the
construct.

### Cast targets

`expr.cast[A]` answers for the storage classes SQLite actually has and refuses the rest, because SQLite's `CAST`
never fails on a type name it does not know. It substring-matches an affinity and returns a value of that class,
so a wrong target produces a wrong value with nothing red. Measured against the text `'42.7'`: `INTERVAL`
contains `INT` and so yields the integer `42`, `BYTEA` matches nothing and falls to numeric so the byte spelling
produces no bytes, `BOOLEAN` and `UUID` and `JSONB` and `DATE` and `TIMESTAMP` all collapse to the same real,
and `TEXT[]` contains `TEXT` so the array-ness is dropped.

| Target type | Renders as |
|---|---|
| `String` | `TEXT` |
| `Short`, `Int`, `Long` | `INTEGER` |
| `Float`, `Double` | `REAL` |
| `Span[Byte]` | `BLOB` |
| `BigDecimal` | `TEXT`, which is where the type mapping already stores it, so the scale and the digits past a double survive |
| everything else, an extension type included | `SqlUnsupportedDialectFeatureException` |

### Values and statement strings

Binding a `NaN` is refused with `SqliteNaNNotStorableException`, because `sqlite3_bind_double` accepts one,
stores NULL, and reports success: the value would become an absent one with nothing red. It carries
`SqlValueOutOfRange`, so one handler catches it together with the codec-side range failures. The infinities are
NOT refused; SQLite holds them, and they read back as `Inf` and `-Inf`.

A statement string holding more than one statement is refused with `SqliteMultipleStatementsException` and
nothing runs. `sqlite3_prepare_v2` on its own compiles the first statement and leaves the rest, so a driver that
ignored the remainder would silently run a prefix of what it was given. Both other engines refuse such a string
at the wire, and this keeps SQLite from being the one that quietly does something else.

kyo-sql's extension channel, which is how an engine-owned column type reaches the wire, is reachable here and
populated by nothing. A payload tagged with another engine's dialect aborts at the bind with
`SqlUnsupportedTypeOnBackendException`, and `cast` to an extension type refuses outright rather than forwarding
a declared name SQLite would substring-match into some other affinity.

## How this dialect renders

Rendering is pure, so a statement's text is readable without connecting:

```scala
val id = 1L

val rendered: Maybe[String] =
    sql"SELECT merchant FROM expense WHERE id = $id".render(SqliteClient.dialect).onlySql
```

```text
SELECT merchant FROM expense WHERE id = ?
```

The two members every dialect must supply are each borrowed from a different sibling here. Identifiers are
double-quoted with an embedded quote doubled, byte-identical to PostgreSQL, and placeholders are an unnumbered
`?`, as on MySQL. `RETURNING` is supported on `INSERT`, `UPDATE`, and `DELETE`, which MySQL has at no version.

Everything else that diverges follows from the same property the refusals do. SQLite resolves permissively, so a
construct the baseline renders becomes a wrong answer rather than an error, and the renderer lowers it:

| Construct | SQLite renders | Because |
|---|---|---|
| `/`, both the fractional and the integral form | `(CAST(dividend AS REAL) / NULLIF(divisor, 0))` | `CAST(7 AS NUMERIC) / 2` is `3` here, NUMERIC affinity leaving an integer an integer. A decimal column is declared with TEXT affinity to keep its digits, and text that parses as an integer divides as one, so 10 over 4 would answer 2 rather than 2.5. |
| `%` | a subtraction, `(l - CAST(l / d AS INTEGER) * d)` | `%` truncates BOTH operands to integers first, so `7.5 % 2.5` is `1.0` where both other engines answer `0.0`, typed `real` so nothing downstream flags it. |
| an empty `IN ()` | `(0)`, and `(1)` negated | `TRUE` and `FALSE` are fallback constants rather than reserved words. Over a table with a column named `false`, `WHERE (FALSE)` returns the row, so the baseline's spelling would match every row instead of none. |
| `IS TRUE` and its family | `IS 1`, `IS NOT 1`, `IS 0`, `IS NOT 0` | The same shadowing, in the same predicate position. |
| `IS UNKNOWN` | `IS NULL` | The keyword does not exist, and for a boolean-valued expression the lowering is exact rather than close: `NULL IS NULL` is true, a decided comparison is not, and a comparison against NULL is. |
| `SUBSTRING(x FROM a FOR b)` | `SUBSTR(x, a, b)` | SQLite rejects the `FROM` form. `SUBSTR` rather than the `SUBSTRING` alias, which arrived in 3.34. |
| a `VALUES` source with named columns | a wrapping `SELECT` renaming `column1`, `column2`, … | SQLite rejects an alias column list on a derived table, which is what the baseline renames through. The row list is left untouched, so the bind order is unchanged. |
| `INSERT … SELECT` carrying a conflict clause | the fed query wrapped as a derived table with a trailing `WHERE 1` | SQLite's grammar reads the `ON` of `ON CONFLICT` as the start of a join constraint. A trailing `WHERE` disambiguates it, and putting it on the wrapper is correct for a query that already ends in a `WHERE` or already carries a join `ON`. |
| a `DEFAULT` cell in an `INSERT` | the column leaves the column list | `NULL` is the trap: it happens to auto-assign for an `INTEGER PRIMARY KEY`, so substituting it would pass the generated-key case and store NULL instead of the declared default for every other column. |

Keeping a division fractional takes both sides of the driver, and neither half is enough alone. The render-side
`CAST(… AS REAL)` covers a column divided by a column, where no bound value takes part at all. The bind side
covers the rest: a bound `BigDecimal` always goes out carrying a decimal point, so a divisor does not parse as
an integer.

```scala
val coverage: Maybe[String] =
    Sql.from[Expense]("e")
        .select(c => c.e.reimbursed / c.e.amount)
        .render(SqliteClient.dialect)
        .onlySql
```

```text
SELECT (CAST("e"."reimbursed" AS REAL) / NULLIF("e"."amount", 0)) FROM "expense" "e"
```

Nothing here is version-gated. The capability floor is 3.39.0, the oldest release carrying every construct this
dialect renders unconditionally, and the module vendors 3.53.4, which dominates it. So a render targeting the
floor and a render targeting the linked library produce identical text, and the compile-time fold behind
`.runStatic` and `.run` cannot disagree with the runtime render behind `.runDynamic`. MySQL is the opposite
case, and kyo-sql documents what varies there.

## Transactions, streaming, and concurrency

One writer at a time over the whole database is SQLite's concurrency model, and the driver takes that lock at
`BEGIN` rather than at the first write. A write transaction opens `BEGIN IMMEDIATE`, so a second write
transaction cannot open while the first is live. The default `BEGIN` would open either of them and then fail to
upgrade against a concurrent reader, which lands the failure at the `COMMIT` instead of at the write that caused
it.

```scala
val importBatch: Long < (Async & Abort[SqlException]) =
    DB.run("sqlite://data/app.db") {
        DB.transaction {
            for
                _ <- DB.executeRaw("INSERT INTO trip (name, started) VALUES ('kyoto', '2026-04-02')")
                n <- DB.executeRaw("INSERT INTO expense (trip, merchant, amount, reimbursed, spent, tags) " +
                    "VALUES (last_insert_rowid(), 'rail', '124.50', '0.00', '2026-04-02', '[\"transit\"]')")
            yield n
        }
    }
```

A read-only transaction opens a plain `BEGIN` and sets `PRAGMA query_only = ON`. That pragma is per CONNECTION
rather than per transaction, so it is cleared on commit and on rollback alike, and a connection is never
returned to the pool still refusing writes. Savepoints are ordinary, and a nested `DB.transaction` takes one
rather than opening a second transaction.

> **Caution:** interrupting a write inside an explicit transaction rolls the WHOLE transaction back, which
> neither other engine does. A caller that cancels a statement and carries on inside the same transaction is
> carrying on without one.

Streaming steps the statement's own cursor, one row per step, which is what SQLite does natively:

```scala
val spend: BigDecimal < (Abort[SqlException] & Scope & DB) =
    sql"SELECT amount FROM expense".as[BigDecimal].stream
        .fold(BigDecimal(0))((acc, amount) => acc + amount)
```

`SqlConfig.streamBatchSize` is accepted and unused here. It exists to bound a network round trip, and there is
none; honouring it by buffering would add latency for nothing.

> **Caution:** a prepared statement outlives a `COMMIT` on its own connection and goes on yielding rows. A
> stream consumed after its transaction committed is reading rows that transaction no longer protects, so a
> stream belongs inside the transaction that scoped it.

A generated key comes from `RETURNING` in preference to `last_insert_rowid`, and the two answers that are not a
key say different things. A table the renderer found no key column on reports `NoAutoKey`, which is the clause
having been omitted and is therefore itself the evidence. A zero rowid reports `Unavailable` rather than the
implicit rowid every ordinary table carries: reporting that would hand back a number the caller never declared,
indistinguishable from a real key and meaning something different from what the other engines answer for the
same table.

`pipeline` runs its statements in turn, and answers one result per statement exactly as it does elsewhere. What
it does not do here is save anything: a pipeline exists to spend one round trip on several statements, and an
embedded engine has no round trip to spend.

## How failures arrive

SQLite has no SQLSTATE, so this driver synthesises one from the extended result codes, using the standard's own
class codes. A handler already matching on class `23` for an integrity violation matches this engine's without
being told which engine it is on.

| Condition | SQLSTATE |
|---|---|
| unique violation | `23505` |
| not-null violation | `23502` |
| foreign key violation | `23503` |
| check or trigger violation | `23514` |
| wrong type into a `STRICT` table | `23000`, an integrity violation rather than a range one: the value is not too large, it is the wrong kind |
| value out of range | `22003` |
| read-only database | `25006` |
| busy or locked | `55P03` |
| syntax error | `42601` |
| unknown table | `42P01` |
| unknown column | `42703` |
| anything else | `HY000` |

Three of those rows share one cause and have to be told apart by hand. An unknown table, an unknown column, and
a syntax error all answer bare `SQLITE_ERROR`, so the message prefix is what separates them. A message matching
no prefix degrades to `HY000` rather than being guessed at: a less specific exception is still one a caller can
act on, where a misclassified one sends them to the wrong fix.

Five typed exceptions are this backend's own, each carrying a portable base so a handler written against
kyo-sql's hierarchy still catches it:

| Exception | Raised when | Portable base |
|---|---|---|
| `SqliteOpenFailedException` | opening the database failed; carries the path and SQLite's message | `SqlConnectionBackendException` |
| `SqliteIsolationLevelUnsupportedException` | a transaction named `ReadUncommitted` or `ReadCommitted`; carries both the requested level and the engine's | `SqlUnsupportedBackendException` |
| `SqliteAdvisoryLockUnsupportedException` | `withAdvisoryLock`; carries the key | `SqlUnsupportedBackendException` |
| `SqliteNaNNotStorableException` | a `NaN` reached a bind | `SqlRequestBackendException`, plus `SqlValueOutOfRange` |
| `SqliteMultipleStatementsException` | a statement string held more than one statement | `SqlRequestBackendException` |

Everything else arrives through kyo-sql's own hierarchy, documented in
[When things fail](../kyo-sql/README.md#when-things-fail).

## Configuration

SQLite declares no `SqlConfig.Extension`, so there is no SQLite config type to look for. The settings an
extension would carry are transport settings, and there is no transport. Every setting this driver honors is
portable, and a `SqlConfig` reaches the pool as the second argument to the same connect call:

```scala
val merchants: Chunk[String] < (Async & Abort[SqlException]) =
    DB.run("sqlite://data/app.db", SqlConfig.default.maxConnections(4)) {
        sql"SELECT merchant FROM expense ORDER BY id".as[String].run
    }
```

Two portable settings mean something specific here.

`acquireTimeout` does double duty: it is how long a caller waits for a pooled connection, and it is also the
engine's own busy timeout, which is how long a statement waits for the write lock another connection holds.
One value for both is correct because they are the same wait from the caller's side.

`maxConnections` is worth more thought than on a server, in both directions. Several connections to one file is
what SQLite's own locking is built for, and is how readers proceed while a writer holds the write lock, so
pooling is not merely amortising a handshake that does not exist. But every connection opening `:memory:` gets
its OWN private database, so an in-memory URL wants `maxConnections = 1` or the program appears to lose every
table it creates.

## Putting it together

The whole of the ledger, from an empty file to rows read back, with nothing in the body naming the engine
except the URL and the DDL. `Trip` and `Expense` are the rows declared above:

```scala
val report: Chunk[Expense] < (Async & Abort[SqlException]) =
    DB.run("sqlite://data/app.db", SqlConfig.default.maxConnections(4)) {
        for
            _ <- sql"""CREATE TABLE IF NOT EXISTS expense (
                         id         INTEGER PRIMARY KEY AUTOINCREMENT,
                         trip       INTEGER NOT NULL,
                         merchant   TEXT NOT NULL,
                         amount     DECIMAL TEXT(38,10) NOT NULL,
                         reimbursed DECIMAL TEXT(38,10) NOT NULL,
                         spent      DATE TEXT NOT NULL,
                         tags       JSON TEXT NOT NULL
                       )""".execute
            _ <- DB.transaction {
                Sql.insert[Expense]
                    .values(Expense(
                        0L,
                        1L,
                        "rail",
                        BigDecimal("48.20"),
                        BigDecimal("0.00"),
                        java.time.LocalDate.of(2026, 3, 14),
                        Chunk("travel")
                    ))
                    .overriding(_.id := Sql.default)
                    .run
            }
            rows <- Sql.from[Expense]("e").orderBy(c => c.e.spent).run
        yield rows
    }
```

Three things in that DDL are this engine and not the DSL. `spent DATE TEXT` declares the kind the codec
dispatches on and the affinity that keeps the rendering verbatim, so the date comes back as a date rather than
as an integer. `amount DECIMAL TEXT(38,10)` does the same for a decimal, and keeps every digit rather than as
many as a double holds. `INTEGER PRIMARY KEY AUTOINCREMENT` is what `Sql.default` on the key resolves against:
the renderer drops the column from the insert entirely and reads the assigned key back through `RETURNING`.

Everything else is portable. Point the same body at `postgres://` or `mysql://`, write that engine's DDL, and
it runs unchanged.
