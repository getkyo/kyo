# kyo-config

Type-safe, cross-platform configuration flags for Kyo applications. Flags are Scala objects whose fully-qualified name becomes the config key. Two kinds are provided:

- **StaticFlag** resolves once at class load. Use for infrastructure settings that should not change at runtime -- pool sizes, timeouts, codec selections.
- **DynamicFlag** evaluates per call with a caller-provided key. Use for values that must vary per request or change without restart -- feature gates, A/B tests, per-tenant rate limits.

Both share the same declaration style, typed parsing, and validation. Flags can also vary by deployment topology and percentage sampling through a rollout expression DSL (covered in a dedicated section below). Configuration is read from system properties, environment variables, or defaults -- in that priority order.

## Getting Started

Add the dependency to your `build.sbt`:

```scala
libraryDependencies += "io.getkyo" %% "kyo-config" % "<latest version>"
```

## Static Flags

### Declaring Flags

Flags are declared as Scala objects. The fully-qualified object name becomes the configuration key:

```scala
package myapp.db

import kyo.*

object poolSize   extends StaticFlag[Int](10)
object maxRetries extends StaticFlag[Int](3)
object jdbcUrl    extends StaticFlag[String]("jdbc:h2:mem:test")
```

`myapp.db.poolSize` is now a config key that reads from the system property `-Dmyapp.db.poolSize=20` or the environment variable `MYAPP_DB_POOLSIZE=20`.

### Reading Values

A static flag's value is available immediately -- it's just a field read:

```scala
val size: Int = myapp.db.poolSize()
val url: String = myapp.db.jdbcUrl()
```

### Configuration Sources

Flags resolve in this order:

1. **System property** -- `-Dmyapp.db.poolSize=20`
2. **Environment variable** -- `MYAPP_DB_POOLSIZE=20` (dots become underscores, uppercased)
3. **Default** -- the value passed to the constructor

The first source found wins. When a flag resolves to its default, kyo-config scans for near-miss system properties (case-insensitive) and prints a warning to stderr. This catches typos like `-Dmyapp.db.PoolSize` when the flag expects `myapp.db.poolSize`.

### Validation

An optional validation function transforms or constrains the resolved value:

```scala
package myapp.db

import kyo.*

// Clamp pool size to at least 1 and at most 100
object poolSize extends StaticFlag[Int](10, n => Right(math.max(1, math.min(n, 100))))

// Ensure the URL is non-empty
object jdbcUrl extends StaticFlag[String]("jdbc:h2:mem:test", url =>
  if url.nonEmpty then Right(url)
  else Left(new IllegalArgumentException("URL must not be empty"))
)
```

The validate parameter has signature `A => Either[Throwable, A]`. Validation runs at class load time. A failure throws a `FlagValidationFailedException` and crashes the process before serving traffic -- bad config is caught at startup.

### Typed Parsing

Built-in `Flag.Reader` instances handle common types:

| Type | Example |
|------|---------|
| `Int` | `-Dmyapp.db.poolSize=20` |
| `Long` | `-Dmyapp.db.ttlMs=60000` |
| `Double` | `-Dmyapp.db.ratio=1.5` |
| `Boolean` | `-Dmyapp.features.debug=true` |
| `String` | `-Dmyapp.db.jdbcUrl=jdbc:h2:mem` |
| `Seq[A]` | `-Dmyapp.db.hosts=host1,host2,host3` |

The `kyo-data` module adds readers for Kyo's data types (available when `kyo-data` is on the classpath):

| Type | Format | Example |
|------|--------|---------|
| `Duration` | number + unit | `"5s"`, `"100ms"`, `"2minutes"`, `"infinity"` |
| `Chunk[A]` | comma-separated | `"a,b,c"` |
| `Span[A]` | comma-separated | `"1,2,3"` |
| `Dict[K,V]` | key=value pairs | `"host=localhost,port=8080"` |
| `Instant` | ISO-8601 | `"2024-01-15T10:30:00Z"` |
| `Text` | plain string | `"hello"` |
| `Record[F]` | key=value pairs, validated against field schema | `"host=localhost,port=8080"` |

