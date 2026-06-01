# kyo-stats-registry

kyo-stats-registry is the low-level metrics and tracing substrate the rest of Kyo uses to report telemetry. Each module asks `StatsRegistry` for a `Scope` (a dotted path like `"kyo" :: "fiber" :: Nil`) and then mints counters, histograms, gauges, and counter-gauges hanging off that scope. Instruments are deduplicated per path: the second call from anywhere in the process returns the same handle the first call did, so a module's "the counter for X" is a process-global singleton rather than a per-call-site allocation. Instrument handles are held weakly, so an instrument the rest of the program has dropped will not pin the registry's map.

Everything in `kyo.stats.internal` is `Unsafe`: the methods take an `AllowUnsafe` evidence and skip the effect system. That is intentional. This is the layer exporters and instrumented hot paths talk to directly; higher layers are responsible for wrapping calls in effects when they need to. The tracing side mirrors the same shape: `TraceExporter.get` runs `java.util.ServiceLoader` discovery against `ExporterFactory` implementations on the classpath, composes them with `TraceExporter.all`, and hands back a single exporter that mints `UnsafeTraceSpan` handles for spans. Application code should usually go through a higher layer like `Stat.initScope(...).initCounter(...)` in `kyo-core`.

<!-- doctest:setup
```scala
import java.time.Instant
import kyo.stats.*
import kyo.stats.internal.*
val httpClientScope              = StatsRegistry.scope("kyo", "http", "client")
def currentInFlightCount: Double = 0.0
```
-->

```scala
import kyo.AllowUnsafe.embrace.danger

val requests = StatsRegistry.scope("kyo", "http", "client").counter("requests")
requests.inc()
requests.add(4)
val sinceLastPoll: Long = requests.get() // returns 5, then resets to 0
```

## Per-module scopes

Telemetry is namespaced by path. Each module of an application (or each library it pulls in) carves out its own corner of the registry by calling `StatsRegistry.scope(...)` with the path segments it owns, then mints instruments off the resulting `Scope`. Two callers that ask for instruments under the same path get the same handle, which is what makes "the counter for HTTP requests" a single value the whole process agrees on rather than a per-call-site allocation.

### Naming a scope

`StatsRegistry.scope` takes the path as a varargs of segments. The path is the registry key for everything minted off the scope: nesting deeper appends segments, and the same path resolves to the same instruments anywhere in the process.

```scala
val client = StatsRegistry.scope("kyo", "http", "client")
val server = StatsRegistry.scope("kyo", "http", "server")
assert(client.path == List("kyo", "http", "client"))
```

> **Note:** the registry stores paths in reverse internally for fast prepending. `Scope.path` returns the forward order; do not read the private `reversePath` and expect human-readable order.

### Nesting deeper

`Scope.scope(more*)` appends segments to an existing scope. This is the usual shape for a sub-component of an already-instrumented module: the parent picks the top-level prefix, sub-components extend it without re-typing the prefix.

```scala
val client    = StatsRegistry.scope("kyo", "http", "client")
val pool      = client.scope("pool")
val keepalive = pool.scope("keepalive")
assert(keepalive.path == List("kyo", "http", "client", "pool", "keepalive"))
```

### Singleton-per-path

Two `counter("requests")` calls on scopes with the same path return the same `UnsafeCounter`. The first caller wins the instantiation; subsequent callers get that same handle back.

```scala
val a = StatsRegistry.scope("kyo", "http", "client").counter("requests")
val b = StatsRegistry.scope("kyo", "http", "client").counter("requests")
assert(a eq b)
```

> **Caution:** the registry holds instruments in a `WeakReference`. If every caller drops its handle the entry is evicted on next lookup and the next caller gets a fresh instrument with the count reset to zero. The user is responsible for holding a strong reference somewhere (typically a `val` in the module that owns the metric).

> **Note:** the description argument is stored only on first registration. Re-declaring the same counter under the same path with a different description silently keeps the original description; the second description is discarded.

## Instruments

