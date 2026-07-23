# kyo-stats-otlp

kyo-stats-otlp wires kyo-core's existing `Stat` counters, gauges, histograms, and trace spans to any OpenTelemetry Protocol collector. There is no new API to learn at the call site: you keep writing `Stat.initScope(...).initCounter(...)` and `Stat.initScope(...).traceSpan(...)` in your application. Adding this module to the classpath and setting `OTEL_EXPORTER_OTLP_ENDPOINT` is the entire integration.

Discovery happens through `META-INF/services` on the JVM, through an explicit `@JSExportTopLevel` registration on Scala.js, and through `META-INF/services` plus a link-time `nativeConfig.withServiceProviders` enlistment on Scala Native (see [Service-loader registration](#service-loader-registration)). When the endpoint variable is unset, the factories return empty and the rest of the runtime sees the standard no-op exporter, so the module is safe to ship in builds that may or may not emit telemetry. Trace spans are buffered in a bounded channel and flushed in batches on a fixed schedule or when the batch-size threshold is hit; metrics are scraped from the global `StatsRegistry` on a separate periodic loop. W3C Trace Context propagation is layered as HTTP client/server filters discovered through the same service-loader path.

The module is published for JVM, Scala.js, and Scala Native. The wire encoding is OTLP-over-HTTP/JSON, not protobuf.

<!-- doctest:setup
```scala
import kyo.stats.otlp.*
```
-->


```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_SERVICE_NAME=my-service
```

```scala
// "kyo-stats-otlp" % "<version>"
```

That is all that is required. No code changes are needed: the service-loader mechanism picks up the exporter automatically, and the environment variable controls whether export is active.

## Setup and activation

Add `kyo-stats-otlp` to the classpath of any module that already uses `kyo-core` stats. No code changes are required to start exporting.

### Service-loader registration

On the JVM, kyo-stats-registry discovers exporter implementations through `META-INF/services/kyo.stats.internal.ExporterFactory`, and the HTTP runtime independently discovers `META-INF/services/kyo.HttpFilter$Factory`. Both files ship in this module's jar, so dropping the jar on the classpath is enough.

On Scala Native, `java.util.ServiceLoader` is resolved at LINK time: the same `META-INF/services` files ship in the jar, but a provider is linked into the binary ONLY when it is also enlisted in the final application's `nativeConfig`. Enlist both providers there, or OTLP export and the trace-propagation filters are silently inert:

```scala doctest:expect=skipped
// build.sbt, in the Scala Native application project
nativeConfig ~= (_.withServiceProviders(Map(
    "kyo.stats.internal.ExporterFactory" -> Seq("kyo.stats.otlp.OTLPExporterFactory"),
    "kyo.HttpFilter$Factory"             -> Seq("kyo.stats.otlp.OTLPHttpFilterFactory")
)))
```

On Scala.js, `META-INF/services` does not work. The module's JS-only `OTLPRegistration` object uses `@JSExportTopLevel("__kyo_otel_init")` to register both factories at module load time:

```scala doctest:expect=skipped
// js/src/main/scala/kyo/stats/otlp/OTLPRegistration.scala
import kyo.HttpFilter
import kyo.stats.internal.ExporterFactory
import kyo.stats.internal.JSServiceLoaderRegistry
import scala.scalajs.js.annotation.JSExportTopLevel

object OTLPRegistration:
    @JSExportTopLevel("__kyo_otel_init")
    val init: Boolean =
        JSServiceLoaderRegistry.register(classOf[ExporterFactory], new OTLPExporterFactory())
        JSServiceLoaderRegistry.register(classOf[HttpFilter.Factory], new OTLPHttpFilterFactory())
        true
    end init
end OTLPRegistration
```

> **Caution:** the Scala.js linker may tree-shake `OTLPRegistration` if nothing references it. If telemetry silently never starts on JS, force a reference (`val _ = OTLPRegistration.init`) somewhere in your application's entry point.

### The on/off switch

Setting `OTEL_EXPORTER_OTLP_ENDPOINT` is what activates the module. With the variable unset, every factory returns empty, and the runtime falls back to the standard no-op exporter. The application keeps running normally without exporting anything.

```bash
# Minimum configuration for a working OTLP setup
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
export OTEL_SERVICE_NAME=my-service
```

> **Caution:** export is silently disabled when `OTEL_EXPORTER_OTLP_ENDPOINT` is unset; nothing logs and the application keeps running with the no-op exporter. If you expect telemetry and see none, verify the variable is actually exported in the process environment.

## Configuration via OTEL_* environment variables

`OTLPConfig` is a plain case class capturing every tunable. The standard path is `OTLPConfig.loadIfEnabled()`, which reads the `OTEL_*` environment variables and returns `Absent` when the endpoint variable is unset. For tests and embedded use cases, build the value directly.

### Loading from the environment

```scala
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.stats.otlp.OTLPConfig

val maybe: Maybe[OTLPConfig] = OTLPConfig.loadIfEnabled()

maybe match
    case Present(cfg) =>
        // export enabled, cfg.endpoint is the OTLP base URL
        println(s"exporting to ${cfg.endpoint} as ${cfg.serviceName}")
    case Absent =>
        // OTEL_EXPORTER_OTLP_ENDPOINT not set, export disabled
        println("OTLP export disabled")
end match
```

`loadIfEnabled` reads `System.getenv` directly. The value is snapshotted at the call site, so changing environment variables after JVM start has no effect. Both `OTLPExporterFactory` and `OTLPHttpFilterFactory` call this independently, which means the environment is read multiple times during startup but always at startup.

### Environment-variable reference

The full env-var contract follows the OpenTelemetry conventions:

| Variable | Default | Field |
| --- | --- | --- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | (required) | `endpoint` |
| `OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` | `<endpoint>/v1/metrics` | `metricsEndpoint` |
| `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` | `<endpoint>/v1/traces` | `tracesEndpoint` |
| `OTEL_EXPORTER_OTLP_HEADERS` | (empty) | `headers` |
| `OTEL_EXPORTER_OTLP_TIMEOUT` | `10000` (ms) | `timeout` |
| `OTEL_EXPORTER_OTLP_COMPRESSION` | `none` | `compression` |
| `OTEL_SERVICE_NAME` | `unknown_service` | `serviceName` |
| `OTEL_RESOURCE_ATTRIBUTES` | (empty) | `resourceAttributes` |
| `OTEL_BSP_SCHEDULE_DELAY` | `5000` (ms) | `bspScheduleDelay` |
| `OTEL_BSP_MAX_QUEUE_SIZE` | `2048` | `bspMaxQueueSize` |
| `OTEL_BSP_MAX_EXPORT_BATCH_SIZE` | `512` | `bspMaxExportBatchSize` |
| `OTEL_BSP_EXPORT_TIMEOUT` | `30000` (ms) | `bspExportTimeout` |
| `OTEL_METRIC_EXPORT_INTERVAL` | `60000` (ms) | `metricExportInterval` |
| `OTEL_METRIC_EXPORT_TIMEOUT` | `30000` (ms) | `metricExportTimeout` |

Headers and resource attributes are parsed as comma-separated `key=value` pairs:

```bash
export OTEL_EXPORTER_OTLP_HEADERS="authorization=Bearer abc123,x-tenant=acme"
export OTEL_RESOURCE_ATTRIBUTES="deployment.environment=prod,service.version=1.4.2"
```

### Direct construction

For tests, ad-hoc tools, or non-environment-driven deployments, construct `OTLPConfig` directly:

```scala
import kyo.*
import kyo.stats.otlp.OTLPConfig

val cfg = OTLPConfig(
    endpoint = "http://localhost:4318",
    metricsEndpoint = "http://localhost:4318/v1/metrics",
    tracesEndpoint = "http://localhost:4318/v1/traces",
    headers = Map("authorization" -> "Bearer abc123"),
    timeout = 10.seconds,
    compression = "none",
    serviceName = "http-server",
    resourceAttributes = Map("env" -> "prod", "version" -> "1.0"),
    bspScheduleDelay = 5.seconds,
    bspMaxQueueSize = 2048,
    bspMaxExportBatchSize = 512,
    bspExportTimeout = 30.seconds,
    metricExportInterval = 60.seconds,
    metricExportTimeout = 30.seconds
)
```

## Exporting trace spans

When OTLP export is active, kyo-stats-registry resolves `TraceExporter.get` to an `OTLPTraceExporter` instance. Application code uses the same `Stat.traceSpan` API it would use with any other exporter:

```scala
import kyo.*

val scope = Stat.initScope("http", "server")
val handler: String < (Sync & Async) =
    scope.traceSpan("GET /hello") {
        "hi"
    }
```

Each call to `scope.traceSpan` produces an `OTLPSpan` value at the wire boundary when the span completes.

### Bounded queue and batched flush

`OTLPTraceExporter` owns a bounded `Channel` for completed spans. When a span's `end()` is called, the span is offered to the channel. If the channel is full, the span is dropped silently and only counted in `kyo.stats.otel.spans.dropped`. A background fiber drains up to `bspMaxExportBatchSize` spans per export call.

The flush loop is driven by two producers feeding a single consumer through a capacity-1 trigger channel:

- A periodic producer offers to the trigger every `bspScheduleDelay`.
- The exporter offers to the same trigger whenever the queue size reaches `bspMaxExportBatchSize`.

The consumer takes from the trigger and runs `flush`, which drains the queue, builds an `ExportTraceRequest`, and calls `OTLPClient.sendTraces`. The capacity-1 trigger naturally coalesces concurrent offers while the consumer is busy.

> **Caution:** spans that arrive when the bounded queue is full are dropped silently. Under burst load, raise `OTEL_BSP_MAX_QUEUE_SIZE`, raise `OTEL_BSP_MAX_EXPORT_BATCH_SIZE`, or shorten `OTEL_BSP_SCHEDULE_DELAY` to drain faster.

### Fiber lifecycle

On JVM and Scala Native, the background fibers start in the `OTLPTraceExporter` constructor via `OTLPInitPlatform.triggerStart`. They run under `Scope.run { ... Async.never }`, so they are intentionally never interrupted. There is no public shutdown call on the exporter.

> **Caution:** simply constructing an `OTLPTraceExporter` spawns long-lived fibers that live for the rest of the process. In-flight spans buffered in the channel can be lost on JVM/Native process exit because there is no graceful drain hook. There is no public shutdown method; the background fibers are parented to `Async.never` and are torn down when the JVM exits.

### Span kind and status

`OTLPTraceExporter` always sets `kind = OTLPModel.SpanKindInternal` on exported spans. The OTLP enum constants for `SpanKindServer` and `SpanKindClient` exist on `OTLPModel`, but there is no API to mark a span as server, client, producer, or consumer. Status maps from kyo-core's `UnsafeTraceSpan.Status` straightforwardly:

```scala
// kyo.stats.internal.UnsafeTraceSpan.Status ->  OTLPModel constant
//   Status.Unset                            ->  StatusUnset (0)
//   Status.Ok                               ->  StatusOk    (1)
//   Status.Error(message)                   ->  StatusError (2), message preserved
```

Span attributes from kyo-core's `Attributes` collection map to OTLP `KeyValue` entries, with list-typed attributes joined into a single comma-separated `stringValue`.

## Exporting metrics

`OTLPMetricsExporter.run` starts a background loop that scrapes the global `StatsRegistry` on `metricExportInterval` and exports any metrics that have non-zero activity since the last export. Application code uses the same `Stat.initScope(...).initCounter(...)`, `initHistogram(...)`, and `initGauge(...)` APIs as without this module.

```scala
import kyo.*

val scope    = Stat.initScope("http", "server")
val requests = scope.initCounter("requests", "Total requests")
val latency  = scope.initHistogram("latency_ms", "Request latency")

val handle: String < (Sync & Async) =
    for
        _ <- requests.inc
        _ <- latency.observe(12.3)
    yield "hi"
```

### Counters and counter-gauges

Counters and counter-gauges export as `OTLPSum` with `isMonotonic = true` and `aggregationTemporality = OTLPModel.DeltaTemporality`. Each export reports the change since the last export, not a running total.

> **Caution:** because temporality is DELTA, not CUMULATIVE, collectors configured for cumulative-only ingestion will misinterpret the data. Configure your collector to accept delta sums for kyo's metrics, or front it with a delta-to-cumulative translator.

### Histograms

Histograms export as `OTLPHistogram` with explicit bucket boundaries from kyo-core's histogram summary, plus per-bucket counts, min, max, and the sum of every observed value. Aggregation temporality is CUMULATIVE, and every data point carries the same series-start `startTimeUnixNano`, captured at the first export. A histogram's buckets, count, min, max, and sum are lifetime values that never drain on read, so each export reports the totals for the whole series rather than the activity since the last export.

### Gauges

Gauges export as `OTLPGauge` containing a single `NumberDataPoint` with the current value. Unlike counters, the value is not a delta.

### Zero-activity intervals

Counters and counter-gauges with `delta == 0` since the last export are skipped entirely, so downstream dashboards see gaps, not zeros, for idle periods. A histogram is skipped only while its `count` is still 0: once it has been observed, every later export re-sends its cumulative data point, since that is what a cumulative series requires. A gauge is exported only once it has been registered, and a metric source registers a gauge on its first present observation, so a gauge for a value the host never produces registers no handle and is absent from the export entirely; this registration-absence is the gauge-side counterpart to the counter `delta == 0` and histogram `count == 0` skips, an idle or host-absent gauge yielding no data point rather than a fabricated zero. An export cycle with no traffic and no observed histogram produces an empty payload, not zero-valued data points.

Weak-reference cleanup for collected metric instances runs inline during the export iteration. A second pass over the same Scala Native map would deadlock, so cleanup and read share one traversal.

## Distributed tracing across HTTP

W3C Trace Context propagation is provided by two filters in `OTLPTraceContextFilter`. They are auto-installed via `OTLPHttpFilterFactory` when OTLP is active:

```scala
import kyo.*
import kyo.stats.otlp.OTLPTraceContextFilter

val clientFilter: HttpFilter.Passthrough[Nothing] = OTLPTraceContextFilter.client
val serverFilter: HttpFilter.Passthrough[Nothing] = OTLPTraceContextFilter.server
```

### Client-side injection

`OTLPTraceContextFilter.client` reads `TraceSpan.current` on each outgoing request. If the current span implements `UnsafeTraceSpan.Propagatable`, the filter adds a `traceparent: 00-<traceId>-<spanId>-01` header:

```scala
// Inside OTLPTraceContextFilter.client (simplified):
//   TraceSpan.current.map {
//       case Present(TraceSpan(span: UnsafeTraceSpan.Propagatable)) =>
//           next(request.addHeader("traceparent", s"00-${span.traceId}-${span.spanId}-01"))
//       case _ => next(request)
//   }
```

Spans produced by `OTLPTraceExporter` implement `Propagatable`, and spans parsed by the server filter implement `Propagatable`. Spans from any other `TraceExporter` that does not extend `Propagatable` are silently skipped for header injection.

### Server-side extraction

`OTLPTraceContextFilter.server` reads the `traceparent` header on each incoming request. If present and well-shaped, it parses the trace ID and span ID and uses `TraceSpan.let` to set a synthetic remote span as the current trace context for the rest of the handler:

```scala
import kyo.*
import kyo.stats.otlp.OTLPTraceContextFilter

// Pseudo-flow inside the server filter:
//   request.headers.get("traceparent") match
//       case Present(value) => parseTraceparent(value) match
//           case Present((traceId, spanId)) =>
//               TraceSpan.let(TraceSpan(new RemoteSpanUnsafe(traceId, spanId))) {
//                   next(request)
//               }
//           case _ => next(request)
//       case _ => next(request)
```

> **Note:** the parser accepts any header with 4-or-more dash-separated parts where part 1 is 32 characters and part 2 is 16 characters. The version byte and trace flags are not validated, so malformed-but-shaped headers are accepted as valid. This is lenient by design; if you need strict W3C validation, layer your own filter before this one.

## Operational visibility

Every OTLP failure path increments a kyo-core counter under the `kyo.stats.otel` scope. These counters are themselves visible to `OTLPMetricsExporter` and exported as ordinary metrics, so you can alert on them through the same collector you ship to:

| Counter | Meaning |
| --- | --- |
| `kyo.stats.otel.trace.export.failures` | Trace export gave up after retries |
| `kyo.stats.otel.metric.export.failures` | Metric export gave up after retries |
| `kyo.stats.otel.spans.rejected` | Collector reported `partialSuccess.rejectedSpans` |
| `kyo.stats.otel.datapoints.rejected` | Collector reported `partialSuccess.rejectedDataPoints` |
| `kyo.stats.otel.spans.dropped` | Span offered to the channel but the channel was full |

A non-zero `spans.dropped` rate is the signal to raise `OTEL_BSP_MAX_QUEUE_SIZE` or `OTEL_BSP_MAX_EXPORT_BATCH_SIZE`. Non-zero `*.export.failures` after the retry policy is exhausted usually means the collector is unreachable, mis-authenticated (check `OTEL_EXPORTER_OTLP_HEADERS`), or rejecting payloads (check rejection counters for partial-success detail).

The retry policy on `OTLPClient` is `Schedule.exponentialBackoff(1.second, 2.0, 5.seconds).take(5)`, gated on `status.code == 429 || status.isServerError`. Other 4xx responses are not retried and increment the failure counter immediately.

## OTLP wire model

The model in `OTLPModel.scala` mirrors the OpenTelemetry Protocol protobuf messages, serialized as JSON via `derives Schema`. It is exposed publicly so tests, adapters, and direct callers of `OTLPClient.sendTraces` / `OTLPClient.sendMetrics` can build payloads without going through `TraceSpan` or `Stat`.

### Building values directly

Each case class is constructed positionally. Optional fields use `Maybe[A]` (`Absent`/`Present(a)`):

```scala
import kyo.*
import kyo.stats.otlp.*

val span = OTLPSpan(
    traceId = "0123456789abcdef0123456789abcdef",
    spanId = "0123456789abcdef",
    name = "test",
    startTimeUnixNano = "1000",
    endTimeUnixNano = "2000",
    attributes = Seq(KeyValue("http.method", AnyValue.string("GET"))),
    status = SpanStatus(code = OTLPModel.StatusOk)
)

val request = ExportTraceRequest(
    resourceSpans = Seq(ResourceSpans(
        resource = OTLPResource(attributes = Seq(KeyValue("service.name", AnyValue.string("my-service")))),
        scopeSpans = Seq(ScopeSpans(
            scope = InstrumentationScope("kyo-stats", version = "1.0.0"),
            spans = Seq(span)
        ))
    ))
)
```

### AnyValue: the OTLP union type

OTLP's `AnyValue` is a tagged union (string, int, double, bool) that this module encodes as a struct with at most one field set. Use the smart constructors on the `AnyValue` companion to build values; constructing the case class directly with multiple fields set is meaningless on the wire:

```scala
import kyo.stats.otlp.AnyValue

val s = AnyValue.string("hello") // stringValue = Present("hello")
val i = AnyValue.int(42L)        // intValue    = Present("42")  -- ints are wire-encoded as strings
val d = AnyValue.double(3.14)    // doubleValue = Present(3.14)
val b = AnyValue.boolean(true)   // boolValue   = Present(true)
```

> **Note:** the wire encoding is OTLP-over-HTTP/JSON, not protobuf, and `AnyValue` is encoded as a struct with at most one populated field rather than a true tagged union. Collectors that expect strict protobuf must front a translation proxy.

### OTLPModel constants

`OTLPModel` carries the integer enum constants used in the JSON wire form:

```scala
import kyo.stats.otlp.OTLPModel

val internal = OTLPModel.SpanKindInternal // 1
val server   = OTLPModel.SpanKindServer   // 2
val client   = OTLPModel.SpanKindClient   // 3

val unset = OTLPModel.StatusUnset // 0
val ok    = OTLPModel.StatusOk    // 1
val err   = OTLPModel.StatusError // 2

val delta      = OTLPModel.DeltaTemporality      // 1
val cumulative = OTLPModel.CumulativeTemporality // 2
```

`OTLPTraceExporter` always emits `SpanKindInternal`; the other constants are provided for adapters and synthesized payloads.

### Building histograms and sums

The metric variants are case classes that mirror the OTLP wire shape exactly:

```scala
import kyo.*
import kyo.stats.otlp.*

val sumPoint = NumberDataPoint(
    startTimeUnixNano = "1000",
    timeUnixNano = "2000",
    asInt = Present("17")
)

val sum = OTLPSum(
    dataPoints = Seq(sumPoint),
    aggregationTemporality = OTLPModel.DeltaTemporality,
    isMonotonic = true
)

val histPoint = HistogramDataPoint(
    startTimeUnixNano = "1000",
    timeUnixNano = "2000",
    count = "3",
    explicitBounds = Seq(1.0, 5.0, 10.0),
    bucketCounts = Seq("1", "1", "1", "0"),
    min = 0.5,
    max = 7.0,
    sum = 13.5
)

val hist = OTLPHistogram(
    dataPoints = Seq(histPoint),
    aggregationTemporality = OTLPModel.CumulativeTemporality
)

val counter = Metric(name = "http.server.requests", description = "Total requests", unit = "1", sum = Present(sum))
val latency = Metric(name = "http.server.latency_ms", description = "Request latency", unit = "1", histogram = Present(hist))
```

### Sending payloads directly

When you have an `OTLPConfig` and an `ExportTraceRequest` or `ExportMetricsRequest`, you can call `OTLPClient.sendTraces` / `OTLPClient.sendMetrics` to send a single payload. Both methods carry the same retry policy, partial-success handling, and failure counters as the background exporter:

```scala
import kyo.*
import kyo.stats.otlp.*

def sendOne(config: OTLPConfig, request: ExportTraceRequest): Unit < Async =
    OTLPClient.sendTraces(config, request)
```

These are the seams adapters and tests reach for when they want to drive the wire without spinning up the background exporter.