The `Record` reader is derived at compile time — it summons a `Flag.Reader` for each field's value type and validates that all required fields are present. This lets you define structured, multi-field configuration as a single flag:

```scala
package myapp.db

import kyo.*

// A typed database config with three fields, each parsed and validated independently
object connection extends StaticFlag[Record["host" ~ String & "port" ~ Int & "timeout" ~ Duration]](
  "host" ~ "localhost" & "port" ~ 5432 & "timeout" ~ Duration.fromUnits(30, Duration.Units.Seconds)
)
```

Configure via system property:
```
-Dmyapp.db.connection="host=db.prod.internal,port=5432,timeout=5s"
```

Field order in the expression doesn't matter. Missing fields produce a clear error listing the absent names. Each field value is parsed by its own `Flag.Reader` — so `port` must be a valid `Int`, `timeout` must be a valid `Duration`, and type errors are reported per field.

Records compose with the rollout DSL:
```
-Dmyapp.db.connection="host=db-us.prod,port=5432,timeout=5s@prod/us-east-1;host=db.prod,port=5432,timeout=10s@prod;host=localhost,port=5432,timeout=30s"
```

For custom types, implement a `Reader`:

```scala
import kyo.*

case class Endpoint(host: String, port: Int)

given Flag.Reader[Endpoint] = new Flag.Reader[Endpoint]:
  def apply(s: String): Endpoint =
    val parts = s.split(":")
    Endpoint(parts(0), parts(1).toInt)
  def typeName: String = "Endpoint"
```

Parse errors at class load time throw a `FlagValueParseException`, failing fast before the application serves traffic.

## Dynamic Flags

### Declaring Flags

Dynamic flags follow the same declaration pattern:

```scala
package myapp.features

import kyo.*

object newCheckout extends DynamicFlag[Boolean](false)
object rateLimit   extends DynamicFlag[Int](100)
```

### Evaluating Per Entity

Dynamic flags evaluate against a key (typically a user ID or tenant ID) and optional attributes for path-based matching:

```scala
val enabled: Boolean = myapp.features.newCheckout("user-123")
val limit: Int = myapp.features.rateLimit("tenant-abc", "premium")
```

The key identifies the entity being evaluated. Attributes provide additional context for matching (see the Rollout DSL section below for how keys and attributes are used in rollout expressions).

Each call to `apply` also tracks evaluation counters (bounded at 100 distinct result values). For hot loops where counter overhead matters, use `evaluate` instead:

```scala
val enabled: Boolean = myapp.features.newCheckout.evaluate("user-123")
```

### Runtime Updates

Dynamic flags can be updated at runtime. Since `update` and `reload` perform side effects, they require an `AllowUnsafe` evidence:

```scala
myapp.features.newCheckout.update("true")
myapp.features.rateLimit.update("200")
```

The new value is parsed and validated atomically -- if anything fails, the old state is preserved.

The current expression is always available:

```scala
val expr: String = myapp.features.newCheckout.expression
```

### Reloading from Config Source

`reload()` re-reads the expression from the original config source (system property or environment variable):

```scala
myapp.features.newCheckout.reload() match
  case Flag.ReloadResult.Updated(expr) =>
    println(s"Updated to: $expr")
  case Flag.ReloadResult.Unchanged =>
    println("Expression unchanged")
  case Flag.ReloadResult.NoSource =>
    println("No config source found")
```

## Rollout DSL

Both `StaticFlag` and `DynamicFlag` support a rollout expression DSL for conditional values. Rollout expressions let you vary a flag's value by deployment topology (environment, region, cluster) and percentage-based sampling -- without changing code or restarting the process.

### Grammar

```
expression = choice { ";" choice }
choice     = value "@" selector
           | value                    (* terminal -- always matches *)
selector   = [ path "/" ] percentage
           | path
path       = segment { "/" segment }
segment    = "*"                      (* wildcard -- matches any single segment *)
           | literal                  (* exact match *)
percentage = digits "%"
```

**Components:**