The registry mints four kinds of measurement. Each has a hot-path API the instrumented code calls (`inc`, `add`, `observe`) and a poll-time API an exporter calls (`get`, `summary`, `collect`). The expected pattern is "one party owns the increments, a different party owns the reads": an exporter polls on a fixed interval and the application code never reads.

All methods take an implicit `AllowUnsafe`. There is no effect-system wrapping at this layer. The caller is responsible for any concurrency discipline beyond what each instrument documents.

### Counting events: `Counter`

When the instrumented code path emits a discrete event per increment, `Scope.counter` mints an `UnsafeCounter`: a monotonic, thread-safe count backed by `LongAdder`. `inc()` and `add(v)` are the hot-path writes.

```scala
import kyo.AllowUnsafe.embrace.danger

val requests = httpClientScope.counter("requests", "HTTP requests issued")
requests.inc()
requests.inc()
requests.add(8)
```

`get()` and `delta()` are the two ways to read. They behave differently and serve different consumers.

```scala
import kyo.AllowUnsafe.embrace.danger

val requests        = httpClientScope.counter("requests", "HTTP requests issued")
val polled: Long    = requests.get()   // sumThenReset: returns total and zeroes
val sinceLast: Long = requests.delta() // stateful: total minus last delta() value
```

> **Caution:** `get()` is destructive. It calls `sumThenReset()`, so the counter is zero immediately after reading. The intended consumer is a single exporter that polls on a fixed interval. Calling `get()` from application code zeros the counter for everyone else.

> **Caution:** `delta()` is stateful and not safe across readers. It mutates a non-volatile `last` field. Only one consumer should be calling `delta()` per counter.

When to use which: an exporter that publishes cumulative counts to a backend that expects cumulative values uses `delta()` and accumulates the deltas. An exporter that publishes non-cumulative (delta-encoded) values can use either; `get()` is simpler when the exporter is also the only reader.

### Recording distributions: `Histogram`

When the value being recorded is a distribution rather than a count, `Scope.histogram` mints an `UnsafeHistogram`: a fixed-bucket distribution with inclusive upper bounds. `observe(v: Long)` and `observe(v: Double)` are the hot-path writes; `summary()` is the poll-time read.

```scala
import kyo.AllowUnsafe.embrace.danger

val latency = httpClientScope.histogram("latency_ms", "Request latency in ms")
latency.observe(42L)
latency.observe(173.4)
val snapshot: Summary = latency.summary()
```

The constructor validates boundaries eagerly. Duplicates, NaN, or `Infinity` throw at construction (via `require`), not at observation.

```scala
val custom = new UnsafeHistogram(Array(1.0, 10.0, 100.0, 1000.0))
// new UnsafeHistogram(Array(1.0, 1.0, 2.0)) // throws: not strictly ascending
// new UnsafeHistogram(Array(Double.NaN))    // throws: contains NaN
```

> **Note:** bucket semantics use inclusive upper bounds. A value exactly equal to a boundary lands in that bucket, matching Prometheus and OTel conventions. This differs from the naive "strictly less than" intuition.

> **Note:** `summary()` is not fully atomic under concurrent writes. Bucket counts come from a single-pass snapshot and `count` is derived from that same snapshot so they stay internally consistent. A writer racing with the reader may land in either snapshot.

> **Note:** min and max are packed as two 32-bit floats in a single `AtomicLong` so each observation costs one CAS. This trims precision to about 7 significant digits versus double's 15. The trade is fine for dashboard hints, not for exact-value assertions.

#### Default boundaries

`UnsafeHistogram.defaultBoundaries` is the OTel SDK default, tuned for latency in milliseconds: `0, 5, 10, 25, 50, 75, 100, 250, 500, 750, 1000, 2500, 5000, 7500, 10000`. The `histogram` method on `Scope` uses these unless `boundaries` is overridden.

> **Caution:** the defaults are ms latency only. Using them for byte counts, request sizes, queue depths, or any other non-latency-like quantity will dump nearly every observation into the overflow bucket. Pass an explicit `boundaries` array when the quantity is not "milliseconds in the 0..10000 range."

