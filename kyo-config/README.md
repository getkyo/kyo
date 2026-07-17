# kyo-config

<!-- doctest:setup
```scala
import kyo.*

// Stub flag objects used in examples (stand-ins for objects declared in named packages)
object poolSize    extends StaticFlag[Int](10)
object maxRetries  extends StaticFlag[Int](3)
object jdbcUrl     extends StaticFlag[String]("jdbc:h2:mem:test")
object newCheckout extends DynamicFlag[Boolean](false)
object rateLimit   extends DynamicFlag[Int](100)
```
-->

Type-safe, cross-platform configuration flags for Kyo applications. Flags are Scala objects whose fully-qualified name becomes the config key. Two kinds are provided:

- **StaticFlag** resolves once at class load. Use for infrastructure settings that should not change at runtime: pool sizes, timeouts, codec selections.
- **DynamicFlag** evaluates per call with a caller-provided key. Use for values that must vary per request or change without restart: feature gates, A/B tests, per-tenant rate limits.

Both share the same declaration style, typed parsing, and validation. Flags can also vary by deployment topology and percentage sampling through a rollout expression DSL (covered in a dedicated section below). Configuration is read from system properties, environment variables, or defaults (in that priority order).

## Getting Started

Add the dependency to your `build.sbt`:

```scala doctest:expect=skipped
libraryDependencies += "io.getkyo" %% "kyo-config" % "<latest version>"
```

## Static Flags

### Declaring Flags

Flags are declared as Scala objects. The fully-qualified object name becomes the configuration key:

Note: Flags must be Scala `object`s declared at top level or nested in other objects. The flag name is derived from the JVM class name, so a flag declared inside a class, trait, method, or as an anonymous value gets a mangled name and is rejected at construction with `FlagNameException`.

```scala doctest:expect=skipped
import kyo.*

// In package myapp.db
object poolSize   extends StaticFlag[Int](10)
object maxRetries extends StaticFlag[Int](3)
object jdbcUrl    extends StaticFlag[String]("jdbc:h2:mem:test")
```

`myapp.db.poolSize` is now a config key that reads from the system property `-Dmyapp.db.poolSize=20` or the environment variable `MYAPP_DB_POOLSIZE=20`.

### Reading Values

A static flag's value is available immediately: it is just a field read.

```scala
val size: Int   = poolSize()
val url: String = jdbcUrl()
```

### Configuration Sources

Flags resolve in this order:

1. **System property**: `-Dmyapp.db.poolSize=20`
2. **Environment variable**: `MYAPP_DB_POOLSIZE=20` (dots become underscores, uppercased)
3. **Default**: the value passed to the constructor

The first source found wins.

Note: When a flag resolves to its default, kyo-config scans system properties and env vars for a case-insensitive near-match and warns on stderr ("did you mean ...?"), catching the classic "I set the property but it is still the default" typo.

### Validation

An optional validation function transforms or constrains the resolved value:

```scala doctest:expect=skipped
import kyo.*

// In package myapp.db
// Clamp pool size to at least 1 and at most 100
object poolSize extends StaticFlag[Int](10, n => Right(math.max(1, math.min(n, 100))))

// Ensure the URL is non-empty
object jdbcUrl extends StaticFlag[String](
        "jdbc:h2:mem:test",
        url =>
            if url.nonEmpty then Right(url)
            else Left(new IllegalArgumentException("URL must not be empty"))
    )
```

The validate parameter has signature `A => Either[Throwable, A]`.

Note: Validation runs at class-load time. A parse or validation failure throws and crashes the process before it serves traffic.

### Typed Parsing

Built-in `Flag.Reader` instances cover `Int`, `Long`, `Double`, `Boolean`, `String`, and `Seq[A]` (comma-separated; the element type must be scalar). For any other type, supply your own `Flag.Reader`. There is no Schema-derived or multi-field reader in this module.