- **expression** — one or more choices separated by `;`. Evaluated left to right, first match wins.
- **choice** — a value paired with a selector (`value@selector`), or a bare value with no `@` (a **terminal** that always matches and stops evaluation).
- **value** — the flag value to use if this choice matches. Parsed by the flag's `Flag.Reader`.
- **selector** — a path, a percentage, or both. Determines whether this choice applies to the current instance or entity.
- **path** — one or more segments separated by `/`. Matched as a prefix against the target path. `prod/us-east-1` matches any path starting with those two segments.
- **segment** — either `*` (wildcard, matches any single path component) or a literal string (exact match).
- **percentage** — a trailing `N%` on a selector. Controls what fraction of entities receive this choice (see Percentage Weights below).
- **terminal** — a choice with no `@`. Always matches. Acts as a default within the expression. Only needed when the desired fallback differs from the flag's constructor default.

### Matching Rules

Choices are evaluated left to right. The first match wins. If no choice matches, the flag's default value is used.

```
-Dmyapp.db.poolSize="50@prod/us-east-1;30@prod;10"
```

For the expression above, evaluation proceeds as follows:

1. Try `50@prod/us-east-1` -- does the path start with `prod/us-east-1`?
2. If not, try `30@prod` -- does the path start with `prod`?
3. If not, fall through to `10` (terminal, always matches)

### Path Matching

Selectors use prefix matching against path segments separated by `/`.

For **StaticFlag**, the path comes from the instance's rollout path, configured via `-Dkyo.rollout.path=prod/us-east-1/az1` or the environment variable `KYO_ROLLOUT_PATH`. When unset, the path is auto-detected from Kubernetes, AWS, or GCP environment variables.

For **DynamicFlag**, the path comes from the attributes passed by the caller:

```scala
// -Dmyapp.features.rateLimit="200@premium;50@free;100"
myapp.features.rateLimit("tenant-abc", "premium") // 200
myapp.features.rateLimit("tenant-xyz", "free")    // 50
myapp.features.rateLimit("tenant-def")            // 100 (terminal)
```

Multi-segment paths match as a prefix:

```
-Dmyapp.db.poolSize="50@prod/us-east-1;30@prod;10"
```

| Instance path | Result | Why |
|---------------|--------|-----|
| `prod/us-east-1/az1` | 50 | `prod/us-east-1` is a prefix |
| `prod/eu-west-1/az2` | 30 | `prod` is a prefix |
| `staging/us-east-1` | 10 | No prefix match, terminal fallback |

### Wildcards

`*` matches any single path segment:

```
-Dmyapp.db.poolSize="50@prod/*/az1;30@prod;10"
```

| Instance path | Result | Why |
|---------------|--------|-----|
| `prod/us-east-1/az1` | 50 | `*` matches `us-east-1`, `az1` matches exactly |
| `prod/eu-west-1/az1` | 50 | `*` matches `eu-west-1`, `az1` matches exactly |
| `prod/us-east-1/az2` | 30 | `az2` does not match `az1`, falls through to `prod` |
| `staging/us-east-1/az1` | 10 | `staging` does not match `prod` |

### Percentage Weights

A trailing `N%` on a selector controls what fraction of entities receive that value. Percentages are **weights**, not thresholds -- each percentage specifies the size of a bucket range, and they accumulate left to right.

```
-Dmyapp.features.newCheckout="true@30%;false"
```

This means: 30% of entities (by deterministic bucketing of their key) get `true`. The remaining 70% fall through to `false`.

The distinction between weights and thresholds matters when multiple percentage choices appear:

```
-Dmyapp.features.variant="A@30%;B@30%;C"
```

| Choice | Weight | Bucket range |
|--------|--------|--------------|
| `A@30%` | 30% | 0-29 |
| `B@30%` | 30% | 30-59 |
| `C` | terminal | 60-99 (everyone else) |

Each weight carves out its own slice of the 0-99 bucket space. Users write simple weights (30%, 30%) and the DSL computes the cumulative ranges internally.

### Multi-Arm Experiments

Percentage weights make it straightforward to run multi-arm experiments:

```
-Dmyapp.features.checkoutVariant="A@25%;B@25%;C@25%;D"
```