```scala
import kyo.AllowUnsafe.embrace.danger

val payloadBytes = httpClientScope.histogram(
    "payload_bytes",
    "Response payload size",
    Array(128.0, 512.0, 2048.0, 8192.0, 32768.0, 131072.0, 524288.0)
)
payloadBytes.observe(4096L)
```

#### Summary

`Summary` is the value-class snapshot returned by `UnsafeHistogram.summary()`. It carries the boundaries, per-bucket counts, total count, min, and max. `percentile(p)` linearly interpolates within the bucket containing the target rank.

> **Note:** the bucket counts are non-cumulative (each bucket holds only its own observations, matching the OTLP explicit-bucket data model), and there is no `sum` field. Sum is omitted by design: exact observed values are not retained, and the OTLP histogram model marks sum optional.

```scala
import kyo.AllowUnsafe.embrace.danger

val latency     = httpClientScope.histogram("latency_ms", "Outbound request latency in milliseconds")
val s: Summary  = latency.summary()
val p50: Double = s.percentile(50.0)
val p99: Double = s.percentile(99.0)
```

> **Note:** `percentile` is only as accurate as the bucket spacing. The interpolation is linear within the bucket, so a wide bucket gives a coarse answer. For an overflow-bucket result it returns the last boundary, not infinity.

### Sampling on demand: `Gauge`

When the value already lives somewhere the exporter can read on demand, `Scope.gauge` mints an `UnsafeGauge`: a pull-model double-valued sample. The thunk re-runs every time `collect()` is called, so the exporter sees the current value.

```scala
import kyo.AllowUnsafe.embrace.danger

val inFlight = httpClientScope.gauge("in_flight", "Currently outstanding requests")(
    currentInFlightCount
)
val sample: Double = inFlight.collect()
```

> **Caution:** the gauge instance holds the thunk strongly, but the registry holds the *instance* in a `WeakReference`. A gauge whose captured closure holds the only reference to the gauge instance will be evicted under GC pressure even though its thunk is alive. Keep the gauge `val` on a long-lived object.

### Reading runtime counters: `CounterGauge`

When the monotonic source is the JVM (GC count, classes loaded, etc.) rather than the application, `Scope.counterGauge` mints an `UnsafeCounterGauge`: a pull-model monotonic counter. The thunk samples the externally-maintained value on demand rather than counting application-side writes.

```scala
import kyo.AllowUnsafe.embrace.danger

val gcCount = StatsRegistry.scope("kyo", "runtime").counterGauge("gc_count")(
    java.lang.management.ManagementFactory.getGarbageCollectorMXBeans
        .stream().mapToLong(_.getCollectionCount).sum()
)
val current: Long   = gcCount.collect()
val sinceLast: Long = gcCount.delta()
```

> **Note:** `collect()` wraps negative results: `Long.MinValue` lands as `Long.MaxValue / 2`. If the thunk legitimately returns negatives, the wrap will produce nonsense. Counter-gauges are intended for true monotonic sources where a negative is overflow.

> **Caution:** `delta()` on `UnsafeCounterGauge` is also single-reader (same caveat as `UnsafeCounter.delta`).

### Counter vs counter-gauge

Both produce monotonic Long values for an exporter to poll. The difference is who owns the count. `UnsafeCounter` is incremented by the instrumented code path: every event calls `inc()` or `add(v)`. `UnsafeCounterGauge` reads a value the runtime (or some external library) is already maintaining: the thunk samples on demand and there is no application-side write path.

## Writing exporters

An exporter library plugs into the registry through a service-loader seam. Three pieces are required: an `ExporterFactory` subclass that returns the exporter, a `META-INF/services/kyo.stats.internal.ExporterFactory` resource file declaring the class, and the application calling `TraceExporter.get` once at startup to discover and compose what is on the classpath.

### Registering an exporter: `ExporterFactory`