> **Note:** When `kyo-data` is on the classpath, a flag value type can also be a kyo-data type. `Duration`, `Chunk`, `Span`, `Dict`, `OrderedMap`, `Instant`, and a multi-field `Record` all have ready-made `Flag.Reader` instances shipped by kyo-data, so they parse from a system property or environment variable with no extra wiring. See [Reading values from configuration](../kyo-data/README.md#reading-values-from-configuration) in the kyo-data README for the accepted string formats and the compile-time-derived `Record` reader.

| Type | Example |
|------|---------|
| `Int` | `-Dmyapp.db.poolSize=20` |
| `Long` | `-Dmyapp.db.ttlMs=60000` |
| `Double` | `-Dmyapp.db.ratio=1.5` |
| `Boolean` | `-Dmyapp.features.debug=true` |
| `String` | `-Dmyapp.db.jdbcUrl=jdbc:h2:mem` |
| `Seq[A]` | `-Dmyapp.db.hosts=host1,host2,host3` |

For custom types, implement a `Reader`:

```scala
import kyo.*

case class Endpoint(host: String, port: Int)

given Flag.Reader[Endpoint] = new Flag.Reader[Endpoint]:
    def apply(s: String): Either[Throwable, Endpoint] =
        val parts = s.split(":")
        Right(Endpoint(parts(0), parts(1).toInt))
    def typeName: String = "Endpoint"
```

Parse errors at class load time throw a `FlagValueParseException`, failing fast before the application serves traffic.

## Dynamic Flags

### Declaring Flags

Dynamic flags follow the same declaration pattern:

```scala doctest:expect=skipped
import kyo.*

// In package myapp.features
object newCheckout extends DynamicFlag[Boolean](false)
object rateLimit   extends DynamicFlag[Int](100)
```

### Evaluating Per Entity

Dynamic flags evaluate against a key (typically a user ID or tenant ID) and optional attributes for path-based matching:

```scala
val enabled: Boolean = newCheckout("user-123")
val limit: Int       = rateLimit("tenant-abc", "premium")
```

The key identifies the entity being evaluated. Attributes provide additional context for matching (see the Rollout DSL section below for how keys and attributes are used in rollout expressions).

Note: `apply(key, attrs*)` increments an evaluation counter on every call. `evaluate(key, attrs*)` returns the identical value with no counter overhead, so use `evaluate` in hot loops. Counters are approximate (a volatile var map, not atomic) and bounded at 100 distinct result values, with overflow bucketed under "other".

For hot loops where counter overhead matters, use `evaluate` instead:

```scala
val enabled: Boolean = newCheckout.evaluate("user-123")
```

### Runtime Updates

Dynamic flags can be updated at runtime. `update` and `reload` require an implicit `AllowUnsafe` because they mutate volatile runtime state, breaking the otherwise resolve-once model:

```scala
import kyo.AllowUnsafe.embrace.danger
newCheckout.update("true")
rateLimit.update("200")
```

The new value is parsed and validated atomically. If anything fails, the old state is preserved.

Note: Validation is two-tier. At construction (deploy time), rollout weights summing over 100% throw and fail fast. At `update()` (runtime), the same condition is normalized or clamped and a warning is written to stderr, so a live service keeps serving.

The current expression is always available:

```scala
val expr: String = newCheckout.expression
```

### Reloading from Config Source

`reload()` re-reads the expression from the original config source (system property or environment variable):

```scala
import kyo.AllowUnsafe.embrace.danger
newCheckout.reload() match
    case Flag.ReloadResult.Updated(newExpr) =>
        println(s"Updated to: $newExpr")
    case Flag.ReloadResult.Unchanged =>
        println("Expression unchanged")
    case Flag.ReloadResult.NoSource =>
        println("No config source found")
end match
```

## Rollout DSL

Both `StaticFlag` and `DynamicFlag` support a rollout expression DSL for conditional values. Rollout expressions let you vary a flag's value by deployment topology (environment, region, cluster) and percentage-based sampling, without changing code or restarting the process.

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

- **expression**: one or more choices separated by `;`. Evaluated left to right, first match wins.
- **choice**: a value paired with a selector (`value@selector`), or a bare value with no `@` (a **terminal** that always matches and stops evaluation).
- **value**: the flag value to use if this choice matches. Parsed by the flag's `Flag.Reader`.
- **selector**: a path, a percentage, or both. Determines whether this choice applies to the current instance or entity.
- **path**: one or more segments separated by `/`. Matched as a prefix against the target path. `prod/us-east-1` matches any path starting with those two segments.
- **segment**: either `*` (wildcard, matches any single path component) or a literal string (exact match).
- **percentage**: a trailing `N%` on a selector. Controls what fraction of entities receive this choice (see Percentage Weights below).
- **terminal**: a choice with no `@`. Always matches. Acts as a default within the expression. Only needed when the desired fallback differs from the flag's constructor default.

### Matching Rules

Choices are evaluated left to right. The first match wins. If no choice matches, the flag's default value is used.

```
-Dmyapp.db.poolSize="50@prod/us-east-1;30@prod;10"
```

For the expression above, evaluation proceeds as follows:

1. Try `50@prod/us-east-1`: does the path start with `prod/us-east-1`?
2. If not, try `30@prod`: does the path start with `prod`?
3. If not, fall through to `10` (terminal, always matches)

### Path Matching

Selectors use prefix matching against path segments separated by `/`.

For **StaticFlag**, the path comes from the instance's rollout path, configured via `-Dkyo.rollout.path=prod/us-east-1/az1` or the environment variable `KYO_ROLLOUT_PATH`. When unset, the path is auto-detected from Kubernetes, AWS, or GCP environment variables.

For **DynamicFlag**, the path comes from the attributes passed by the caller:

```scala
// -Dmyapp.features.rateLimit="200@premium;50@free;100"
rateLimit("tenant-abc", "premium") // 200
rateLimit("tenant-xyz", "free")    // 50
rateLimit("tenant-def")            // 100 (terminal)
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

A trailing `N%` on a selector controls what fraction of entities receive that value. Percentages are **weights**, not thresholds: each percentage specifies the size of a bucket range, and they accumulate left to right.

Note: Because weights accumulate left to right into cumulative bucket ranges, lowering a percentage removes entities previously in-bucket (reducing 75% to 50% drops the top 25%).

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

Bucketing is deterministic per key (via a stable string hash), so a user who was included at 5% stays included at 25%. The bucket range grows from the same starting point, making progressive rollouts additive.

However, **decreasing** a percentage can remove entities: going from 75% back to 50% drops buckets 50-74, removing 25% of previously included users.

### Rollout with StaticFlag

StaticFlag evaluates the rollout expression once at class load time. The path comes from `kyo.rollout.path` (or auto-detected cloud metadata), and the bucket is derived from hashing that path:

```scala doctest:expect=skipped
import kyo.*

// In package myapp.db
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

```scala doctest:expect=skipped
import kyo.*

// In package myapp.features
// -Dmyapp.features.newCheckout="true@premium/50%;false"
object newCheckout extends DynamicFlag[Boolean](false)

// At call site:
newCheckout("user-123", "premium") // key="user-123", path=["premium"]
```

The key `"user-123"` determines the bucket. The attribute `"premium"` is matched against the path selector. If the path matches and the bucket falls within the 50% weight, the result is `true`.

Dynamic flags also accept rollout expressions via `update()`:

```scala
import kyo.AllowUnsafe.embrace.danger
newCheckout.update("true@premium/75%;true@free/25%;false")
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

Note: `Rollout.bucketFor` and `Rollout.validate` are the only public members of `Rollout`. The selector and choice grammar types and internal evaluation functions (`select`, `parseChoices`, `evaluateIndex`) are private[kyo] internals.

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

- **FlagValueParseException**: a raw string could not be parsed into the expected type (e.g., `"abc"` for an `Int` flag)
- **FlagRolloutParseException**: a rollout expression has structural errors
- **FlagExpressionParseException**: a dynamic flag expression has structural issues (empty choice, empty selector, bad percentage)
- **FlagChoiceParseException**: a specific choice within a rollout expression failed to parse

### Validation Errors (FlagValidationException)

- **FlagValidationFailedException**: the user-supplied validation function rejected a value

### Registration Errors (FlagRegistrationException)

- **FlagDuplicateNameException**: two flags share the same fully-qualified name
- **FlagNameException**: a flag is declared inside a class, trait, or method instead of as a top-level object

All exceptions include the flag name, the problematic value or expression, and the underlying cause. They support pattern matching on category traits:

```scala
import kyo.*

try
    // flag initialization
    (
)
catch
    case e: FlagParseException        => println(s"Parse error: ${e.getMessage}")
    case e: FlagValidationException   => println(s"Validation error: ${e.getMessage}")
    case e: FlagRegistrationException => println(s"Registration error: ${e.getMessage}")
end try
```

## HTTP Admin and Sync (kyo-http)

The kyo-http module builds on kyo-config to expose dynamic-flag management over HTTP (`FlagAdmin`) and background reload/sync (`FlagSync`). `FlagAdmin` serves HTTP endpoints for listing, reading, and updating dynamic flag expressions at runtime. `FlagSync` runs a background fiber that periodically reloads or replaces flag expressions from external sources. The full API, route tables, authentication model, and backoff behavior are documented in kyo-http's README.

## Cross-Platform

kyo-config compiles and runs on JVM, JavaScript, and Scala Native. The same flag declarations, rollout expressions, and typed parsing work identically across all platforms. Bucketing is deterministic and consistent: the same key produces the same bucket regardless of platform.

- **JVM**: No additional setup required.
- **JavaScript**: Full support. System properties are not available, so flags resolve from environment variables or defaults.
- **Native**: Full support. Same behavior as JVM.
