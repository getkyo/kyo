# kyo-sql-postgres

[kyo-sql](../kyo-sql/README.md) is the SQL module: one portable surface for writing statements, decoding rows,
running transactions, and streaming results, with no engine named anywhere in it. This artifact is its PostgreSQL
driver.

Adding it is the whole installation. It carries one `META-INF/services/kyo.db.Backend` entry, so a `postgres://` or
`postgresql://` URL resolves to this driver, and a program that writes only portable SQL never names anything in
this module. Everything portable is documented in kyo-sql: the `sql"…"` interpolator, the typed DSL, row decoding
through `SqlSchema`, transactions, pooling, and configuration.

Two things here are not portable, and they are what this README covers. The first is a set of operations no other
engine can answer: bulk load and unload through `COPY`, the `LISTEN`/`NOTIFY` stream, and the parameters the
handshake reported. They live on `PostgresClient`, which a program reaches with `DB.clientAs[PostgresClient]` at
the point it has already committed to PostgreSQL.

The second is a column tier. `PostgresTypes` supplies `SqlSchema.Column` instances for `hstore`, the six builtin
range types, native PostgreSQL enums, and any other type through `PostgresTypes.custom`. These are ordinary SQL
evidence: having one admits the type at every bind and row position the portable surface has, including as a field
of a case class that `derives SqlSchema`. They travel out through kyo-sql's extension channel tagged with the
dialect that owns them, so the same program still compiles against MySQL and aborts there with a typed
`SqlUnsupportedTypeOnBackendException` at the bind rather than sending bytes the server cannot read.

```scala
val loaded: Long < (Async & Abort[SqlException]) =
    DB.run("postgres://app:secret@localhost:5432/app") {
        DB.clientAs[PostgresClient].map(_.copyIn(
            "COPY parcel (id, carrier) FROM STDIN WITH (FORMAT csv)",
            Stream.init("1,dhl\n2,ups\n".getBytes.toSeq)
        ))
    }
```

## Adding the driver

Depend on kyo-sql and this artifact. There is nothing to wire:

```scala doctest:expect=skipped
libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-sql"          % "<latest version>",
    "io.getkyo" %% "kyo-sql-postgres" % "<latest version>"
)
```

The services entry names `PostgresBackendFactory`, which claims the scheme `postgres` and the alias `postgresql`,
so a URL copied from a JDBC or libpq connection string opens unchanged. That one entry is the whole services
contract: the dialect is not registered separately, it is the backend's own `dialect` member, which is also what a
statically rendered query reaches for this engine.

### Making the driver discoverable at run time

A URL that is a string literal resolves while compiling, against the drivers on the compile classpath, so nothing
below matters for it. A URL built at run time falls through to runtime discovery instead, and that is where the
platforms differ.

| Platform | How a computed URL finds this driver |
|---|---|
| JVM | `ServiceLoader` reads the services entry. Nothing further. |
| JS, Wasm | An exported initializer in this artifact calls `Backend.register` at module load, because linker dead-code elimination would drop an initializer nothing references. Nothing further. |
| Native | Service providers are resolved at link time, so the application enlists the class itself. |

On Scala Native, add the factory to the link-time provider map:

```scala doctest:expect=skipped
nativeConfig ~= { config =>
    config.withServiceProviders(
        Map("kyo.db.Backend" -> Seq("kyo.internal.postgres.PostgresBackendFactory"))
    )
}
```

Native embeds a single `META-INF/services/kyo.db.Backend` file when several jars declare the service, so a Native
program that opens more than one flavor by computed URL needs `PostgresClient.register()` as well. It is redundant
but harmless on the JVM, and on JS and Wasm the same registration already runs at module load.

```scala
PostgresClient.register()

def parcelCount(host: String, db: String)(using Frame): Chunk[Long] < (Async & Abort[SqlException]) =
    DB.run(s"postgres://app@$host:5432/$db") {
        sql"SELECT count(*) FROM parcel".as[Long].run
    }
```

> **Caution:** omitting a platform's runtime registration leaves the driver reachable through a literal URL and
> invisible to a computed one, with no error at either point.

## Connecting to PostgreSQL