Plugging an exporter into the registry happens through `ExporterFactory`, the SPI base class the ServiceLoader scans for. The default `traceExporter()` returns `None` (factory present, not opted in); override it to return `Some(exporter)`.

```scala
import java.time.Instant
// In package myapp.telemetry:
import kyo.AllowUnsafe
import kyo.stats.Attributes
import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.TraceExporter

class MyExporterFactory extends ExporterFactory:
    override def traceExporter()(implicit _au: AllowUnsafe): Option[TraceExporter] =
        Some(new MyTraceExporter)

class MyTraceExporter extends TraceExporter:
    def startSpan(
        scope: List[String],
        name: String,
        now: Instant,
        parent: Option[UnsafeTraceSpan] = None,
        attributes: Attributes = Attributes.empty
    )(implicit _au: AllowUnsafe): UnsafeTraceSpan = UnsafeTraceSpan.noop
end MyTraceExporter
```

Declare the class in `src/main/resources/META-INF/services/kyo.stats.internal.ExporterFactory`:

```
myapp.telemetry.MyExporterFactory
```

> **Caution:** the service descriptor is the entire wiring. Without that `META-INF/services` file, `TraceExporter.get` silently returns `noop` and *no error is logged*. Forgetting the descriptor is the most common deployment failure.

### Implementing the exporter: `TraceExporter`

Once the factory is wired, the exporter itself has one job: hand callers an `UnsafeTraceSpan`. `TraceExporter.startSpan(scope, name, now, parent?, attributes?)` is the one method to implement; the exporter does ID generation, batching, and shipping to the backend behind it.

```scala
class MyTraceExporter extends TraceExporter:
    def startSpan(
        scope: List[String],
        name: String,
        now: Instant,
        parent: Option[UnsafeTraceSpan] = None,
        attributes: Attributes = Attributes.empty
    )(implicit _au: AllowUnsafe): UnsafeTraceSpan =
        new MyTraceSpan(scope, name)
end MyTraceExporter

class MyTraceSpan(scope: List[String], name: String) extends UnsafeTraceSpan:
    def end(now: Instant)(implicit _au: AllowUnsafe): Unit                             = ()
    def event(n: String, a: Attributes, now: Instant)(implicit _au: AllowUnsafe): Unit = ()
    def setStatus(status: UnsafeTraceSpan.Status)(implicit _au: AllowUnsafe): Unit     = ()
end MyTraceSpan
```

### Discovering exporters at startup

At startup the application calls `TraceExporter.get` once; it runs `ServiceLoader.load(classOf[ExporterFactory])`, calls `traceExporter()` on each factory, flattens the `Some` results, and composes them. Zero exporters returns `noop`; one returns that one; many returns `TraceExporter.all(list)`, which fans out every method call to every exporter.

```scala
import kyo.AllowUnsafe.embrace.danger

val exporter: TraceExporter = TraceExporter.get
val span = exporter.startSpan(
    scope = List("kyo", "http", "client"),
    name = "GET /users",
    now = Instant.now()
)
span.setStatus(UnsafeTraceSpan.Status.Ok)
span.end(Instant.now())
```

> **Caution:** `TraceExporter.get` does classpath discovery on *every call*. It is not cached. Call it once at application startup, hold the result in a `val`, and pass that to anything that needs it.

> **Note:** `TraceExporter.all` composes exporters by fan-out: `end`, `event`, and `setStatus` fire on every span. For propagation it only exposes the *first* `Propagatable` exporter's `traceId` and `spanId`; the other exporters' IDs are silently dropped on the propagation path even though their lifecycle methods still fire.

### Driving a span: `UnsafeTraceSpan`

Between `startSpan` and the call that ends it, the caller drives an `UnsafeTraceSpan`. Three methods, all `Unsafe`:

```scala
import kyo.AllowUnsafe.embrace.danger

val exporter: TraceExporter = TraceExporter.noop
val span: UnsafeTraceSpan = exporter.startSpan(
    List("kyo", "http", "client"),
    "GET /users",
    Instant.now()
)
span.event(
    "cache-miss",
    Attributes.add("key", "user:42"),
    Instant.now()
)
span.setStatus(UnsafeTraceSpan.Status.Error("upstream timeout"))
span.end(Instant.now())
```

