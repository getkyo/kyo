# kyo-sql

Pure-Scala async database driver fully integrated with the [Kyo](https://github.com/getkyo/kyo) effect system.

kyo-sql speaks directly to the PostgreSQL and MySQL wire protocols, no JDBC, no third-party driver, no thread
blocking. All I/O runs through `kyo-net`'s async transport layer, which means every query, every row, every
notification arrives in a Kyo fiber with structured cancellation and backpressure.

## Status

### PostgreSQL, feature-complete

- Connect: SCRAM-SHA-256 / MD5 / plaintext password auth
- TLS: `SSLRequest` upgrade; `sslmode=require/verify-ca/verify-full` all enforce TLS (v1 treats them identically; strict cert-check differences are v2)
- Simple query protocol (migrations, multi-statement scripts)
- Extended query protocol (parameterised, binary types, prepared-statement cache per connection)
- Streaming via portals with configurable batch size and backpressure
- Connection pool with `kyo.Channel`-backed FIFO waiter queue
- Transactions with `SAVEPOINT`/`RELEASE`/`ROLLBACK TO` for nested transactions
- `LISTEN`/`NOTIFY` asynchronous notifications over a dedicated connection
- Query cancellation via a fresh sidecar TCP connection and `CancelRequest`

### MySQL, feature-complete

- Connect: `native_password` and `caching_sha2_password` (fast-auth + RSA-OAEP full-auth)
- TLS: `CLIENT_SSL` handshake upgrade
- Simple and extended (binary) query protocols
- Streaming rows packet-by-packet from the wire
- Connection pool with optional `COM_RESET_CONNECTION` on release (`resetOnRelease = true`)
- Transactions with `SAVEPOINT`/`RELEASE`/`ROLLBACK TO` for nested transactions
- Query cancellation via `KILL QUERY <connectionId>` on a pooled sidecar connection

### Runtime targets

JVM is the supported runtime today. The `kyo-sqlJS` and `kyo-sqlNative` artefacts compile but the auth code
paths (SCRAM-SHA-256, MD5, caching_sha2_password RSA-OAEP) depend on JDK crypto (`javax.crypto`), so they
will not run on JS or Native without cross-platform crypto support, that is follow-up work.

---

## Quickstart, PostgreSQL

```scala
import kyo.*
import kyo.sql.*

case class User(id: Long, name: String, email: String) derives CanEqual
given RowDecoder[User] = RowDecoder.derived

val program: Unit < (Async & Abort[SqlException] & Scope) =
  for
    client <- SqlClient.init("postgres://app:secret@localhost:5432/mydb")
    _      <- client.executeRaw(
                "CREATE TABLE IF NOT EXISTS users (id BIGSERIAL PRIMARY KEY, name TEXT, email TEXT)"
              )
    _      <- client.execute(sql"INSERT INTO users (name, email) VALUES (${"Alice"}, ${"alice@example.com"})")
    rows   <- client.query(sql"SELECT id, name, email FROM users WHERE name = ${"Alice"}")
    users  <- Kyo.foreach(rows)(row => RowDecoder[User].decode(row))
    _       = users.foreach(u => println(s"User: $u"))
  yield ()

// Run it:
KyoApp.run:
  Scope.run(Abort.run(program).map(_.getOrElse(())))
```

---

## Quickstart, MySQL

```scala
import kyo.*
import kyo.sql.*

val program: Unit < (Async & Abort[SqlException] & Scope) =
  for
    client <- SqlClient.init("mysql://app:secret@localhost:3306/mydb")
    _      <- client.executeRaw(
                "CREATE TABLE IF NOT EXISTS users (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255), email VARCHAR(255))"
              )
    _      <- client.execute(sql"INSERT INTO users (name, email) VALUES (${"Bob"}, ${"bob@example.com"})")
    rows   <- client.query(sql"SELECT id, name, email FROM users WHERE name = ${"Bob"}")
    _       = rows.foreach(row => println(row))
  yield ()

KyoApp.run:
  Scope.run(Abort.run(program).map(_.getOrElse(())))
```

---

## Connection URLs

### PostgreSQL

```
postgres://user:password@host:port/database[?options]
```

| Option              | Description                                         |
|---------------------|-----------------------------------------------------|
| `sslmode=disable`   | Plaintext only. Default when `sslmode` is absent.   |
| `sslmode=allow`     | Try TLS but fall back to plaintext (v1: plaintext). |
| `sslmode=prefer`    | Prefer TLS but allow plaintext (v1: plaintext).     |
| `sslmode=require`   | TLS required; JDK default trust store.              |
| `sslmode=verify-ca` | TLS required (v1: identical to `require`).          |
| `sslmode=verify-full` | TLS required (v1: identical to `require`).        |
| `application_name`  | Sets `application_name` startup parameter.          |
| `connect_timeout`   | TCP connection timeout in seconds.                  |
| `socket_timeout`    | Idle socket timeout in seconds.                     |

Example with TLS:
```
postgres://app:secret@db.prod.example.com:5432/orders?sslmode=require
```

### MySQL

```
mysql://user:password@host:port/database[?options]
```

MySQL uses the same option keys as PostgreSQL. Auth plugin (native_password vs caching_sha2_password) is
negotiated automatically with the server, you do not need to configure it.

---

## Queries

kyo-sql offers three layers. Use whichever fits the task.

### 1. Typed DSL

Derives schema from a case class. Fully typed, column names and types are checked at compile time.

```scala
import kyo.*
import kyo.sql.*

case class Product(id: Long, name: String, price: BigDecimal, inStock: Boolean)

// Build a query using the typed DSL:
val q = from[Product]("products", "p")
          .where(_.p.inStock == Expr.Lit(true))
          .select(_.p.name, _.p.price)
          .orderBy(_.p.price.asc)
          .limit(20)

// Execute:
client.query(q)  // : Chunk[Row] < (Async & Abort[SqlException])
```

Supported combinators:
- `.where(pred)`, appends a WHERE clause
- `.select(cols*)`, projects columns; defaults to SELECT *
- `.join[B]("table", "alias").on(cond)`, INNER JOIN
- `.leftJoin[B]("table", "alias").on(cond)`, LEFT JOIN
- `.orderBy(cols*)`, ORDER BY with `.asc` / `.desc`
- `.groupBy(cols*)`, GROUP BY
- `.having(pred)`, HAVING (requires `groupBy` first)
- `.limit(n)` / `.offset(n)`, LIMIT / OFFSET
- `.distinct`, SELECT DISTINCT

Aggregate functions: `count(expr)`, `sum(expr)`, `avg(expr)`, `min(expr)`, `max(expr)`, `countAll`.

### 2. `sql"..."` interpolator

A compile-time macro that tracks the types of all interpolated values. Values of the default scalar types
(Int, Long, String, Boolean, BigDecimal, etc.) are erased at compile time, no encoder lookup needed at runtime.
Values of custom types generate a compile-time error if no encoder is in scope.

```scala
val minPrice = BigDecimal("9.99")
val category = "Electronics"

val frag = sql"SELECT id, name FROM products WHERE price > $minPrice AND category = $category"
client.query(frag)
```

Fragments compose with `++`:
```scala
val base   = sql"SELECT id FROM orders WHERE user_id = ${userId}"
val filter = sql" AND status = ${"shipped"}"
client.query(base ++ filter)
```

### 3. Raw SQL

For migrations, DDL, multi-statement scripts, or anything that the typed DSL does not cover.

```scala
client.executeRaw("""
  CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_orders_user
  ON orders (user_id)
""")
```

`executeRaw` uses the simple-query protocol and returns the affected-row count. It does not support
bind parameters; use the `sql"..."` interpolator for parameterised statements.

### DDL helpers

```scala
case class Order(id: Long, userId: Long, total: BigDecimal)

val ddl: Fragment[Unit, Nothing] = DDL.createTable[Order]("orders")
client.executeDdl(ddl)
```

`DDL.createTable[A]` derives column types from the case class fields at compile time.

---

## Lift mechanism

When you write `sql"... ${expr} ..."`, the macro inspects the type of `expr` at compile time:

- **Default type** (`Int`, `Long`, `String`, `Boolean`, `BigDecimal`, `Span[Byte]`, `kyo.Instant`,
  `java.time.LocalDate`, `java.time.LocalDateTime`), erased to `Default.Value`; no backend-specific
  encoder is needed; same fragment works for both Postgres and MySQL.

- **Custom type** enters the `Custom` union of the `Fragment` type. The execute macro summons a
  backend-specific encoder at the call site. If no encoder is found, the macro fails at compile time
  with the missing type and the source position of the `${...}` lift.

### Opaque ID types

The `mappedPostgresEncoder` and `mappedPostgresDecoder` givens (importable from `kyo.sql.internal`)
propagate encoders and decoders through a `Conversion`:

```scala
opaque type UserId = Long
object UserId:
  def apply(v: Long): UserId = v
  given Conversion[UserId, Long] = identity
  given Conversion[Long, UserId] = UserId(_)
  // mappedPostgresEncoder and mappedPostgresDecoder kick in automatically.

import kyo.sql.internal.{mappedPostgresEncoder, mappedPostgresDecoder}

val userId: UserId = UserId(42L)
client.query(sql"SELECT * FROM users WHERE id = ${userId}")
```

---

## Row decoding

### RowDecoder derivation

`RowDecoder[A]` decodes a `Row` into a case class. Derive automatically using `RowDecoder.derived`:

```scala
case class Person(id: Long, name: String, age: Int) derives CanEqual
given RowDecoder[Person] = RowDecoder.derived

val rows: Chunk[Row] = ... // from client.query(...)
val people: Chunk[Person] < Abort[SqlException.Decode] =
  Kyo.foreach(rows)(RowDecoder[Person].decode(_))
```

Column matching is case-insensitive by name. Nested case classes flatten using snake_case:
field `address: Address` with sub-fields `street` and `city` expects columns `address_street` and
`address_city`.

For `Maybe[A]`-typed fields, a NULL column becomes `Maybe.Absent` instead of an error.

### Positional decoding

When column names don't match field names (custom projections):

```scala
val byPos: RowDecoder[Person] = RowDecoder.positional[Person]
```

### Single-column decoders

Built-in `RowDecoder` instances for `Long`, `Int`, `Short`, `String`, `Boolean`, `Double`, `Float`,
`BigDecimal`, `Span[Byte]`, and `kyo.Instant` decode single-column results without derivation.

### Manual column access

```scala
val row: Row = ...
val name: String < Abort[SqlException.Decode] = row.columnAs[String]("name")
val id:   Long   < Abort[SqlException.Decode] = row.columnAs[Long](0)
```

---

## Streaming

`streamQuery` returns a `Stream[Row, Async & Abort[SqlException] & Scope]`. PostgreSQL uses portals
with configurable batch size; MySQL streams rows packet-by-packet from the wire.

```scala
// Stream all rows in batches of 100 (Postgres portal fetch size):
val stream: Stream[Row, Async & Abort[SqlException] & Scope] =
  client.streamQuery(sql"SELECT * FROM large_table", batchSize = 100)

// Consume with backpressure:
Scope.run:
  stream.runForeach { row =>
    // processed one row at a time; server delivers next batch only when asked
    processRow(row)
  }
```

Streaming inside a transaction reuses the bound connection automatically.

---

## Transactions

```scala
client.transaction() {
  for
    _ <- client.execute(sql"UPDATE accounts SET balance = balance - ${amount} WHERE id = ${from}")
    _ <- client.execute(sql"UPDATE accounts SET balance = balance + ${amount} WHERE id = ${to}")
  yield ()
}
```

- On success: sends `COMMIT`.
- On `Abort[SqlException]`: sends `ROLLBACK` and re-raises.
- On unhandled exception (Panic): sends `ROLLBACK` and re-raises.

### Isolation levels

```scala
import kyo.sql.IsolationLevel

client.transaction(isolation = Maybe.Present(IsolationLevel.Serializable)) { ... }
client.transaction(isolation = Maybe.Present(IsolationLevel.RepeatableRead), readOnly = true) { ... }
```

Available levels: `ReadUncommitted`, `ReadCommitted`, `RepeatableRead`, `Serializable`.

### Nested transactions (savepoints)

Nested `transaction { ... }` calls automatically use `SAVEPOINT`:

```scala
client.transaction() {          // BEGIN
  client.transaction() {        // SAVEPOINT sp_xxx
    client.execute(sql"...")
  }                             // RELEASE SAVEPOINT sp_xxx (or ROLLBACK TO on failure)
}                               // COMMIT (or ROLLBACK on failure)
```

The inner call reuses the outer connection. Its `isolation` parameter is ignored (savepoints do not
support per-savepoint isolation levels).

---

## Pool configuration

```scala
val config = SqlClientConfig(
  maxConnections      = 20,
  acquireTimeout      = 5.seconds,
  queryTimeout        = 30.seconds,
  idleTimeout         = 10.minutes,
  retries             = 0,
  retryDelay          = 1.second,
  tls                 = Maybe.Absent,       // or Maybe.Present(NetTlsConfig.default) for TLS
  preparedStmtCacheSize = 64,
  preparedStmtTtl     = Duration.Infinity,
  resetOnRelease      = false,              // MySQL only: send COM_RESET_CONNECTION on release
  cancelTimeout       = 2.seconds          // MySQL only: timeout for acquiring a cancel connection
)

val client = SqlClient.init("postgres://...", config)
```

### `SqlClientConfig` reference

| Field                  | Default          | Description                                                                    |
|------------------------|------------------|--------------------------------------------------------------------------------|
| `maxConnections`       | 10               | Maximum concurrent connections in the pool.                                    |
| `acquireTimeout`       | 5 seconds        | Time to wait for a connection before raising `SqlException.Connection`.        |
| `queryTimeout`         | 30 seconds       | Maximum time for a single query/execute.                                       |
| `idleTimeout`          | 10 minutes       | Maximum idle time before a connection is closed.                               |
| `retries`              | 0                | Automatic retry count (0 = fail immediately).                                  |
| `retryDelay`           | 1 second         | Base delay between retries (exponential backoff).                              |
| `tls`                  | `Absent`         | TLS configuration. `Absent` = plaintext.                                       |
| `preparedStmtCacheSize`| 64               | LRU cache size for server-side prepared statements per connection.             |
| `preparedStmtTtl`      | `Duration.Infinity` | TTL for cached prepared statements. `Infinity` = evict on capacity only.    |
| `resetOnRelease`       | `false`          | (MySQL) Send `COM_RESET_CONNECTION` before returning to pool.                 |
| `cancelTimeout`        | 2 seconds        | (MySQL) Timeout when acquiring a cancel connection.                            |

### TLS

```scala
import kyo.net.NetTlsConfig

// JDK default trust store (production CA-signed certificates):
val tlsDefault = NetTlsConfig.default

// Trust all certificates (development / self-signed):
val tlsTrustAll = NetTlsConfig(trustAll = true)

val client = SqlClient.init(
  "postgres://app:secret@prod.db:5432/orders?sslmode=require",
  SqlClientConfig.default.copy(tls = Maybe.Present(tlsDefault))
)
```

URL `sslmode=require` also enables TLS automatically (with JDK default trust). Programmatic `config.tls`
takes precedence over the URL when both are set.

---

## Cancellation

```scala
for
  (handle, rows) <- client.cancellableQuery(sql"SELECT pg_sleep(30)")
  _              <- Fiber.init:
                      Async.sleep(2.seconds).andThen(client.cancel(handle))
yield rows
```

`cancellableQuery` returns `(CancelHandle, Chunk[Row])`. Pass the handle to `cancel` from another fiber
while the query is in-flight. If the query finishes first, `cancel` is a no-op.

- **PostgreSQL**: opens a fresh TCP sidecar connection, sends a 16-byte `CancelRequest`. The server
  interrupts the query and the query fiber receives `SqlException.Server` with SQLSTATE `57014`.
- **MySQL**: acquires a connection from the pool, sends `KILL QUERY <connectionId>`, releases it.
  The query fiber receives `SqlException.Server` with SQLSTATE `70100`.

---

## LISTEN / NOTIFY (PostgreSQL only)

```scala
val stream: Stream[Notification, Async & Abort[SqlException] & Scope] =
  client.notifications("order_events")

Scope.run:
  stream.runForeach { notif =>
    println(s"Channel=${notif.channel} PID=${notif.processId} Payload=${notif.payload}")
  }

// Send a notification from another connection:
client.executeRaw("NOTIFY order_events, 'order_123_shipped'")
```

`notifications(channel)` acquires a dedicated connection, sends `LISTEN <channel>`, and streams
`Notification(channel, payload, processId)` values. When the `Scope` exits, `UNLISTEN` is sent and
the connection returns to the pool.

---

## Error handling

All failures surface as `Abort[SqlException]`. The hierarchy:

| Variant                 | Cause                                                                           |
|-------------------------|---------------------------------------------------------------------------------|
| `SqlException.Connection` | TCP refused, pool exhausted, TLS handshake failed, acquire timeout            |
| `SqlException.Request`  | Client-side error: missing encoder, parameter serialization, protocol mismatch  |
| `SqlException.Server`   | Error response from the database (SQLSTATE + severity + message + detail)       |
| `SqlException.Decode`   | Row decoded from the server but could not be mapped to the expected Scala type  |

```scala
Abort.run[SqlException](client.query(sql"SELECT 1")).flatMap {
  case Result.Success(rows)                                => println(s"Got ${rows.size} rows")
  case Result.Failure(e: SqlException.Server) if e.sqlState == "23505" =>
    println("Unique-violation, duplicate key")
  case Result.Failure(e) =>
    println(s"Query failed: ${e.getMessage}")
  case Result.Panic(t) =>
    // Unexpected runtime exception, always log these
    java.lang.System.err.println(s"Panic: ${t.getMessage}")
}
```

---

## Type support

Default types (no custom encoder needed):

| Scala type              | PostgreSQL wire type       | MySQL wire type            |
|-------------------------|----------------------------|----------------------------|
| `Int`                   | `int4` (binary)            | `INT` (binary)             |
| `Long`                  | `int8` (binary)            | `BIGINT` (binary)          |
| `String`                | `text` (text)              | `VARCHAR`/`TEXT` (text)    |
| `Boolean`               | `bool` (binary)            | `TINYINT(1)` (binary)      |
| `BigDecimal`            | `numeric` (text)           | `DECIMAL` (text)           |
| `Span[Byte]`            | `bytea` (binary)           | `BLOB` (binary)            |
| `kyo.Instant`           | `timestamptz` (binary)     | `DATETIME` (text)          |
| `java.time.LocalDate`   | `date` (text ISO-8601)     | `DATE` (text)              |
| `java.time.LocalDateTime` | `timestamp` (text)       | `DATETIME` (text)          |

Custom types require a `PostgresEncoder[A]` (for Postgres) or `MysqlEncoder[A]` (for MySQL) in scope
at the `execute` / `query` call site. The compile-time macro tells you exactly which type and lift
position is missing.

---

## Architecture

- **Wire protocol**: pure Scala implementation of the PostgreSQL frontend-backend protocol v3 and the
  MySQL client-server protocol v10 (MySQL 8.x). No JDBC.
- **Transport**: all I/O through `kyo-net`'s safe `Connection` API (async, non-blocking, completion-based).
  The network layer is shared with `kyo-http`.
- **Prepared-statement cache**: per-connection LRU cache (`kyo.Cache`) keyed by SQL text. The server-side
  statement is reused across requests on the same connection; eviction is capacity-based (CLOCK algorithm).
- **Pool backpressure**: `kyo.Channel`-backed FIFO waiter queue inside `kyo.net.ConnectionPool`. Fibers
  that cannot acquire a connection immediately suspend and are resumed in order as connections are released.

Reference design documents (in `kyo-sql/`):
- `DESIGN-SYNTHESIS.md`, authoritative design decisions and trade-offs
- `RESEARCH-PROTOCOLS.md`, PostgreSQL and MySQL wire-protocol reference
- `RESEARCH-NDBC-LESSONS.md`, lessons from studying the ndbc driver

---

## Limitations / known issues (v1)

| Limitation | Notes |
|---|---|
| Auth crypto is JVM-only | SCRAM-SHA-256, MD5, and caching_sha2_password RSA use `javax.crypto`. JS/Native compile but won't connect. Cross-platform crypto is follow-up work. |
| `verify-ca` / `verify-full` = `require` | v1 treats them all as "TLS required, JDK default trust". Strict cert-chain and hostname-check differences are v2. |
| No pipeline mode | PostgreSQL pipeline mode (≥PG14) is not yet implemented. Each connection processes one in-flight message at a time. v2. |
| MySQL LOCAL INFILE rejected | Requires security review before enabling. |
| No PostgreSQL COPY protocol | `COPY … FROM STDIN` / `COPY … TO STDOUT` are v2. |
| No SCRAM channel binding | `SCRAM-SHA-256-PLUS` (channel binding) is v2. |
| No `allow` / `prefer` TLS upgrade | Opportunistic TLS for `sslmode=allow/prefer` is v2. v1 treats them as plaintext. |
| TLS test flake in full-suite runs | One PostgreSQL TLS integration test occasionally times out when the full test suite runs all containers concurrently (resource contention between Podman containers). The test passes consistently in isolation. Mitigated by `Test / parallelExecution := false` in the kyo-sql build config. |

---

## Troubleshooting

### `SqlException.Connection: pool exhausted, no connection available within 5 seconds`

The pool is saturated. Options:
- Increase `maxConnections`.
- Increase `acquireTimeout`.
- Check for connection leaks (connections not released when the scope exits).

### `SqlException.Connection: Server at host:port does not support TLS`

The server responded `N` to the SSLRequest. Either disable TLS (`sslmode=disable`) or configure the
server to support TLS.

### `SqlException.Server: [28P01] FATAL: password authentication failed for user "..."`

Wrong password, or the user does not exist. Check the credentials in the URL.

### `SqlException.Server: [57014] ERROR: canceling statement due to user request`

The query was cancelled via `SqlClient.cancel`. This is expected when cancellation is intentional.

### `SqlException.Server: [23505] ERROR: duplicate key value violates unique constraint "..."`

Unique-constraint violation. Catch this specific SQLSTATE in your error handler.

### `SqlException.Decode: Column 'xyz' not found in row`

The query did not return the expected column. Check the SELECT list or use `RowDecoder.positional`.

### `SqlException.Decode: Column 'xyz' is NULL but field is non-optional`

The column returned NULL but the Scala field is not `Maybe[A]`. Change the field type to `Maybe[A]`
or ensure the column is NOT NULL in the schema.

### Compile error: `No given instance of type PostgresEncoder[MyCustomType]`

The `sql"..."` interpolation contains a custom type without a registered encoder. Provide:
```scala
given PostgresEncoder[MyCustomType] = ...
// or use MappedEncoder if you have a Conversion[MyCustomType, SomePrimitiveType]:
import kyo.sql.internal.mappedPostgresEncoder
given Conversion[MyCustomType, Long] = _.value
```

### Tests time out with multiple TLS containers

Integration tests are configured with `parallelExecution := false` to avoid resource races between
Podman containers. If you see timeouts in CI, ensure the CI agent has enough memory for the containers
running sequentially.
