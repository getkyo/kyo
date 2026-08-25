<!-- doctest:setup
```scala
import kyo.*
```
-->

# kyo-sql-mysql

The MySQL driver for [kyo-sql](../kyo-sql/README.md).

[kyo-sql](../kyo-sql/README.md) is the SQL module, and it names no engine: the `sql"…"` interpolator, the typed DSL,
`SqlSchema` and row decoding, transactions, streaming, pipelining, configuration, and error handling all live there
and are documented there. This artifact is what makes `mysql://` one of the URLs that module can open. Adding it to
the build is the whole installation: it carries one `META-INF/services/kyo.db.Backend` entry, so a program written
against kyo-sql's portable surface runs on MySQL without naming anything in here.

What the driver decides for such a program is two things. It decides how the portable types reach the wire, which
matters where MySQL has no column for what a program stores: a `Chunk[String]` becomes a JSON document, a
`java.time.Period` becomes normalized ISO-8601 text, a `java.util.UUID` becomes a 36-character string. And it decides
how the portable DSL renders, which matters where MySQL's SQL differs in shape or arrived in a specific 8.0 release:
there is no `RETURNING` at any version, a conflict clause is `INSERT IGNORE` or `ON DUPLICATE KEY UPDATE`, and
`LATERAL`, `WITH RECURSIVE`, `INTERSECT` / `EXCEPT`, and the `VALUES` table constructor each carry a version floor. A
render that reads the connected server's version aborts with a typed failure on a construct that server is too old
for, rather than sending it SQL it would reject.

One operation has no portable equivalent and so lives on `MysqlClient`, the driver's own client: `loadLocalInfile`,
the client-side bulk load MySQL performs through its `LOCAL INFILE` protocol. A program reaches it by narrowing once
with `DB.clientAs[MysqlClient]`, and the client widens back to `SqlClient` wherever portable code takes over.

```scala
val hot: Chunk[Long] < (Async & Abort[SqlException]) =
    DB.run("mysql://app:secret@localhost:3306/plant") {
        sql"SELECT sensor FROM reading WHERE celsius > 40".as[Long].run
    }
```

`DB.run(url)` opens a pooled client, supplies it as the `DB` effect the statement runs under, and closes the pool
when the block ends, so there is no client to hold and nothing to close by hand. `.as[Long]` names the type each row
decodes to. Nothing in that program mentions MySQL except the URL.

## Adding the backend

Which engines a program can open is decided by which artifacts it depends on:

```scala doctest:expect=skipped
libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-sql"       % "<latest version>",
    "io.getkyo" %% "kyo-sql-mysql" % "<latest version>"
)
```

The artifact carries one `META-INF/services/kyo.db.Backend` entry naming `MysqlBackendFactory`. That factory declares
the scheme it answers to, the entry point that opens a client, and the dialect the engine renders in, so there is no
second services file for the dialect and nothing to register by hand. The dialect it names is public as
`MysqlClient.dialect`, whose `id` is `mysql`.

The claimed scheme is `mysql`, and this backend declares no alias, so `mysql://user:password@host:port/database` is
the only accepted form. A literal URL is checked while compiling: a scheme no backend on the compile classpath claims
is a compile-time warning naming the schemes that are available, ahead of the typed failure at first connect.

### Runtime discovery on each platform

The driver builds for the JVM, Scala.js, Scala Native, and Wasm. Every protocol, dialect, and codec source is shared;
the only per-platform difference is how a URL computed at run time, rather than written as a literal, finds the
backend.

| Platform | What finds the backend at run time |
|---|---|
| JVM | `ServiceLoader` reads the `META-INF/services/kyo.db.Backend` entry. Nothing to do. |
| Scala.js, Wasm | An exported object registers the factory at module load. Nothing to do. |
| Scala Native | The application enlists the class in `nativeConfig.withServiceProviders`, since Native resolves service providers at link time. |

The Native enlistment names the same class the services file does:

