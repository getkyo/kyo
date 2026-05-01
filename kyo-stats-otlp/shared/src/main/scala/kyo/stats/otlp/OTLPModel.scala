package kyo.stats.otlp

import kyo.Absent
import kyo.Maybe
import kyo.Present
import kyo.Schema

/** Data model types for the OTLP JSON protocol.
  *
  * These case classes map directly to the OpenTelemetry Protocol protobuf messages, serialized as JSON via `derives Schema`. Types are
  * organized into three sections: metrics, traces, and common (resource, scope, key-value attributes).
  *
  * Response types (`ExportTraceResponse`, `ExportMetricsResponse`) are `private[otlp]` since they are only consumed internally by
  * [[OTLPClient]].
  */

// === Metrics ===

/** Top-level request payload for exporting metrics via OTLP. */
case class ExportMetricsRequest(resourceMetrics: Seq[ResourceMetrics]) derives Schema

case class ResourceMetrics(resource: OTLPResource, scopeMetrics: Seq[ScopeMetrics]) derives Schema

case class ScopeMetrics(scope: InstrumentationScope, metrics: Seq[Metric]) derives Schema

case class Metric(
    name: String,
    description: String,
    unit: String,
    histogram: Maybe[OTLPHistogram] = Absent,
    sum: Maybe[OTLPSum] = Absent,
    gauge: Maybe[OTLPGauge] = Absent
) derives Schema

case class OTLPHistogram(
    dataPoints: Seq[HistogramDataPoint],
    aggregationTemporality: Int
) derives Schema

case class OTLPSum(
    dataPoints: Seq[NumberDataPoint],
    aggregationTemporality: Int,
    isMonotonic: Boolean
) derives Schema

case class OTLPGauge(dataPoints: Seq[NumberDataPoint]) derives Schema

case class HistogramDataPoint(
    startTimeUnixNano: String,
    timeUnixNano: String,
    count: String,
    explicitBounds: Seq[Double],
    bucketCounts: Seq[String],
    min: Double,
    max: Double,
    attributes: Seq[KeyValue] = Seq.empty
) derives Schema

case class NumberDataPoint(
    startTimeUnixNano: String,
    timeUnixNano: String,
    asInt: Maybe[String] = Absent,
    asDouble: Maybe[Double] = Absent,
    attributes: Seq[KeyValue] = Seq.empty
) derives Schema

// === Traces ===

/** Top-level request payload for exporting trace spans via OTLP. */
case class ExportTraceRequest(resourceSpans: Seq[ResourceSpans]) derives Schema

case class ResourceSpans(resource: OTLPResource, scopeSpans: Seq[ScopeSpans]) derives Schema

case class ScopeSpans(scope: InstrumentationScope, spans: Seq[OTLPSpan]) derives Schema

/** A single span in the OTLP trace model.
  *
  * @param traceId
  *   32-character lowercase hex trace identifier
  * @param spanId
  *   16-character lowercase hex span identifier
  * @param parentSpanId
  *   Parent span ID, empty string for root spans
  * @param kind
  *   Span kind (1=internal, 2=server, 3=client)
  * @param startTimeUnixNano
  *   Span start time as nanoseconds since Unix epoch, encoded as a string
  * @param endTimeUnixNano
  *   Span end time as nanoseconds since Unix epoch, encoded as a string
  */
case class OTLPSpan(
    traceId: String,
    spanId: String,
    parentSpanId: String = "",
    name: String,
    kind: Int = 1,
    startTimeUnixNano: String,
    endTimeUnixNano: String,
    attributes: Seq[KeyValue] = Seq.empty,
    events: Seq[SpanEvent] = Seq.empty,
    status: SpanStatus = SpanStatus()
) derives Schema

case class SpanEvent(
    name: String,
    timeUnixNano: String,
    attributes: Seq[KeyValue] = Seq.empty
) derives Schema

case class SpanStatus(
    code: Int = 0,
    message: String = ""
) derives Schema

// === Responses ===

private[otlp] case class ExportTraceResponse(
    partialSuccess: Maybe[TracePartialSuccess] = Absent
) derives Schema

private[otlp] case class TracePartialSuccess(
    rejectedSpans: Long = 0,
    errorMessage: String = ""
) derives Schema

private[otlp] case class ExportMetricsResponse(
    partialSuccess: Maybe[MetricsPartialSuccess] = Absent
) derives Schema

private[otlp] case class MetricsPartialSuccess(
    rejectedDataPoints: Long = 0,
    errorMessage: String = ""
) derives Schema

// === Common ===

case class OTLPResource(attributes: Seq[KeyValue] = Seq.empty) derives Schema

case class InstrumentationScope(
    name: String,
    version: String = ""
) derives Schema

case class KeyValue(key: String, value: AnyValue) derives Schema

/** OTLP AnyValue union type. Each variant has exactly one field set. */
case class AnyValue(
    stringValue: Maybe[String] = Absent,
    intValue: Maybe[String] = Absent,
    doubleValue: Maybe[Double] = Absent,
    boolValue: Maybe[Boolean] = Absent
) derives Schema

object AnyValue:
    def string(v: String): AnyValue   = AnyValue(stringValue = Present(v))
    def int(v: Long): AnyValue        = AnyValue(intValue = Present(v.toString))
    def double(v: Double): AnyValue   = AnyValue(doubleValue = Present(v))
    def boolean(v: Boolean): AnyValue = AnyValue(boolValue = Present(v))
end AnyValue

/** Constants for OTLP enum values that are represented as integers in the JSON protocol. */
object OTLPModel:
    /** DELTA aggregation temporality (1) */
    val DeltaTemporality = 1

    /** CUMULATIVE aggregation temporality (2) */
    val CumulativeTemporality = 2

    /** Internal span — default for in-process operations. */
    val SpanKindInternal = 1

    /** Server span — the handler side of an RPC or HTTP request. */
    val SpanKindServer = 2

    /** Client span — the caller side of an RPC or HTTP request. */
    val SpanKindClient = 3

    /** Status not explicitly set. */
    val StatusUnset = 0

    /** Operation completed successfully. */
    val StatusOk = 1

    /** Operation failed. */
    val StatusError = 2
end OTLPModel