Four equal groups: A gets buckets 0-24, B gets 25-49, C gets 50-74, and D (terminal) gets 75-99.

An unequal split for a holdout experiment:

```
-Dmyapp.features.pricing="new@80%;control@10%;holdout"
```

80% see the new pricing, 10% see the control, and the remaining 10% are the holdout group.

### Combining Paths and Percentages

Path matching and percentages compose within a single selector:

```
-Dmyapp.features.newCheckout="true@premium/50%;true@free/10%;false"
```

50% of premium users and 10% of free users get `true`. Everyone else gets `false`. The path must match first; then the percentage filter is applied within that path.

### Progressive Rollout

Increasing the percentage adds entities without removing existing ones. A typical rollout progression:

| Day | Expression | Effect |
|-----|------------|--------|
| 1 | `true@5%;false` | 5% of users (buckets 0-4) |
| 2 | `true@25%;false` | 25% of users (buckets 0-24) |
| 3 | `true@50%;false` | 50% of users (buckets 0-49) |
| 4 | `true@75%;false` | 75% of users (buckets 0-74) |
| 5 | `true` | 100% of users (terminal) |

Bucketing is deterministic per key (via MurmurHash3), so a user who was included at 5% stays included at 25%. The bucket range grows from the same starting point, making progressive rollouts additive.

However, **decreasing** a percentage can remove entities -- going from 75% back to 50% drops buckets 50-74, removing 25% of previously included users.

### Rollout with StaticFlag

StaticFlag evaluates the rollout expression once at class load time. The path comes from `kyo.rollout.path` (or auto-detected cloud metadata), and the bucket is derived from hashing that path:

```scala
package myapp.db

import kyo.*

// -Dkyo.rollout.path=prod/us-east-1/az1
// -Dmyapp.db.poolSize="50@prod/us-east-1;30@prod;10"
object poolSize extends StaticFlag[Int](10)
```

On an instance with path `prod/us-east-1/az1`, `poolSize()` resolves to `50` at startup and never changes.

### Cloud Topology Auto-Detection

When `kyo.rollout.path` is not explicitly set, Rollout auto-detects the topology path from cloud provider environment variables. Detection runs once at startup. The first matching provider wins.

**Kubernetes** (triggered by `KUBERNETES_SERVICE_HOST`):