`DB.run` takes a URL, opens a pool for it, and supplies the `DB` effect to everything inside. The pool belongs to
the enclosing `Scope`, which closes it on the way out, so connecting and running statements are one expression:

```scala
val carriers: Chunk[String] < (Async & Abort[SqlException]) =
    DB.run("postgres://app:secret@localhost:5432/app") {
        sql"SELECT carrier FROM parcel ORDER BY id".as[String].run
    }
```

Nothing in that body names PostgreSQL. The URL scheme is what picked this driver, and for a string literal it
picked it while compiling, so a `postgres://` URL with no driver on the classpath is a compile-time warning rather than a
failure at startup. Change the URL to a `mysql://` one and the same body runs against MySQL.

`DB.run(url, config)` is the same with a `SqlConfig`, which is how the PostgreSQL settings below are attached.

### Reaching the PostgreSQL client

The operations with no portable equivalent live on `PostgresClient`, and `DB.clientAs` narrows the running client
to it:

```scala
val version: String < (Async & Abort[SqlException]) =
    DB.run("postgres://app:secret@localhost:5432/app") {
        DB.clientAs[PostgresClient].map(_.parameters).map(_.getOrElse("server_version", "unknown"))
    }
```

Narrowing is a run-time check, because `DB` is unparameterized: asking for `PostgresClient` while a MySQL URL is
running fails with `SqlConnectionBackendMismatchException`, naming both sides. That is the reason to narrow once,
at the boundary where the program has already committed to the engine, and to hold the concrete client from there
rather than narrowing per call. Code that is not inside a `DB.run` at all is a compile error instead, since `DB`
stays in the effect row until something discharges it. The narrowed value is still a `SqlClient`, so it goes
straight back into portable code that takes one.

`PostgresClient.use { client => … }` is the same narrowing spelled as one call.

### What the handshake reported

`parameters` returns the `ParameterStatus` map the server sent during startup (`server_version`, `server_encoding`,
`timezone`, `integer_datetimes`, and the rest), plus any updates a `SET` produced. It is pool-stable: every
connection from the same pool reports the same startup set, so reading it is not a reason to prefer one connection
over another. Like `copyIn`, it routes onto an enclosing transaction's connection rather than leasing a second one;
`copyOut` and `notifications` are the two operations here that never route, each for its own reason.

### Holding the client as a value

`DB.run(url)` covers the common case: one pool, one scope, one program. A program that has to hold the client
itself, to close it on its own schedule or to hand it to a library that takes a `SqlClient`, opens it with
`PostgresClient.init` and supplies the effect with the `DB.run` overload that takes a client:

```scala
val held: Chunk[String] < (Async & Scope & Abort[SqlException]) =
    PostgresClient.init("postgres://app:secret@localhost:5432/app").map { pg =>
        DB.run(pg) {
            sql"SELECT carrier FROM parcel ORDER BY id".as[String].run
        }
    }
```

`init` binds the close to the enclosing `Scope`, and `initWith` is the same plus a callback the client is handed to;
`initUnscoped` and `initUnscopedWith` register no cleanup and leave `SqlClient.close` to the caller. All four mirror
their `SqlClient` counterparts with `PostgresClient` in the result position, and each has a `SqlConfig` overload.
Warm-up finishes before `init` hands the client back, so the connections
`minConnections` asked for are ready by the time the first statement runs.

Naming the engine in the factory is a statement about which engine, so a URL contradicting it is refused rather
than silently retargeted:

```scala
val refused: PostgresClient < (Async & Scope & Abort[SqlException]) =
    PostgresClient.init("mysql://app@localhost:3306/app")
```

That aborts with `SqlConnectionUrlParseException`. Only another engine's scheme is refused: the check reads the
factory's own claimed set, so `postgresql://` opens a client exactly as `postgres://` does.

## Bulk load and unload

`COPY` is PostgreSQL's bulk path in both directions, and the two directions differ in exactly one way that matters:
which connection they run on. `copyIn` joins whatever session the fiber is already in, and `copyOut` never does.

### Streaming rows into the server

`copyIn` takes a `COPY … FROM STDIN` statement and a `Stream[Byte, S]`, and answers with the row count from the
server's `COPY N` command tag. Each chunk of the stream becomes one or more `CopyData` packets, so an in-memory
`Stream.init` covers a small load and `Path.readBytesStream` a file-backed one.