```scala doctest:expect=skipped
nativeConfig ~= {
    _.withServiceProviders(Map(
        "kyo.db.Backend" -> Seq("kyo.internal.mysql.MysqlBackendFactory")
    ))
}
```

`MysqlClient.register()` is the explicit counterpart, for the case the link-time enlistment cannot cover: a Native
program that opens more than one flavor by computed URL, since Native embeds a single
`META-INF/services/kyo.db.Backend` file when several jars declare the service. It is redundant on the JVM and already
runs on JS and Wasm.

```scala
val registered: Unit = MysqlClient.register()
```

Omitting the runtime registration on a platform that needs it leaves the backend reachable through a literal URL and
invisible to a computed one, with no error at either point.

## How MySQL stores the portable types

Every type in kyo-sql's supported set has a MySQL representation, and for most of them it is the obvious column.
Three have no column MySQL offers at all, so the backend picks a form and both sides of the codec agree on it. Core's
[type support table](../kyo-sql/README.md#type-support) says which Scala type lands in which column on each engine, and
this section says what the three that diverge cost when a program queries them.

The examples below use two tables:

```scala doctest:setup
case class Sensor(id: Long, name: String, tags: Chunk[String], calibration: java.time.Period) derives SqlSchema

case class Reading(sensor: Long, taken: java.time.Instant, celsius: Double, humidity: Maybe[Double])
    derives SqlSchema
```

`SqlSchema[A]` is the evidence that admits `A` to the wire, and `SqlSchema.Column[A]` is its single-column tier, the
one a bind position requires. Support is the presence of an instance: `derives SqlSchema` is the whole declaration
for a case class whose fields are each a single column, and a type with no instance is a compile error at the site
that lifted it rather than a failure at run time. `tags` and `calibration` are the two fields this section is about.

### Arrays as a JSON document

`Chunk[Int]`, `Chunk[String]`, and `Chunk[JsonText]` are stored as a JSON array document, MySQL having no array type.
The column is `JSON` and the bind goes out as JSON text, a `VAR_STRING` parameter the server reads into the column,
and a predicate over it is written with MySQL's JSON functions rather than with array operators:

```scala
val outdoor: Chunk[Sensor] < (Abort[SqlException] & DB) =
    sql"""SELECT id, name, tags, calibration FROM sensor
          WHERE JSON_CONTAINS(tags, '"outdoor"')""".as[Sensor].run
```

> **Unlike** PostgreSQL, which stores `Chunk[Int]` and `Chunk[String]` as `int4[]` and `text[]`, MySQL has no array
> operators to reach for. The Scala side is identical on both engines, so the case class and the decode carry over
> unchanged. A raw predicate that names the storage does not.

### Calendar intervals as normalized text

`java.time.Period` is stored as ISO-8601 text, and it is normalized before it goes out. The normalization is what
keeps a predicate portable. PostgreSQL's wire form carries a single month count, so `Period.of(1, 2, 0)` and
`Period.ofMonths(14)` are literally the same stored value there. Without normalizing they would store as the distinct
strings `P1Y2M` and `P14M` on MySQL, and a predicate matching one would miss the other on MySQL while matching both
on PostgreSQL:

```scala
val fourteenMonths = java.time.Period.ofMonths(14)

val due: Chunk[Sensor] < (Abort[SqlException] & DB) =
    sql"SELECT id, name, tags, calibration FROM sensor WHERE calibration = $fourteenMonths".as[Sensor].run
```

`Period.ofMonths(14)` binds the text `P1Y2M`, and so does `Period.of(1, 2, 0)`, so this matches rows written either
way. The read side normalizes too, which is what makes a `Period` round-trip equal on both engines.

> **Caution:** `Period.of(y, m, d)` carries no bound the way `Period.between` over two `LocalDate` values does, so a
> year carry that overflows `Int` is reachable through the bind API. It is refused there with
> `SqlRequestPeriodOverflowException` rather than throwing an unchecked `ArithmeticException`. On the way back,
> interval text this reader did not write is a typed `SqlDecodeIntervalException` rather than a JDK
> `DateTimeParseException` escaping to the caller.

### Times and identifiers as text

`java.time.OffsetTime` is stored as ISO-8601 text, MySQL having no offset-carrying time column, and the offset
survives the round trip because the text carries it. `java.util.UUID` is stored as its 36-character string form,
which is also what `CAST(… AS CHAR)` produces, so a UUID column compares and casts as a string. `JsonText` is a
native `JSON` column.

### No extension types

An engine-owned column type reaches the wire through the extension channel, and this backend implements none of them.
Two different failures come out of that, and they say different things:

| Payload | Failure | Meaning |
|---|---|---|
| Another engine's type, a PostgreSQL range for instance | `SqlUnsupportedTypeOnBackendException` | The value belongs to a dialect this client does not speak. |
| A type claiming the MySQL dialect | `SqlUnsupportedCustomTypeException` | The dialect is right and no MySQL extension type is registered. |

`GEOMETRY`, `SET`, and the spatial family are MySQL types that would travel this channel and are not implemented,
which is a property of this backend rather than of the engine. Bind parameters here are binary by construction, so a
composite payload that demands the text form is refused rather than answered with binary bytes its reader would parse
as text.

## How the dialect renders

Two programs that write the same portable statement can still send different SQL, because MySQL is the engine where a
construct's availability depends on the server's patch release, and because several constructs MySQL does have are
spelled differently from the baseline. Both are decided at render time, so a render that knows which server it is
targeting either produces SQL that server accepts or fails typed before anything is sent.

`MysqlClient.dialect` renders any AST to text without executing it, which is how to see what a statement becomes on
this engine:

```scala
val rendered: Maybe[String] =
    Sql.from[Reading]("r").where(c => c.r.celsius > 40.0).render(MysqlClient.dialect).onlySql
```

Identifiers come back quoted with backticks and bind positions as `?`:

```text
SELECT `r`.`sensor`, `r`.`taken`, `r`.`celsius`, `r`.`humidity` FROM `reading` `r` WHERE (`r`.`celsius` > ?)
```

### A folded render targets the floor, a runtime render targets the connected server

`.runStatic` renders while compiling, where no version is knowable, and so targets `capabilityFloor`, MySQL 8.0.31.
That is the oldest release carrying every construct this dialect renders unconditionally. `.run` attempts the same
fold and takes the same floor whenever it succeeds, rendering at run time only when the fold does not happen.
`.runDynamic` always renders at run time, against the version the server reported at handshake, read once and cached,
so a construct a newer server has is available and one it lacks is refused rather than emitted.

The two paths therefore disagree about what a version-gated construct proves, and they disagree in both directions. A
construct above the floor is unavailable to a folded render, which is what sends `.run` to the runtime path for it. A
construct whose own floor `capabilityFloor` already satisfies renders unconditionally under the fold, and only the
runtime path checks it against the server that will answer:

```scala
val overlap: Chunk[Sensor] < (Abort[SqlException] & DB) =
    Sql.from[Sensor]("a").intersect(Sql.from[Sensor]("b")).runDynamic
```

`INTERSECT` arrived in 8.0.31, which is also `capabilityFloor`. Against a server reporting 8.0.30 the render above
aborts with `SqlUnsupportedDialectFeatureException`, carrying the construct name `INTERSECT / EXCEPT`, the dialect id
`mysql`, the floor it needed, and the version the server actually reported. Spelled `.runStatic`, the same statement
renders `INTERSECT` because the floor satisfies the gate, and that 8.0.30 server answers the statement with a syntax
error, which arrives as `SqlServerSyntaxException`.

Four constructs carry a floor:

| Construct | Available from |
|---|---|
| `WITH RECURSIVE` | 8.0.0 |
| `LATERAL` | 8.0.14 |
| `VALUES` table constructor | 8.0.19 |
| `INTERSECT` / `EXCEPT` | 8.0.31 |

A render targeting a version below one of those floors emits nothing at all for the construct: the failure replaces
the SQL rather than accompanying it, so the runtime path never sends a statement the server would answer with a
syntax error. The folded path targets the floor rather than the server, which leaves one case where MySQL does see
SQL it rejects: a server older than 8.0.31.

### What MySQL never has

Five constructs do not depend on the server version at all, so no upgrade changes the answer. Three of them fail
typed, and two are lowered into something MySQL does have.

`RETURNING` does not exist on MySQL, so `.returning` on an insert, update, or delete fails typed rather than being
silently dropped. A generated key still comes back: the MySQL driver reads `last_insert_id` off the OK packet and
reports it through `SqlClient.InsertOutcome`.

Grouping sets and `CUBE` do not exist either. `groupByRollup` is the one grouping extension MySQL has, and it renders
as a trailing modifier on the key list rather than as the baseline's function-call form:

```scala
val perSensor: Maybe[String] =
    Sql.from[Reading]("r")
        .groupByRollup(c => c.r.sensor)
        .select(view => (view.sensor, view.celsius.max))
        .render(MysqlClient.dialect)
        .onlySql
```

```text
SELECT `r`.`sensor`, MAX(`r`.`celsius`) FROM `reading` `r` GROUP BY `r`.`sensor` WITH ROLLUP
```

A predicate on the conflict-update clause is refused, because `ON DUPLICATE KEY UPDATE` accepts none. Dropping it
silently would update rows the caller excluded, so `onConflictDoUpdate(…).where(…)` raises
`SqlUnsupportedDialectFeatureException` naming `ON CONFLICT ... WHERE`.

`FULL OUTER JOIN` is emulated rather than refused, since the same rows are reachable as a union. A `LEFT JOIN` is
unioned with the matching `RIGHT JOIN` and the whole thing is wrapped as a derived table, so an enclosing clause
attaches to all of it:

```scala
val paired: Maybe[String] =
    Sql.from[Sensor]("s")
        .fullOuterJoin(Sql.from[Reading]("r"))
        .on(j => j.s.id == j.r.sensor)
        .render(MysqlClient.dialect)
        .onlySql
```

```text
SELECT * FROM (SELECT * FROM `sensor` `s` LEFT JOIN `reading` `r` ON (`s`.`id` = `r`.`sensor`)
UNION SELECT * FROM `sensor` `s` RIGHT JOIN `reading` `r` ON (`s`.`id` = `r`.`sensor`)) `sub`
```

`NULLS FIRST` and `NULLS LAST` are a parse error on MySQL, whose null ordering is fixed: nulls first ascending, last
descending. `ascAbsentFirst` and `descAbsentLast` ask for exactly that and render plainly. The other two,
`ascAbsentLast` and `descAbsentFirst`, lower to a leading null-ness term that moves the absent rows to the requested
end:

```scala
val driest: Chunk[Reading] < (Abort[SqlException] & DB) =
    Sql.from[Reading]("r").orderBy(c => c.r.humidity.ascAbsentLast).run
```

```text
SELECT `r`.`sensor`, `r`.`taken`, `r`.`celsius`, `r`.`humidity` FROM `reading` `r`
ORDER BY `r`.`humidity` IS NULL, `r`.`humidity` ASC
```

### What it spells differently

A conflict clause is the largest of these. MySQL discards conflicting rows through the opening keyword rather than a
trailing clause, and references the incoming row as `VALUES(col)` rather than the baseline `EXCLUDED.col`. That
reference is spelled `Sql.Excluded(col)` on the Scala side either way:

```scala
val newSensor = Sensor(1L, "north-inlet", Chunk("outdoor"), java.time.Period.ofMonths(6))

val ignoring: Maybe[String] =
    Sql.insert[Sensor].values(newSensor).onConflictDoNothing().render(MysqlClient.dialect).onlySql

val updating: Maybe[String] =
    Sql.insert[Sensor].values(newSensor)
        .onConflictDoUpdate(_.name)(c => c.name := Sql.Excluded(c.name))
        .render(MysqlClient.dialect).onlySql
```

```text
INSERT IGNORE INTO `sensor` (`id`, `name`, `tags`, `calibration`) VALUES (?, ?, ?, ?)

INSERT INTO `sensor` (`id`, `name`, `tags`, `calibration`) VALUES (?, ?, ?, ?)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)
```

The target columns named in `onConflictDoUpdate` do not appear in the MySQL rendering, because MySQL keys the update
off any duplicate key rather than off named columns. The same statement on PostgreSQL renders them into an
`ON CONFLICT (…)` clause, so a program that relies on one specific key to arbitrate should say so in the DDL, where
both engines read it.

Three smaller divergences round out the list. Division renders bare for the exact fractional quotient, MySQL's `/`
being fractional for every operand type, where PostgreSQL needs `CAST(… AS NUMERIC) /` because its `/` truncates on
two integers, so the same portable expression produces two renderings and one value. The truncating quotient is `DIV`.
Nested concatenations flatten into one variadic `CONCAT` call. And `CAST` accepts a fixed short list of targets, so
`expr.cast[A]` has no rendering for a calendar interval, an offset-carrying time, an array, or an extension type:
types MySQL stores happily are not all types MySQL can cast to.

## Bulk load with `LOAD DATA LOCAL INFILE`

MySQL takes a table's worth of rows in one statement whose data the client uploads over the same connection, rather
than reading a file the server can see. There is no portable equivalent, so it lives on `MysqlClient` rather than on
`SqlClient`. A portable program cannot reach it by accident, and a program that wants it says so at the call site.

### Reaching the MySQL client

`DB.clientAs[MysqlClient]` narrows the client the enclosing `DB.run` supplied, which is the door to every surface
`SqlClient` does not carry. It narrows once, where the program has already committed to the engine, and the concrete
client is held from there:

```scala
val uploaded: Long < (Async & Abort[SqlException]) =
    DB.run("mysql://app:secret@localhost:3306/plant") {
        DB.clientAs[MysqlClient].map { client =>
            client.loadLocalInfile(
                "LOAD DATA LOCAL INFILE 'readings.csv' INTO TABLE reading FIELDS TERMINATED BY ','",
                Stream.init("1,2024-01-01 00:00:00,21.5,0.4\n".getBytes.toSeq)
            )
        }
    }
```

`MysqlClient.use { client => … }` is the same narrowing spelled as one call.

> **Note:** narrowing to the wrong engine is a typed failure, `SqlConnectionBackendMismatchException`, carrying the
> client type asked for and the dialect id of the client actually in force. A program that supplied no client at all
> is a compile error instead, since `DB` is in the effect row.

A program that talks to nothing but MySQL can skip the narrowing and open the concrete client itself. Four factories
do that: `MysqlClient.init` and `MysqlClient.initWith` bind the close to the enclosing `Scope`, and
`MysqlClient.initUnscoped` and `MysqlClient.initUnscopedWith` leave it to the caller, the `With` pair taking a
callback the client is handed to. All four take an optional second `SqlConfig` and return a `MysqlClient`, so
`loadLocalInfile` is reachable without a narrowing step. All four also refuse a scheme this backend does not claim,
with `SqlConnectionUrlParseException` naming the offending scheme: naming the engine in the factory is a statement
about which engine, and a mismatched URL contradicts it rather than silently retargeting.

### The upload and what it promises

`loadLocalInfile` takes the statement and a `Stream[Byte, S]`, and answers with the affected-row count the server
reports. The stream's own effects stay in the result's effect row, so a file-backed load carries what reading a file
carries:

```scala
val fromFile: Long < (Async & Abort[SqlException | FileReadException] & Scope & DB) =
    MysqlClient.use { client =>
        client.loadLocalInfile(
            "LOAD DATA LOCAL INFILE 'readings.csv' INTO TABLE reading FIELDS TERMINATED BY ','",
            Path("readings.csv").readBytesStream
        )
    }
```

The filename in the statement is arbitrary. kyo-sql ignores whatever the server echoes back and always uploads the
caller's stream, so the name is a label the server logs rather than a path anything opens.

> **Caution:** a failure part-way through the stream does not unload what the server already took. The protocol has
> no way to abandon a load once bytes are on the wire, so the upload completes with what was sent and the failure is
> raised after. Run it inside a transaction when the load has to be all-or-nothing.

Transaction routing follows the portable rule. If a transaction is active in the current fiber, the upload runs on
that transaction's connection and a rollback undoes it. Otherwise it leases its own connection for the duration, and
an enclosing transaction on another connection has no say over it.

> **Note:** the `CLIENT_LOCAL_FILES` capability is negotiated automatically, and the server still needs
> `local_infile=ON`. Without it the server rejects the statement and the failure arrives as `SqlServerException`.

An interrupt during a `loadLocalInfile` destroys the connection rather than returning it to the pool. Reaching the
concrete MySQL connection means reaching past the SPI, and past the in-flight window the pool maintains, so the pool
is left with no evidence that a request was outstanding and takes the conservative answer.

### Why an ordinary query will not serve a file

A MySQL server can answer any statement with a `LOCAL INFILE` request, including one a program sent through
`executeRaw` or `simpleQuery`. Answering it there would mean reading a local file because the server asked, at a call
site that never consented to it, so the request is refused with
`SqlRequestMysqlLocalInfileRequiresLoadApiException`. Consent is per call site, and `loadLocalInfile` is the only one
that gives it. The prepared route (`query` and `execute`) has no arm for the request at all: the request's leading
`0xfb` is not a valid result-set header, so it surfaces as a protocol-decode failure,
`SqlConnectionProtocolDecodeException` over a `SqlDecodeProtocolFormatException`, rather than as the refusal above.

## Authentication

Which plugin runs is the server's choice, so the decision a program actually makes is whether its password may cross
the wire in a form only TLS protects. Four plugins are implemented, all in pure Scala, so no platform needs
`javax.crypto` or `java.security.MessageDigest`:

| Plugin | Password on the wire |
|---|---|
| `mysql_native_password` | SHA-1 challenge response, never the password itself. |
| `caching_sha2_password` | SHA-256 challenge response on the fast path, a full-auth round when the server has no cached entry. |
| `sha256_password` | A full-auth round on every connection, the plugin having no server-side cache. |
| `mysql_clear_password` | The password itself, with no hashing and no encryption. |

The full-auth round is where the two SHA-256 plugins differ from their fast path. Over TLS the password goes as
cleartext inside the encrypted channel. Over a plaintext connection it is encrypted to the server's RSA public key
with RSA-OAEP, implemented here in `internal/mysql/auth/RsaOaep.scala`. `caching_sha2_password` needs that round only
when the server has no cached entry for the account, which in practice means the first connection after a server
restart or a password change. `sha256_password` has no cache at all and pays it every time.

`mysql_clear_password` is the one that cannot protect itself, and the handshake enforces TLS before the plugin is
ever called: on a plaintext connection it fails with `SqlConnectionClearPasswordRequiresTlsException` rather than
sending the password in the clear. Configure TLS through the portable `sslmode` URL option or `config.tlsMode`, both
documented in [kyo-sql](../kyo-sql/README.md#engines-and-configuration).

Digests come from core's `internal/auth/PureHash.scala`, which both engines share.

## Configuration

MySQL declares no `SqlConfig.Extension`, so there is no MySQL config type to look for. Every setting this driver
honors is portable, and a `SqlConfig` reaches the pool as the second argument to the same connect call:

```scala
val batched: Chunk[Long] < (Async & Abort[SqlException]) =
    DB.run("mysql://app:secret@localhost:3306/plant", SqlConfig.default.maxConnections(4)) {
        sql"SELECT sensor FROM reading".as[Long].run
    }
```

The pool sizes, timeouts, retry schedule, TLS settings, prepared-statement cache, and URL options are documented in
[kyo-sql](../kyo-sql/README.md#engines-and-configuration).