The `Status` sealed type has three variants:

```scala
val unset: UnsafeTraceSpan.Status = UnsafeTraceSpan.Status.Unset
val ok: UnsafeTraceSpan.Status    = UnsafeTraceSpan.Status.Ok
val err: UnsafeTraceSpan.Status   = UnsafeTraceSpan.Status.Error("upstream timeout")
```

`Propagatable` is a mixin trait an exporter's span class implements when it carries cross-service context:

```scala
class MyTraceSpan(name: String, parent: Option[UnsafeTraceSpan]) extends UnsafeTraceSpan with UnsafeTraceSpan.Propagatable:
    val traceId: String                                                                = generateTraceId()
    val spanId: String                                                                 = generateSpanId()
    def end(now: Instant)(implicit _au: AllowUnsafe): Unit                             = ()
    def event(n: String, a: Attributes, now: Instant)(implicit _au: AllowUnsafe): Unit = ()
    def setStatus(status: UnsafeTraceSpan.Status)(implicit _au: AllowUnsafe): Unit     = ()
    private def generateTraceId()                                                      = "trace-1"
    private def generateSpanId()                                                       = "span-1"
end MyTraceSpan
```

`UnsafeTraceSpan.noop` is the span every `TraceExporter.noop.startSpan` call returns. It is built with `AllowUnsafe.embrace.danger` already in scope so callers do not need to provide one.

> **Caution:** do not pattern-match on `UnsafeTraceSpan.noop` to detect "no exporter installed." `TraceExporter.noop.startSpan` returns it, but so does any `all(Nil)` composition, and an exporter is free to hand back a noop for spans it chose not to record. The presence of `noop` is not a useful signal to user code.

## Trace attributes

`Attributes` is the kv bag attached to spans and events. It carries a `List[Attribute]` where each `Attribute` is one of eight typed variants (boolean, double, long, string, and `List` of each). Values are added through the `AsAttribute[A]` type class, which compile-time restricts the set of allowed Scala types.

### Building attribute bags

Attribute bags accumulate with `Attributes.empty.add(...)` chains; each `add` returns a new `Attributes` carrying the appended typed value.

```scala
val attrs: Attributes = Attributes.empty
    .add("http.method", "GET")
    .add("http.status_code", 200)
    .add("retry", true)
    .add("upstream", List("a.example.com", "b.example.com"))
```

The companion exposes `empty`, `add(name, value)` for a single-pair `Attributes`, and `all(list)` to flatten a `List[Attributes]` into one.

```scala
val a                  = Attributes.add("k1", "v1")
val b                  = Attributes.add("k2", 42L)
val merged: Attributes = Attributes.all(List(a, b))
assert(merged.get.length == 2)
```

### On-wire types

`Attributes.Attribute` is sealed with eight case classes:

```scala
import kyo.stats.Attributes.Attribute
import kyo.stats.Attributes.Attribute.*
val attrs: List[Attribute] = List(
    BooleanAttribute("ok", true),
    DoubleAttribute("p99_ms", 173.4),
    LongAttribute("count", 42L),
    StringAttribute("path", "/users"),
    BooleanListAttribute("flags", List(true, false)),
    DoubleListAttribute("latencies", List(1.0, 2.0, 3.0)),
    LongListAttribute("ids", List(1L, 2L, 3L)),
    StringListAttribute("tags", List("a", "b"))
)
```

This is the shape an exporter sees on the wire.

### Restricting value types: `AsAttribute`

Only a small set of Scala types are valid attribute values; the `AsAttribute[A]` type class is what enforces that restriction at compile time. Instances exist for `Boolean`, `Double`, `Long`, `Int`, `String`, and `List` of each. The `@implicitNotFound` message lists allowed types when resolution fails.