Because it joins the enclosing session, a load inside a transaction is undone with the rest of it: `BEGIN`, `COPY`,
and `ROLLBACK` all reach the same physical connection.

```scala
val atomicLoad: Long < (Async & Abort[SqlException]) =
    DB.run("postgres://app:secret@localhost:5432/app") {
        DB.transaction {
            DB.clientAs[PostgresClient].map(_.copyIn(
                "COPY parcel (id, carrier) FROM STDIN WITH (FORMAT csv)",
                Stream.init("1,dhl\n2,ups\n".getBytes.toSeq)
            ))
        }
    }
```

Outside a transaction it leases a connection for the duration of the upload and returns it to the pool when the
upload finishes, on success and on failure alike.

> **Note:** a rejection from the server stops the upload at the next chunk boundary rather than at the end of the
> stream, so a load the server refuses on its first row costs one chunk and not the whole transfer. The failure it
> reports is the server's own.

### Streaming rows out of the server

`copyOut` runs `COPY … TO STDOUT` and hands back the bytes as a `Stream`, chunked as the server framed them. The
connection is held for the lifetime of that stream, not the lifetime of the call:

```scala
val exportedBytes: Long < (Async & Scope & Abort[SqlException]) =
    DB.run("postgres://app:secret@localhost:5432/app") {
        DB.clientAs[PostgresClient].map(
            _.copyOut("COPY parcel TO STDOUT WITH (FORMAT csv)").fold(0L)((n, _) => n + 1L)
        )
    }
```

> **Unlike** `copyIn` and `parameters`, `copyOut` always takes a connection of its own, so a `COPY TO STDOUT`
> inside a transaction does not see that transaction's uncommitted rows. The transfer holds a cleanup latch
> released only from a `Scope` finalizer; routing it onto the transaction's connection would leave the latch
> outliving the transfer, and the transaction's own `COMMIT` would wait on it forever.

Closing the stream before the server has sent `CopyDone` (a `.take(n)`, an abort, an interrupt) runs an
uninterruptible cleanup that sends `CopyFail` and drains `ReadyForQuery` before the connection goes back. That
cleanup is bounded by `PostgresConfig.copyOutCleanupTimeout`, and if the budget expires the connection is marked
corrupted and removed from the pool rather than returned to it.

> **Caution:** the engine-only operations reach past the portable SPI, which is what gives them the concrete
> connection `COPY` needs, and that means bypassing the in-flight window the SPI maintains. An interrupt during one
> of them leaves the pool with no evidence that a request was outstanding, so the connection is destroyed rather
> than reclaimed. It is the conservative answer, and the right one here: `COPY` has its own mid-transfer cleanup,
> which a generic drain would cut across.

## Server-pushed notifications

A subscription's lifetime is a `Scope` rather than a call, and it holds a connection for all of it, so scope
discipline is most of what there is to know. `notifications` opens a connection through the pool, sends
`LISTEN <channel>`, and emits one `PostgresClient.Notification` per message, each carrying the channel name, the
payload (empty when the `NOTIFY` had no payload clause), and the notifying backend's process id:

```scala
val watch: Unit < (Async & Scope & Abort[SqlException]) =
    DB.run("postgres://app:secret@localhost:5432/app") {
        DB.clientAs[PostgresClient].map(_.notifications("parcel_events").foreach { event =>
            Console.printLine(s"${event.channel} from pid ${event.processId}: ${event.payload}")
        })
    }
```

That connection is opened through the pool, so it is refused on the same terms a statement is refused, but it takes
no slot and is never lent to another borrower: a session running `LISTEN` needs a fiber pumping its inbound
messages, and that pump cannot survive being handed to the next caller.

> **Caution:** `.take(n)` ends the stream, not the scope. The connection is released when the enclosing `Scope`
> exits, so a bounded subscription belongs inside a `Scope.run` of its own rather than living as long as the pool.

```scala
val firstThree: Chunk[PostgresClient.Notification] < (Async & Abort[SqlException]) =
    DB.run("postgres://app:secret@localhost:5432/app") {
        Scope.run(DB.clientAs[PostgresClient].map(_.notifications("parcel_events").take(3).run))
    }
```

