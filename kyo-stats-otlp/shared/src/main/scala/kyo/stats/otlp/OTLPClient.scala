package kyo.stats.otlp

import kyo.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.UnsafeCounter

/** HTTP client for exporting telemetry data to an OTLP-compatible collector.
  *
  * Handles JSON serialization, retry logic (exponential backoff on 429/5xx), custom header injection, and partial-success reporting. Failed
  * exports and rejected spans/data points are tracked via internal counters.
  */
object OTLPClient:

    private val traceExportFailures = StatsRegistry.scope("kyo", "stats", "otel")
        .counter("trace.export.failures", "Trace export failures after retries")
    private val metricExportFailures = StatsRegistry.scope("kyo", "stats", "otel")
        .counter("metric.export.failures", "Metric export failures after retries")
    private val rejectedSpans = StatsRegistry.scope("kyo", "stats", "otel")
        .counter("spans.rejected", "Spans rejected by collector")
    private val rejectedDataPoints = StatsRegistry.scope("kyo", "stats", "otel")
        .counter("datapoints.rejected", "Data points rejected by collector")

    /** Exports a batch of trace spans to the configured traces endpoint.
      *
      * Logs a warning and increments `spans.rejected` if the collector reports a partial success.
      */
    def sendTraces(config: OTLPConfig, request: ExportTraceRequest)(using Frame): Unit < Async =
        send[ExportTraceRequest, ExportTraceResponse](
            config, config.tracesEndpoint, request, traceExportFailures
        ) { response =>
            response.partialSuccess match
                case Some(ps) if ps.rejectedSpans > 0 =>
                    rejectedSpans.add(ps.rejectedSpans)
                    Log.warn(s"OTLP collector rejected ${ps.rejectedSpans} spans: ${ps.errorMessage}")
                case _ => ()
        }

    /** Exports a batch of metrics to the configured metrics endpoint.
      *
      * Logs a warning and increments `datapoints.rejected` if the collector reports a partial success.
      */
    def sendMetrics(config: OTLPConfig, request: ExportMetricsRequest)(using Frame): Unit < Async =
        send[ExportMetricsRequest, ExportMetricsResponse](
            config, config.metricsEndpoint, request, metricExportFailures
        ) { response =>
            response.partialSuccess match
                case Some(ps) if ps.rejectedDataPoints > 0 =>
                    rejectedDataPoints.add(ps.rejectedDataPoints)
                    Log.warn(s"OTLP collector rejected ${ps.rejectedDataPoints} data points: ${ps.errorMessage}")
                case _ => ()
        }

    /** Builds the OTLP resource with service name, SDK metadata, and any custom resource attributes from config. */
    private[otlp] def buildResource(config: OTLPConfig): OTLPResource =
        OTLPResource(
            attributes = Seq(
                KeyValue("service.name", AnyValue.string(config.serviceName)),
                KeyValue("telemetry.sdk.name", AnyValue.string("kyo")),
                KeyValue("telemetry.sdk.language", AnyValue.string("scala")),
                KeyValue("telemetry.sdk.version", AnyValue.string("1.0.0"))
            ) ++ config.resourceAttributes.map { case (k, v) =>
                KeyValue(k, AnyValue.string(v))
            }
        )

    private def send[A: Json, B: Json](
        config: OTLPConfig,
        url: String,
        body: A,
        failureCounter: UnsafeCounter
    )(onSuccess: B => Unit < Sync)(using Frame): Unit < Async =
        val headerFilter = config.headers.toSeq.map { case (name, value) =>
            HttpFilter.client.addHeader(name, value)
        }.foldLeft(HttpFilter.noop.asInstanceOf[HttpFilter.Passthrough[Nothing]]) { case (acc, f) =>
            acc.andThen(f).asInstanceOf[HttpFilter.Passthrough[Nothing]]
        }

        val route = HttpRoute.postJson[B, A]("").filter(headerFilter)

        Abort.run[Throwable] {
            HttpClient.withConfig(
                _.timeout(config.timeout)
                    .retry(Schedule.exponentialBackoff(1.second, 2.0, 5.seconds).take(5))
                    .retryOn(status => status.code == 429 || status.isServerError)
            ) {
                HttpUrl.parse(url) match
                    case Result.Failure(err) => Abort.fail(err)
                    case Result.Success(parsedUrl) =>
                        HttpClient.use { client =>
                            client.sendWith(route, HttpRequest(route.method, parsedUrl).addField("body", body)) { res =>
                                res.fields.body
                            }
                        }
            }
        }.map {
            case Result.Success(response) => onSuccess(response)
            case Result.Failure(err) =>
                failureCounter.inc()
                Log.error(s"OTLP export failed: $err")
        }
end OTLPClient
