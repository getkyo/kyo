package kyo.stats.otlp

import kyo.*

/** HTTP client for exporting telemetry data to an OTLP-compatible collector.
  *
  * Handles JSON serialization, retry logic (exponential backoff on 429/5xx), custom header injection, and partial-success reporting. Failed
  * exports and rejected spans/data points are tracked via internal counters.
  */
object OTLPClient:

    private[otlp] val sdkVersion           = "1.0.0"
    private[otlp] val instrumentationScope = InstrumentationScope("kyo-stats", version = sdkVersion)

    private val stat                 = Stat.initScope("kyo", "stats", "otel")
    private val traceExportFailures  = stat.initCounter("trace.export.failures", "Trace export failures after retries")
    private val metricExportFailures = stat.initCounter("metric.export.failures", "Metric export failures after retries")
    private val rejectedSpans        = stat.initCounter("spans.rejected", "Spans rejected by collector")
    private val rejectedDataPoints   = stat.initCounter("datapoints.rejected", "Data points rejected by collector")

    /** Exports a batch of trace spans to the configured traces endpoint.
      *
      * Logs a warning and increments `spans.rejected` if the collector reports a partial success.
      */
    def sendTraces(config: OTLPConfig, request: ExportTraceRequest)(using Frame): Unit < Async =
        send[ExportTraceRequest, ExportTraceResponse](
            config,
            config.tracesEndpoint,
            request,
            traceExportFailures
        ) { response =>
            response.partialSuccess match
                case Present(ps) if ps.rejectedSpans > 0 =>
                    rejectedSpans.add(ps.rejectedSpans)
                        .andThen(Log.warn(s"OTLP collector rejected ${ps.rejectedSpans} spans: ${ps.errorMessage}"))
                case _ => ()
        }

    /** Exports a batch of metrics to the configured metrics endpoint.
      *
      * Logs a warning and increments `datapoints.rejected` if the collector reports a partial success.
      */
    def sendMetrics(config: OTLPConfig, request: ExportMetricsRequest)(using Frame): Unit < Async =
        send[ExportMetricsRequest, ExportMetricsResponse](
            config,
            config.metricsEndpoint,
            request,
            metricExportFailures
        ) { response =>
            response.partialSuccess match
                case Present(ps) if ps.rejectedDataPoints > 0 =>
                    rejectedDataPoints.add(ps.rejectedDataPoints)
                        .andThen(Log.warn(s"OTLP collector rejected ${ps.rejectedDataPoints} data points: ${ps.errorMessage}"))
                case _ => ()
        }

    /** Builds the OTLP resource with service name, SDK metadata, and any custom resource attributes from config. */
    private val sdkAttributes = Seq(
        KeyValue("telemetry.sdk.name", AnyValue.string("kyo")),
        KeyValue("telemetry.sdk.language", AnyValue.string("scala")),
        KeyValue("telemetry.sdk.version", AnyValue.string(sdkVersion))
    )

    /** Builds the OTLP resource with service name, SDK metadata, and any custom resource attributes from config. */
    private[otlp] def buildResource(config: OTLPConfig): OTLPResource =
        OTLPResource(
            attributes =
                (KeyValue("service.name", AnyValue.string(config.serviceName)) +: sdkAttributes)
                    ++ config.resourceAttributes.map { case (k, v) => KeyValue(k, AnyValue.string(v)) }
        )

    private def send[A: Schema, B: Schema](
        config: OTLPConfig,
        url: String,
        body: A,
        failureCounter: Counter
    )(onSuccess: B => Unit < (Sync & Async))(using Frame): Unit < Async =
        val route = HttpRoute.postJson[B, A]("")

        Abort.run[Throwable] {
            HttpClient.withConfig(
                _.timeout(config.timeout)
                    .retry(Schedule.exponentialBackoff(1.second, 2.0, 5.seconds).take(5))
                    .retryOn(status => status.code == 429 || status.isServerError)
            ) {
                HttpUrl.parse(url) match
                    case Result.Failure(err) => Abort.fail(err)
                    case Result.Success(parsedUrl) =>
                        val request =
                            config.headers.foldLeft(
                                HttpRequest(route.method, parsedUrl).addField("body", body)
                            ) { case (req, (name, value)) => req.addHeader(name, value) }
                        HttpClient.use { client =>
                            client.sendWith(route, request) { res =>
                                res.fields.body
                            }
                        }
            }
        }.map {
            case Result.Success(response) => onSuccess(response)
            case Result.Failure(err) =>
                failureCounter.inc
                    .andThen(Log.error(s"OTLP export failed: $err"))
        }
    end send

    /** Starts a serialized export loop driven by a trigger channel.
      *
      * A periodic producer offers to the trigger at fixed rate via `Clock.repeatAtInterval`. External producers (e.g., batch-size
      * threshold) can also offer to the same trigger. A single consumer fiber takes from the trigger and flushes, ensuring no overlap. The
      * trigger channel has capacity 1, so offers are naturally coalesced when the consumer is busy.
      */
    /** Starts a serialized export loop driven by a trigger channel.
      *
      * Registers both the periodic producer and consumer fibers with the enclosing `Scope` so they are interrupted when the scope closes.
      */
    private[otlp] def startExportLoop(
        interval: Duration,
        timeout: Duration,
        label: String,
        trigger: Channel.Unsafe[Unit]
    )(
        flush: => Unit < Async
    )(using Frame): Unit < (Sync & Scope) =
        val safeTrigger = trigger.safe
        for
            producer <- Clock.repeatAtInterval(interval, interval) {
                safeTrigger.offer(()).unit
            }
            _ <- Scope.ensure(producer.interrupt.unit)
            consumer <- Fiber.initUnscoped {
                Loop.forever {
                    safeTrigger.take.andThen {
                        Abort.recover[Throwable](err => Log.error(s"OTLP $label flush failed", err)) {
                            Async.timeout(timeout)(flush)
                        }
                    }
                }
            }
            _ <- Scope.ensure(consumer.interrupt.unit)
        yield ()
        end for
    end startExportLoop
end OTLPClient