```scala
val ok: Attributes  = Attributes.add("count", 42)     // Int -> LongAttribute
val ok2: Attributes = Attributes.add("name", "alice") // String
// Attributes.add("ratio", 0.5f) // does NOT compile: Float has no AsAttribute instance
// Attributes.add("opt",   Some(1)) // does NOT compile: Option has no AsAttribute instance
```

> **Note:** `Int` is auto-widened to `LongAttribute`, and `List[Int]` to `LongListAttribute`. There is no `IntAttribute` on the wire; the registry's on-the-wire type model has only Long. `Float`, `BigDecimal`, `Option`, `Map`, and user case classes will not compile as attribute values.

## Cross-platform behavior

The module compiles for JVM, Scala.js, and Scala Native from the same `shared/` sources. The hot-path API is identical across all three. The one behavior that differs is the registry's weak-reference eviction.

On JVM, instruments are held in `java.lang.ref.WeakReference` and are evicted under GC pressure when no strong reference remains. On JS and Native, `WeakReference` is a polyfill that holds the value *strongly*. Instruments minted on those platforms are pinned for the life of the process; the eviction-and-fresh-instrument scenario described under "Singleton-per-path" cannot occur there.

The application-level consequence is small in practice: code that holds instruments in a `val` on a long-lived object behaves identically across platforms. Code that relies on eviction to drop instruments (none of the in-tree kyo modules do this) is JVM-only behavior.

## Putting it together

A realistic instrumented module wires the registry, the instruments, and an exporter together at startup. The instruments are `val`s on the module object so the strong references survive for the lifetime of the process; the exporter is fetched once and reused.

```scala
import java.time.Instant
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.stats.*
import kyo.stats.internal.*

object HttpClientTelemetry:

    private val scope = StatsRegistry.scope("kyo", "http", "client")

    val requests = scope.counter("requests", "HTTP requests issued")
    val latency  = scope.histogram("latency_ms", "Request latency in ms")
    val inFlight = scope.gauge("in_flight", "Currently outstanding requests")(
        currentInFlightCount()
    )

    val exporter: TraceExporter = TraceExporter.get

    def recordRequest(method: String, path: String, durationMs: Long, statusOk: Boolean): Unit =
        requests.inc()
        latency.observe(durationMs)
        val span = exporter.startSpan(
            scope.path,
            s"$method $path",
            Instant.now(),
            attributes = Attributes.empty
                .add("http.method", method)
                .add("http.path", path)
        )
        span.setStatus(
            if statusOk then UnsafeTraceSpan.Status.Ok
            else UnsafeTraceSpan.Status.Error("non-2xx response")
        )
        span.end(Instant.now())
    end recordRequest

    private def currentInFlightCount(): Double = 0.0
end HttpClientTelemetry
```

An exporter polls the instruments on its own clock:

```scala
import kyo.AllowUnsafe.embrace.danger

object HttpClientTelemetry:
    private val scope = StatsRegistry.scope("kyo", "http", "client")
    val requests      = scope.counter("requests", "HTTP requests issued")
    val latency       = scope.histogram("latency_ms", "Request latency in ms")
    val inFlight      = scope.gauge("in_flight", "Currently outstanding requests")(0.0)
end HttpClientTelemetry

def poll(): Unit =
    val totalRequests: Long = HttpClientTelemetry.requests.get() // resets to 0
    val latencySnapshot     = HttpClientTelemetry.latency.summary()
    val currentInFlight     = HttpClientTelemetry.inFlight.collect()
    publish("kyo.http.client.requests", totalRequests)
    publish("kyo.http.client.latency_ms.p99", latencySnapshot.percentile(99.0))
    publish("kyo.http.client.in_flight", currentInFlight)
end poll

def publish(name: String, value: Long): Unit   = ()
def publish(name: String, value: Double): Unit = ()
```

The instrumented code path never reads the instruments; the exporter never increments them. That separation is what makes the destructive `get()` on counters and the single-reader `delta()` on counter-gauges work without coordination.