That inner scope is the only thing that ends a subscription. Because the connection holds no pool slot, closing the
pool neither closes a live subscription nor waits for one, and a subscription still open at that point keeps
delivering until its own scope exits.

A channel name may contain any character except NUL, which is refused with
`SqlRequestNotificationChannelNulException`, because the statement carrying the name is NUL-terminated on the wire.

Sending is not part of this surface: `notifications` does the `LISTEN`, and `NOTIFY` is an ordinary statement.

```scala
val announce: Long < (Async & Abort[SqlException]) =
    DB.run("postgres://app:secret@localhost:5432/app") {
        DB.executeRaw("NOTIFY parcel_events, 'parcel_1_delivered'")
    }
```

## Column types only PostgreSQL has

One mental model covers this whole section. Each type below is a `SqlSchema.Column[A]`, and having one is the
entire integration: support in kyo-sql is the presence of an instance, so a type with one is admitted everywhere a
bind or a row field is admitted, and a type without one is a compile error at the call site rather than a failure
at execution. Each of these writes through kyo-sql's extension channel, which carries the payload together with the
id of the dialect that owns it.

### hstore

`PostgresTypes.HStore` wraps the flat string-to-string map PostgreSQL's `hstore` stores, with `Maybe.Absent` for a
value that is SQL NULL, which is a different thing from a key that is not there:

```scala doctest:scope=inherited
val labels = PostgresTypes.HStore(
    Map("carrier" -> Maybe("dhl"), "signature" -> Maybe.Absent)
)
```

The column comes from `PostgresTypes.hstoreColumn`, already in implicit scope from the companion, so nothing needs
declaring. Writes always emit the binary form; reads take whichever form the column arrived in, which matters
because every column of a `simpleQuery` result is text and `hstore` renders there as `"carrier"=>"dhl"`.

