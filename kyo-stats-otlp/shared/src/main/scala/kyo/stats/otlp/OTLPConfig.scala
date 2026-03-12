package kyo.stats.otlp

import kyo.*

/** Configuration for the OTLP exporter.
  *
  * All settings follow the OpenTelemetry environment variable conventions. When loaded via `loadIfEnabled`, values are read from standard
  * `OTEL_EXPORTER_OTLP_*` environment variables with sensible defaults. Export is disabled entirely when `OTEL_EXPORTER_OTLP_ENDPOINT` is
  * not set.
  *
  * @param endpoint
  *   Base OTLP endpoint URL (e.g. `http://localhost:4318`)
  * @param metricsEndpoint
  *   Full URL for the metrics export endpoint, defaults to `endpoint + "/v1/metrics"`
  * @param tracesEndpoint
  *   Full URL for the traces export endpoint, defaults to `endpoint + "/v1/traces"`
  * @param headers
  *   Additional HTTP headers sent with every export request (e.g. auth tokens)
  * @param timeout
  *   HTTP request timeout for each export call
  * @param compression
  *   Compression algorithm (`"none"` or `"gzip"`)
  * @param serviceName
  *   Value of the `service.name` resource attribute
  * @param resourceAttributes
  *   Extra key-value pairs added to the OTLP resource on every export
  * @param bspScheduleDelay
  *   How often the batch span processor flushes accumulated spans
  * @param bspMaxQueueSize
  *   Maximum number of spans buffered before new spans are dropped
  * @param bspMaxExportBatchSize
  *   Maximum number of spans sent per export request
  * @param bspExportTimeout
  *   Timeout for a single trace export batch
  * @param metricExportInterval
  *   How often metrics are collected and exported
  * @param metricExportTimeout
  *   Timeout for a single metric export call
  */
case class OTLPConfig(
    endpoint: String,
    metricsEndpoint: String,
    tracesEndpoint: String,
    headers: Map[String, String],
    timeout: Duration,
    compression: String,
    serviceName: String,
    resourceAttributes: Map[String, String],
    bspScheduleDelay: Duration,
    bspMaxQueueSize: Int,
    bspMaxExportBatchSize: Int,
    bspExportTimeout: Duration,
    metricExportInterval: Duration,
    metricExportTimeout: Duration
)

object OTLPConfig:

    /** Returns Absent if OTEL_EXPORTER_OTLP_ENDPOINT is not set — export is disabled. */
    private[otlp] def loadIfEnabled()(using AllowUnsafe) =
        val endpoint = java.lang.System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
        if endpoint == null then Absent
        else
            Present(OTLPConfig(
                endpoint = endpoint,
                metricsEndpoint = envOrDefault("OTEL_EXPORTER_OTLP_METRICS_ENDPOINT", endpoint + "/v1/metrics"),
                tracesEndpoint = envOrDefault("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", endpoint + "/v1/traces"),
                headers = parseHeaders(envOrDefault("OTEL_EXPORTER_OTLP_HEADERS", "")),
                timeout = envOrDefault("OTEL_EXPORTER_OTLP_TIMEOUT", "10000").toLong.millis,
                compression = envOrDefault("OTEL_EXPORTER_OTLP_COMPRESSION", "none"),
                serviceName = envOrDefault("OTEL_SERVICE_NAME", "unknown_service"),
                resourceAttributes = parseHeaders(envOrDefault("OTEL_RESOURCE_ATTRIBUTES", "")),
                bspScheduleDelay = envOrDefault("OTEL_BSP_SCHEDULE_DELAY", "5000").toLong.millis,
                bspMaxQueueSize = envOrDefault("OTEL_BSP_MAX_QUEUE_SIZE", "2048").toInt,
                bspMaxExportBatchSize = envOrDefault("OTEL_BSP_MAX_EXPORT_BATCH_SIZE", "512").toInt,
                bspExportTimeout = envOrDefault("OTEL_BSP_EXPORT_TIMEOUT", "30000").toLong.millis,
                metricExportInterval = envOrDefault("OTEL_METRIC_EXPORT_INTERVAL", "60000").toLong.millis,
                metricExportTimeout = envOrDefault("OTEL_METRIC_EXPORT_TIMEOUT", "30000").toLong.millis
            ))

    private def envOrDefault(name: String, default: String): String =
        val v = java.lang.System.getenv(name)
        if v == null then default else v

    private def parseHeaders(s: String): Map[String, String] =
        if s.isEmpty then Map.empty
        else
            s.split(",").flatMap { pair =>
                pair.split("=", 2) match
                    case Array(k, v) => Some(k.trim -> v.trim)
                    case _           => None
            }.toMap
end OTLPConfig