| Env var | Source | Example |
|---------|--------|---------|
| `POD_NAMESPACE` or `KUBE_NAMESPACE` | [Downward API](https://kubernetes.io/docs/concepts/workloads/pods/downward-api/) | `production` |
| `NODE_NAME` | Downward API | `ip-10-0-1-42` |
| `HOSTNAME` | Set by container runtime | `my-pod-abc123` |

Example path: `production/ip-10-0-1-42/my-pod-abc123`

Kubernetes does not expose region or AZ as environment variables. Inject `POD_NAMESPACE` and `NODE_NAME` via the Downward API in your pod spec, or set `kyo.rollout.path` explicitly.

**AWS** (triggered by `AWS_REGION`):

| Env var | Source | Example |
|---------|--------|---------|
| `AWS_REGION` | [AWS SDK config](https://docs.aws.amazon.com/sdkref/latest/guide/setting-global-aws_region.html) | `us-east-1` |
| `ECS_CLUSTER` | [ECS agent](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs-agent-config.html) | `my-cluster` |
| `HOSTNAME` | Set by container runtime | `ip-10-0-1-42` |

Example path: `us-east-1/my-cluster/ip-10-0-1-42`

**GCP** (triggered by `GOOGLE_CLOUD_PROJECT`):

| Env var | Source | Example |
|---------|--------|---------|
| `GOOGLE_CLOUD_PROJECT` | [GCP runtime](https://cloud.google.com/run/docs/reference/container-contract#env-vars) | `my-project` |
| `GOOGLE_CLOUD_REGION` | Cloud Run / GCE | `us-central1` |
| `K_SERVICE` | [Cloud Run / Knative](https://cloud.google.com/run/docs/reference/container-contract#env-vars) | `my-service` |
| `HOSTNAME` | Set by container runtime | `my-service-abc123` |

Example path: `my-project/us-central1/my-service/my-service-abc123`

**Generic fallback** (none of the above):

| Env var | Example |
|---------|---------|
| `ENV` or `ENVIRONMENT` | `production` |
| `REGION` | `us-east-1` |
| `HOSTNAME` | `my-host` |

Missing segments are skipped. For example, if only `AWS_REGION=us-east-1` is set, the path is just `us-east-1`.

### Rollout with DynamicFlag

DynamicFlag evaluates the rollout expression on every call. The path comes from the caller's attributes, and the bucket is derived from hashing the caller's key:

```scala
package myapp.features

import kyo.*

// -Dmyapp.features.newCheckout="true@premium/50%;false"
object newCheckout extends DynamicFlag[Boolean](false)

// At call site:
newCheckout("user-123", "premium") // key="user-123", path=["premium"]
```

The key `"user-123"` determines the bucket. The attribute `"premium"` is matched against the path selector. If the path matches and the bucket falls within the 50% weight, the result is `true`.

Dynamic flags also accept rollout expressions via `update()`:

```scala
myapp.features.newCheckout.update("true@premium/75%;true@free/25%;false")
```

### Validating Expressions

`Rollout.validate` checks an expression without evaluating it, returning warnings and errors:

```scala
import kyo.*

Rollout.validate("true@50%;false") match
  case Right(warnings) => warnings.foreach(println)
  case Left(error)     => println(s"Error: $error")
```

It catches:

- Empty values before `@`
- Bad percentages (negative, non-numeric)
- Unreachable choices after a terminal
- Weights summing over 100% (warning)
- Numeric path segments without `%` (warning: "did you mean N%?")
- Empty path segments (double slash)

### Debugging Buckets

`Rollout.bucketFor` shows the deterministic bucket (0-99) for any key:

```scala
import kyo.*

val bucket: Int = Rollout.bucketFor("user-123") // e.g., 47
```

Same key always produces the same bucket across platforms and process restarts. Use this to verify which bucket a specific user or tenant falls into during debugging.

## Choosing Between StaticFlag and DynamicFlag

| | StaticFlag | DynamicFlag |
|---|---|---|
| **Resolves** | Once at class load | Per call |
| **Access cost** | Field read | Volatile read + bucket computation |
| **Runtime updates** | No | `update()`, `reload()` |
| **Per-entity bucketing** | No (uses instance path) | Yes (caller provides key + attributes) |
| **Rollout path source** | `kyo.rollout.path` / auto-detected | Caller-supplied attributes |
| **Rollout bucket source** | Hash of instance path | Hash of caller-supplied key |
| **Use for** | Pool sizes, timeouts, feature kill switches | Feature gates, A/B tests, per-tenant config |
| **Rollout DSL** | Yes | Yes |
| **Typed parsing** | Yes | Yes |
| **Validation** | Yes | Yes |

## Registry and Introspection

All flags (static and dynamic) self-register into a global registry at construction time.

### Listing Flags

```scala
import kyo.*

// All registered flags
val flags: List[?] = Flag.all

// Look up by name
val flag = Flag.get("myapp.db.poolSize")
```

### Dump Table

`Flag.dump()` returns a formatted table of all registered flags with columns for name, type, value, default, and source:

```scala
import kyo.*

println(Flag.dump())
```

Output:

```
+-----------------------+---------+-------+---------+----------------+
| Name                  | Type    | Value | Default | Source         |
+-----------------------+---------+-------+---------+----------------+
| myapp.db.jdbcUrl      | static  | ...   | ...     | Default        |
| myapp.db.maxRetries   | static  | 5     | 3       | SystemProperty |
| myapp.db.poolSize     | static  | 20    | 10      | SystemProperty |
| myapp.features.newCo..| dynamic | ---   | false   | Default        |
+-----------------------+---------+-------+---------+----------------+
```

Static flags show their resolved value. Dynamic flags show `---` since their value depends on the evaluation key.

### Reading Without a Flag Object

`Flag.apply` reads a config value by name without creating a flag object. Useful for bootstrapping:

```scala
import kyo.*

val logLevel: String = Flag("myapp.log.level", "INFO")
```

This checks the system property, then the environment variable, then falls back to the default. No rollout evaluation, no registration.

## Error Handling

All flag errors are subtypes of `FlagException`, organized into three categories:

### Parse Errors (FlagParseException)

Thrown when a value or expression cannot be parsed:

- **FlagValueParseException** -- a raw string could not be parsed into the expected type (e.g., `"abc"` for an `Int` flag)
- **FlagRolloutParseException** -- a rollout expression has structural errors (via `Rollout.select`)
- **FlagExpressionParseException** -- a dynamic flag expression has structural issues (empty choice, empty selector, bad percentage)
- **FlagChoiceParseException** -- a specific choice within a rollout expression failed to parse

### Validation Errors (FlagValidationException)

- **FlagValidationFailedException** -- the user-supplied validation function rejected a value

### Registration Errors (FlagRegistrationException)

- **FlagDuplicateNameException** -- two flags share the same fully-qualified name
- **FlagNameException** -- a flag is declared inside a class, trait, or method instead of as a top-level object

All exceptions include the flag name, the problematic value or expression, and the underlying cause. They support pattern matching on category traits:

```scala
import kyo.*

try
  // flag initialization
  ()
catch
  case e: FlagParseException        => println(s"Parse error: ${e.getMessage}")
  case e: FlagValidationException   => println(s"Validation error: ${e.getMessage}")
  case e: FlagRegistrationException => println(s"Registration error: ${e.getMessage}")
```

## HTTP Admin (kyo-http)

The `kyo-http` module provides `FlagAdmin` for managing flags over HTTP. Add kyo-http as a dependency to use these features.

### Admin Routes

`FlagAdmin.routes` returns a sequence of HTTP handlers for flag management:

```scala
import kyo.*

val adminHandlers = FlagAdmin.routes(prefix = "flags")
val server = HttpServer.init(adminHandlers*)
```

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/{prefix}` | List all flags (optional `?filter=glob` for filtering) |
| `GET` | `/{prefix}/:name` | Single flag detail as JSON |
| `PUT` | `/{prefix}/:name` | Update a dynamic flag expression (plain text body) |
| `POST` | `/{prefix}/:name/reload` | Reload a dynamic flag from its config source |

The PUT body is plain text (the rollout expression), not JSON:

```bash
curl -X PUT -d 'true@premium/50%' http://localhost:8080/flags/myapp.features.newCheckout
```

### Security

Token-based authentication is configured via the `kyo.flag.admin.token` system property. When set, PUT and POST requests require an `Authorization: Bearer <token>` header. GET endpoints are always open.

Read-only mode blocks all mutations:

```scala
import kyo.*

val adminHandlers = FlagAdmin.routes(prefix = "flags", readOnly = true)
```

## Config Sync (kyo-http)

`FlagSync` runs a background fiber that periodically updates dynamic flags from external sources.

### Reloading from Config Sources

`startReloader` polls system properties and environment variables on an interval:

```scala
import kyo.*

val reloader = FlagSync.startReloader(30.seconds)
```

Each cycle iterates over all registered dynamic flags and calls `reload()`. Static flags are skipped.

### Custom Source

`startSync` fetches expressions from a caller-supplied function, such as a database, Consul, or etcd:

```scala
import kyo.*

val sync = FlagSync.startSync(30.seconds, name =>
  // Return Present(expr) to update, Absent to skip
  fetchFromConsul(name)
)
```

### Error Backoff

Both sync strategies track consecutive failures per flag. The first 5 failures log at WARN level. The 6th triggers a single ERROR escalation. Subsequent failures are suppressed until a successful sync resets the counter. This prevents log spam from persistently broken config sources.

## Cross-Platform

kyo-config compiles and runs on JVM, JavaScript, and Scala Native. The same flag declarations, rollout expressions, and typed parsing work identically across all platforms. Bucketing is deterministic and consistent -- the same key produces the same bucket regardless of platform.

- **JVM**: No additional setup required.
- **JavaScript**: Full support. System properties are not available, so flags resolve from environment variables or defaults.
- **Native**: Full support. Same behavior as JVM.