> **Note:** `hstore` has no fixed OID, so its type name must be declared in `PostgresConfig.typeNames`
> (`Set("hstore")`) or the bind aborts with `SqlUnsupportedCustomTypeException`. Range types do not need this; see
> [Engine settings](#engine-settings).

### Ranges

`PostgresTypes.Range[A]` is an interval over `A`, each end being `Inclusive`, `Exclusive`, or `Unbounded`:

```scala doctest:scope=inherited
// `Instant` under a bare `import kyo.*` is kyo's own, which is not a range element type.
import java.time.Instant

val slot = PostgresTypes.Range(
    PostgresTypes.Range.Bound.Inclusive(Instant.parse("2026-03-01T08:00:00Z")),
    PostgresTypes.Range.Bound.Exclusive(Instant.parse("2026-03-01T18:00:00Z"))
)
```

Nothing about `SqlSchema.Column[A]` says which of PostgreSQL's six range types an element belongs to, so the choice
is carried by an explicit `PostgresTypes.RangeKind[A]` given rather than derived. Seven givens cover the six type
names: `Int` to `int4range`, `Long` to `int8range`, `BigDecimal` to `numrange`, `LocalDate` to `daterange`,
`LocalDateTime` to `tsrange`, and both `Instant` and `OffsetDateTime` to `tstzrange`. That also closes the set, so
an element type PostgreSQL has no range for does not compile:

```scala doctest:expect=fails-compile
summon[SqlSchema.Column[PostgresTypes.Range[String]]]
```

Rejecting that while compiling is the point; the alternative is a payload the server rejects at execution time. For
a user-defined `CREATE TYPE … AS RANGE`, reach for [`custom`](#any-other-type) with the concrete type name instead:
those types have no fixed OID and must be resolved through `PostgresConfig.typeNames`.

The six builtin ranges all bind, `numrange` included. A standalone `numeric` parameter goes out as its ASCII text
rendering, but a range payload's layout fixes its elements as binary, so an element position selects the binary
sibling encoder rather than refusing.

> **Note:** only the binary form of a range is read back. A range column of a `simpleQuery` result arrives as
> PostgreSQL's own `[1,10)` rendering, which is a grammar rather than a header, and is refused by name:
> `[` is `0x5B`, whose low bit is the binary form's EMPTY flag, so parsing it as a header would answer "the empty
> range" for a range holding ten values.

> **Note:** the empty range cannot be read back at all, and fails with `SqlDecodeEmptyRangeException`. Two bounds
> have no spelling for a range that holds no values, and an unbounded pair would mean its exact opposite.

### Native enums

An enum whose variants are all singletons needs nothing from this module: `derives SqlSchema.Column` stores the
variant name as a label in an ordinary text column, and that works on every engine. Reach for
`PostgresTypes.pgEnum` when the column is a native `CREATE TYPE … AS ENUM` rather than text, so the server enforces
the value set and the type's own ordering applies. The variant name is the label either way, so the Scala and SQL
declarations line up by name:

```scala doctest:scope=inherited
// CREATE TYPE parcel_status AS ENUM ('Pending', 'InTransit', 'Delivered')
enum ParcelStatus derives CanEqual:
    case Pending, InTransit, Delivered

given SqlSchema.Column[ParcelStatus] = PostgresTypes.pgEnum[ParcelStatus]("parcel_status")
```

`pgEnum` is `inline` because it reads the variant labels from the `Mirror.SumOf` at the call site and acquires the
`ConcreteTag` there too, neither of which can be derived against an abstract type parameter. Its `typeName`
argument must be declared in `PostgresConfig.typeNames`, the same as `hstore`. A label the server sends that names
no variant aborts with `SqlDecodeSumTypeUnknownLabelException`, listing the labels the enum does have.

### Any other type

`PostgresTypes.custom` is the escape for everything this object does not cover: a PostGIS `geometry`, a `vector`, a
`CREATE DOMAIN`, a user `CREATE TYPE`. It takes the same write and read pair `SqlSchema.of` takes, so the pair
composes from the ordinary SQL vocabulary. A domain over `text` is the simplest case, since its wire form is text's:

```scala doctest:scope=inherited
// CREATE DOMAIN carrier_code AS text CHECK (VALUE ~ '^[a-z]{3,8}$')
case class CarrierCode(value: String) derives CanEqual

given SqlSchema.Column[CarrierCode] =
    PostgresTypes.custom[CarrierCode]((code, w) => w.string(code.value))(r => CarrierCode(r.string()))
```

Derivation does not cover this case: `derives SqlSchema` on a single-field case class makes it a row of one column,
which is what a `SELECT` list wants, and a row is not a bind value. Closing that gap is what `custom` is for.

A type with an OID of its own writes `w.extension(SqlCodec.Writer.Payload(dialect, typeName, format, bytes))` and
reads `r.nextExtension(dialect, typeName)` instead, which is exactly what `hstoreColumn` and `pgEnum` do. That is
the form whose `typeName` has to be resolved from `pg_type`, so it is the form `PostgresConfig.typeNames` exists
for.

> **Note:** `custom` yields the column, which admits the type at bind and row positions. Casting to it
> (`expr.cast[A]`) reads a separate `given SqlType[A]`, so installing only the column leaves the type usable and
> not castable. Declare `given SqlType[CarrierCode] = SqlType.of(SqlType.Type.Extension("carrier_code"))` to close
> that gap.

### Putting them in a row

None of these are a separate tier. Each is a single column, which is precisely what a derived row requires of a
field, so a case class mixing engine types and portable ones derives in the ordinary way:

```scala doctest:scope=inherited
case class Parcel(
    id: Long,
    carrier: CarrierCode,
    status: ParcelStatus,
    labels: PostgresTypes.HStore,
    slot: PostgresTypes.Range[Instant]
) derives SqlSchema

val parcel = Parcel(1L, CarrierCode("dhl"), ParcelStatus.InTransit, labels, slot)
```

### The same program on another engine

Because these columns go out through the extension channel tagged with `postgres`, binding one while a MySQL URL is
running aborts with `SqlUnsupportedTypeOnBackendException`, naming the dialect that owns the type and the dialect
that is active. The refusal is typed and it happens at the bind, at run time; nothing at compile time stops the
program from being written. That is the deliberate trade the extension channel makes, and it is what keeps a MySQL
backend from emitting bytes its server cannot read.

## Engine settings

`PostgresConfig` attaches to a `SqlConfig` through `extension`, and that config goes to `DB.run(url, config)`. At
most one value per extension type is held, so attaching a second replaces the first. Attaching none is a
declaration that the program needs neither field, which is what `PostgresConfig.default` expresses and what the
driver reads when no instance is there:

```scala
val tuned: SqlConfig = SqlConfig.default.extension(
    PostgresConfig(typeNames = Set("hstore", "parcel_status"), copyOutCleanupTimeout = 5.seconds)
)
```

Both fields exist to serve sections above. `typeNames` is what `hstore`, `pgEnum`, and every `custom` type with its
own OID need resolved; the six builtin ranges have fixed OIDs and are resolved without it, as are the standard
scalars. `copyOutCleanupTimeout` bounds `copyOut`'s abort path.

| Field | Default | Meaning |
|---|---|---|
| `typeNames` | empty | Type names the session resolves OIDs for from `pg_type` at connection startup. A name absent from the catalog fails pool initialisation. |
| `copyOutCleanupTimeout` | 5 seconds | Bound on the uninterruptible `CopyFail` and `ReadyForQuery` drain that runs when a `copyOut` stream closes before `CopyDone`. |

> **Caution:** a name containing `'` or `\` is refused with `SqlConnectionInvalidTypeNameException` before any
> connection opens, because the names are interpolated into a `pg_type` lookup.

## How this dialect renders

The dialect is reached through the backend rather than registered on its own, and it is also available directly as
`PostgresClient.dialect`. Rendering is pure, so a statement's text is readable without connecting:

```scala
val id = 1L

val rendered: Maybe[String] =
    sql"SELECT carrier FROM parcel WHERE id = $id".render(PostgresClient.dialect).onlySql
```

That renders `SELECT carrier FROM parcel WHERE id = $1`. Five divergences from the portable baseline are visible to
a program that writes nothing PostgreSQL-specific:

| Construct | PostgreSQL renders |
|---|---|
| Bind placeholder | `$1`, `$2`, numbered by position |
| Identifier quoting | `"quoted"`, with an embedded quote doubled |
| `RETURNING` | supported, so an insert can answer with generated columns |
| Case-insensitive match | `ILIKE`, rather than the baseline `LOWER(…) LIKE LOWER(…)` |
| Integral division | `(CAST(dividend AS NUMERIC) / divisor)`, because `/` on two integers truncates here |

PostgreSQL version-gates nothing. Its capability floor is 11.0.0 and every construct the renderer emits is
available at every version it claims, so a render targeting the floor and a render targeting a live server's reported
version produce identical text: the compile-time fold behind `.runStatic` and `.run`, and the runtime render behind
`.runDynamic`, cannot disagree here. MySQL is the opposite case, and kyo-sql documents what varies there.

## Putting it together

Everything above composes into one program: settings that declare the two type names the columns need, a `COPY`
load, a bind carrying all three engine column types, and a read back into the derived row.

```scala doctest:scope=inherited
val settings: SqlConfig = SqlConfig.default.extension(
    PostgresConfig(typeNames = Set("hstore", "parcel_status"))
)

val roundTrip: Chunk[Parcel] < (Async & Abort[SqlException]) =
    DB.run("postgres://app:secret@localhost:5432/app", settings) {
        for
            _ <- DB.executeRaw("CREATE EXTENSION IF NOT EXISTS hstore")
            _ <- DB.clientAs[PostgresClient].map(_.copyIn(
                "COPY parcel (id, carrier) FROM STDIN WITH (FORMAT csv)",
                Stream.init("1,dhl\n2,ups\n".getBytes.toSeq)
            ))
            _ <- sql"""UPDATE parcel
                       SET status = ${parcel.status}, labels = ${parcel.labels}, slot = ${parcel.slot}
                       WHERE id = ${parcel.id}""".execute
            rows <- sql"SELECT id, carrier, status, labels, slot FROM parcel ORDER BY id".as[Parcel].run
        yield rows
    }
```

One `DB.run` opens the pool, supplies the effect, and closes the pool when the enclosing `Scope` exits. What names
PostgreSQL inside it is the `CREATE EXTENSION` statement, the `COPY` statement, and the column values themselves.
The `sql"…"` statements, the binds, and the row decode are the portable surface kyo-sql documents, doing there what
they do everywhere.
