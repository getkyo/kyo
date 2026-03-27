package kyo.stats.otlp

import kyo.*
import kyo.stats.*

class OTLPClientTest extends Test:

    def testConfig(port: Int) = OTLPConfig(
        endpoint = s"http://localhost:$port",
        tracesEndpoint = s"http://localhost:$port/v1/traces",
        metricsEndpoint = s"http://localhost:$port/v1/metrics",
        headers = Map.empty,
        timeout = 5.seconds,
        compression = "none",
        serviceName = "test-client",
        resourceAttributes = Map("env" -> "test"),
        bspScheduleDelay = 100.millis,
        bspMaxQueueSize = 100,
        bspMaxExportBatchSize = 50,
        bspExportTimeout = 30.seconds,
        metricExportInterval = 60.seconds,
        metricExportTimeout = 30.seconds
    )

    def withCollector[A, S](
        test: (OTLPConfig, Channel[ExportTraceRequest], Channel[ExportMetricsRequest]) => A < (S & Async & Abort[Any])
    )(using Frame): A < (S & Async & Scope & Abort[Any]) =
        for
            traceCh  <- Channel.init[ExportTraceRequest](100)
            metricCh <- Channel.init[ExportMetricsRequest](100)
            traceRoute = HttpRoute.postRaw("v1" / "traces")
                .request(_.bodyJson[ExportTraceRequest])
                .response(_.bodyJson[ExportTraceResponse])
            metricRoute = HttpRoute.postRaw("v1" / "metrics")
                .request(_.bodyJson[ExportMetricsRequest])
                .response(_.bodyJson[ExportMetricsResponse])
            traceHandler = traceRoute.handler { req =>
                traceCh.put(req.fields.body).andThen {
                    HttpResponse.ok.addField("body", ExportTraceResponse())
                }
            }
            metricHandler = metricRoute.handler { req =>
                metricCh.put(req.fields.body).andThen {
                    HttpResponse.ok.addField("body", ExportMetricsResponse())
                }
            }
            server <- HttpServer.init(0, "localhost")(traceHandler, metricHandler)
            config = testConfig(server.port)
            result <- test(config, traceCh, metricCh)
        yield result

    def mkTraceRoute = HttpRoute.postRaw("v1" / "traces")
        .request(_.bodyJson[ExportTraceRequest])
        .response(_.bodyJson[ExportTraceResponse])

    def mkMetricRoute = HttpRoute.postRaw("v1" / "metrics")
        .request(_.bodyJson[ExportMetricsRequest])
        .response(_.bodyJson[ExportMetricsResponse])

    def defaultMetricHandler = mkMetricRoute.handler { _ =>
        HttpResponse.ok.addField("body", ExportMetricsResponse())
    }

    def mkSimpleTraceRequest(config: OTLPConfig, name: String) =
        ExportTraceRequest(
            resourceSpans = Seq(ResourceSpans(
                resource = OTLPClient.buildResource(config),
                scopeSpans = Seq(ScopeSpans(
                    scope = InstrumentationScope("kyo-stats"),
                    spans = Seq(OTLPSpan(
                        traceId = "abc",
                        spanId = "def",
                        name = name,
                        startTimeUnixNano = "1",
                        endTimeUnixNano = "2"
                    ))
                ))
            ))
        )

    "sendTraces" - {

        "sends spans to collector" in run {
            withCollector { (config, traceCh, _) =>
                val span = OTLPSpan(
                    traceId = "0af7651916cd43dd8448eb211c80319c",
                    spanId = "b7ad6b7169203331",
                    name = "test-operation",
                    startTimeUnixNano = "1000000000",
                    endTimeUnixNano = "2000000000"
                )
                val request = ExportTraceRequest(
                    resourceSpans = Seq(ResourceSpans(
                        resource = OTLPClient.buildResource(config),
                        scopeSpans = Seq(ScopeSpans(
                            scope = InstrumentationScope("kyo-stats", version = "1.0.0"),
                            spans = Seq(span)
                        ))
                    ))
                )
                for
                    _        <- OTLPClient.sendTraces(config, request)
                    received <- traceCh.take
                yield
                    assert(received.resourceSpans.size == 1)
                    val spans = received.resourceSpans.head.scopeSpans.head.spans
                    assert(spans.size == 1)
                    assert(spans.head.name == "test-operation")
                    assert(spans.head.traceId == "0af7651916cd43dd8448eb211c80319c")
                    assert(spans.head.spanId == "b7ad6b7169203331")
                end for
            }
        }

        "includes resource attributes" in run {
            withCollector { (config, traceCh, _) =>
                val request = ExportTraceRequest(
                    resourceSpans = Seq(ResourceSpans(
                        resource = OTLPClient.buildResource(config),
                        scopeSpans = Seq(ScopeSpans(
                            scope = InstrumentationScope("kyo-stats"),
                            spans = Seq(OTLPSpan(
                                traceId = "abc",
                                spanId = "def",
                                name = "x",
                                startTimeUnixNano = "1",
                                endTimeUnixNano = "2"
                            ))
                        ))
                    ))
                )
                for
                    _        <- OTLPClient.sendTraces(config, request)
                    received <- traceCh.take
                yield
                    val attrs = received.resourceSpans.head.resource.attributes
                    assert(attrs.exists(kv => kv.key == "service.name" && kv.value.stringValue == Present("test-client")))
                    assert(attrs.exists(kv => kv.key == "telemetry.sdk.name" && kv.value.stringValue == Present("kyo")))
                    assert(attrs.exists(kv => kv.key == "env" && kv.value.stringValue == Present("test")))
                end for
            }
        }

        "includes span attributes and events" in run {
            withCollector { (config, traceCh, _) =>
                val span = OTLPSpan(
                    traceId = "abc",
                    spanId = "def",
                    name = "op",
                    kind = OTLPModel.SpanKindServer,
                    startTimeUnixNano = "1",
                    endTimeUnixNano = "2",
                    attributes = Seq(KeyValue("http.method", AnyValue.string("GET"))),
                    events = Seq(SpanEvent(
                        "exception",
                        "1500000000",
                        Seq(KeyValue("message", AnyValue.string("boom")))
                    )),
                    status = SpanStatus(code = OTLPModel.StatusError, message = "request failed")
                )
                val request = ExportTraceRequest(
                    resourceSpans = Seq(ResourceSpans(
                        resource = OTLPClient.buildResource(config),
                        scopeSpans = Seq(ScopeSpans(
                            scope = InstrumentationScope("kyo-stats"),
                            spans = Seq(span)
                        ))
                    ))
                )
                for
                    _        <- OTLPClient.sendTraces(config, request)
                    received <- traceCh.take
                yield
                    val s = received.resourceSpans.head.scopeSpans.head.spans.head
                    assert(s.kind == OTLPModel.SpanKindServer)
                    assert(s.attributes.exists(kv => kv.key == "http.method" && kv.value.stringValue == Present("GET")))
                    assert(s.events.exists(_.name == "exception"))
                    assert(s.status.code == OTLPModel.StatusError)
                    assert(s.status.message == "request failed")
                end for
            }
        }

        "sends multiple spans in batch" in run {
            withCollector { (config, traceCh, _) =>
                val spans = (1 to 5).map(i =>
                    OTLPSpan(
                        traceId = "abc",
                        spanId = s"span$i",
                        name = s"op-$i",
                        startTimeUnixNano = "1",
                        endTimeUnixNano = "2"
                    )
                )
                val request = ExportTraceRequest(
                    resourceSpans = Seq(ResourceSpans(
                        resource = OTLPClient.buildResource(config),
                        scopeSpans = Seq(ScopeSpans(
                            scope = InstrumentationScope("kyo-stats"),
                            spans = spans
                        ))
                    ))
                )
                for
                    _        <- OTLPClient.sendTraces(config, request)
                    received <- traceCh.take
                yield
                    val receivedSpans = received.resourceSpans.head.scopeSpans.head.spans
                    assert(receivedSpans.size == 5)
                    (1 to 5).foreach(i =>
                        assert(receivedSpans.exists(_.name == s"op-$i"))
                    )
                    succeed
                end for
            }
        }

        "parent-child relationship preserved" in run {
            withCollector { (config, traceCh, _) =>
                val parent = OTLPSpan(
                    traceId = "aaa",
                    spanId = "parent1",
                    name = "parent-op",
                    startTimeUnixNano = "1",
                    endTimeUnixNano = "3"
                )
                val child = OTLPSpan(
                    traceId = "aaa",
                    spanId = "child1",
                    parentSpanId = "parent1",
                    name = "child-op",
                    startTimeUnixNano = "1",
                    endTimeUnixNano = "2"
                )
                val request = ExportTraceRequest(
                    resourceSpans = Seq(ResourceSpans(
                        resource = OTLPClient.buildResource(config),
                        scopeSpans = Seq(ScopeSpans(
                            scope = InstrumentationScope("kyo-stats"),
                            spans = Seq(parent, child)
                        ))
                    ))
                )
                for
                    _        <- OTLPClient.sendTraces(config, request)
                    received <- traceCh.take
                yield
                    val spans = received.resourceSpans.head.scopeSpans.head.spans
                    assert(spans.size == 2)
                    val childSpan = spans.find(_.name == "child-op").get
                    assert(childSpan.parentSpanId == "parent1")
                    assert(childSpan.traceId == "aaa")
                end for
            }
        }

        "retries after 503 and delivers" in run {
            val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
            for
                traceCh <- Channel.init[ExportTraceRequest](10)
                traceHandler = mkTraceRoute.handler { req =>
                    if attempts.incrementAndGet() <= 1 then
                        HttpResponse.halt(HttpResponse.serviceUnavailable)
                    else
                        traceCh.put(req.fields.body).andThen {
                            HttpResponse.ok.addField("body", ExportTraceResponse())
                        }
                }
                server <- HttpServer.init(0, "localhost")(traceHandler, defaultMetricHandler)
                config = testConfig(server.port)
                _        <- OTLPClient.sendTraces(config, mkSimpleTraceRequest(config, "retry-test"))
                received <- traceCh.take
            yield
                assert(attempts.get() >= 2, s"Expected at least 2 attempts, got ${attempts.get()}")
                assert(received.resourceSpans.head.scopeSpans.head.spans.head.name == "retry-test")
            end for
        }

        "retries after 429 and delivers" in run {
            val attempts = new java.util.concurrent.atomic.AtomicInteger(0)
            for
                traceCh <- Channel.init[ExportTraceRequest](10)
                traceHandler = mkTraceRoute.handler { req =>
                    if attempts.incrementAndGet() <= 1 then
                        HttpResponse.halt(HttpResponse.tooManyRequests)
                    else
                        traceCh.put(req.fields.body).andThen {
                            HttpResponse.ok.addField("body", ExportTraceResponse())
                        }
                }
                server <- HttpServer.init(0, "localhost")(traceHandler, defaultMetricHandler)
                config = testConfig(server.port)
                _        <- OTLPClient.sendTraces(config, mkSimpleTraceRequest(config, "rate-limit-test"))
                received <- traceCh.take
            yield
                assert(attempts.get() >= 2)
                assert(received.resourceSpans.head.scopeSpans.head.spans.head.name == "rate-limit-test")
            end for
        }

        "handles partial success with rejected spans" in run {
            for
                traceCh <- Channel.init[ExportTraceRequest](10)
                traceHandler = mkTraceRoute.handler { req =>
                    traceCh.put(req.fields.body).andThen {
                        HttpResponse.ok.addField(
                            "body",
                            ExportTraceResponse(
                                partialSuccess = Present(TracePartialSuccess(
                                    rejectedSpans = 3,
                                    errorMessage = "queue full"
                                ))
                            )
                        )
                    }
                }
                server <- HttpServer.init(0, "localhost")(traceHandler, defaultMetricHandler)
                config = testConfig(server.port)
                _        <- OTLPClient.sendTraces(config, mkSimpleTraceRequest(config, "partial-test"))
                received <- traceCh.take
            yield assert(received.resourceSpans.head.scopeSpans.head.spans.head.name == "partial-test")
        }
    }

    "sendMetrics" - {

        "sends counter metrics" in run {
            withCollector { (config, _, metricCh) =>
                val request = ExportMetricsRequest(
                    resourceMetrics = Seq(ResourceMetrics(
                        resource = OTLPClient.buildResource(config),
                        scopeMetrics = Seq(ScopeMetrics(
                            scope = InstrumentationScope("kyo-stats"),
                            metrics = Seq(Metric(
                                name = "http.requests",
                                description = "Total HTTP requests",
                                unit = "1",
                                sum = Present(OTLPSum(
                                    dataPoints = Seq(NumberDataPoint(
                                        startTimeUnixNano = "100",
                                        timeUnixNano = "200",
                                        asInt = Present("42")
                                    )),
                                    aggregationTemporality = OTLPModel.DeltaTemporality,
                                    isMonotonic = true
                                ))
                            ))
                        ))
                    ))
                )
                for
                    _        <- OTLPClient.sendMetrics(config, request)
                    received <- metricCh.take
                yield
                    val metrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                    assert(metrics.size == 1)
                    assert(metrics.head.name == "http.requests")
                    assert(metrics.head.sum.isDefined)
                    assert(metrics.head.sum.get.dataPoints.head.asInt == Present("42"))
                    assert(metrics.head.sum.get.isMonotonic)
                end for
            }
        }

        "sends histogram metrics" in run {
            withCollector { (config, _, metricCh) =>
                val request = ExportMetricsRequest(
                    resourceMetrics = Seq(ResourceMetrics(
                        resource = OTLPClient.buildResource(config),
                        scopeMetrics = Seq(ScopeMetrics(
                            scope = InstrumentationScope("kyo-stats"),
                            metrics = Seq(Metric(
                                name = "http.duration",
                                description = "Request duration",
                                unit = "ms",
                                histogram = Present(OTLPHistogram(
                                    dataPoints = Seq(HistogramDataPoint(
                                        startTimeUnixNano = "100",
                                        timeUnixNano = "200",
                                        count = "10",
                                        explicitBounds = Seq(1.0, 5.0, 10.0),
                                        bucketCounts = Seq("2", "5", "2", "1"),
                                        min = 0.5,
                                        max = 42.0
                                    )),
                                    aggregationTemporality = OTLPModel.DeltaTemporality
                                ))
                            ))
                        ))
                    ))
                )
                for
                    _        <- OTLPClient.sendMetrics(config, request)
                    received <- metricCh.take
                yield
                    val metrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                    assert(metrics.head.name == "http.duration")
                    assert(metrics.head.histogram.isDefined)
                    val dp = metrics.head.histogram.get.dataPoints.head
                    assert(dp.count == "10")
                    assert(dp.min == 0.5)
                    assert(dp.max == 42.0)
                end for
            }
        }

        "sends gauge metrics" in run {
            withCollector { (config, _, metricCh) =>
                val request = ExportMetricsRequest(
                    resourceMetrics = Seq(ResourceMetrics(
                        resource = OTLPClient.buildResource(config),
                        scopeMetrics = Seq(ScopeMetrics(
                            scope = InstrumentationScope("kyo-stats"),
                            metrics = Seq(Metric(
                                name = "system.memory",
                                description = "Memory usage",
                                unit = "bytes",
                                gauge = Present(OTLPGauge(
                                    dataPoints = Seq(NumberDataPoint(
                                        startTimeUnixNano = "100",
                                        timeUnixNano = "200",
                                        asDouble = Present(1073741824.0)
                                    ))
                                ))
                            ))
                        ))
                    ))
                )
                for
                    _        <- OTLPClient.sendMetrics(config, request)
                    received <- metricCh.take
                yield
                    val metrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                    assert(metrics.head.name == "system.memory")
                    assert(metrics.head.gauge.isDefined)
                    assert(metrics.head.gauge.get.dataPoints.head.asDouble == Present(1073741824.0))
                end for
            }
        }

        "handles partial success with rejected data points" in run {
            for
                metricCh <- Channel.init[ExportMetricsRequest](10)
                traceHandler = mkTraceRoute.handler { _ =>
                    HttpResponse.ok.addField("body", ExportTraceResponse())
                }
                metricHandler = mkMetricRoute.handler { req =>
                    metricCh.put(req.fields.body).andThen {
                        HttpResponse.ok.addField(
                            "body",
                            ExportMetricsResponse(
                                partialSuccess = Present(MetricsPartialSuccess(
                                    rejectedDataPoints = 5,
                                    errorMessage = "too many"
                                ))
                            )
                        )
                    }
                }
                server <- HttpServer.init(0, "localhost")(traceHandler, metricHandler)
                config = testConfig(server.port)
                request = ExportMetricsRequest(
                    resourceMetrics = Seq(ResourceMetrics(
                        resource = OTLPClient.buildResource(config),
                        scopeMetrics = Seq(ScopeMetrics(
                            scope = InstrumentationScope("kyo-stats"),
                            metrics = Seq(Metric(
                                name = "test.metric",
                                description = "test",
                                unit = "1",
                                sum = Present(OTLPSum(
                                    dataPoints = Seq(NumberDataPoint(
                                        startTimeUnixNano = "1",
                                        timeUnixNano = "2",
                                        asInt = Present("10")
                                    )),
                                    aggregationTemporality = OTLPModel.DeltaTemporality,
                                    isMonotonic = true
                                ))
                            ))
                        ))
                    ))
                )
                _        <- OTLPClient.sendMetrics(config, request)
                received <- metricCh.take
            yield assert(received.resourceMetrics.head.scopeMetrics.head.metrics.head.name == "test.metric")
        }
    }

    "buildResource" - {

        "includes service name and SDK metadata" in {
            val config   = testConfig(0)
            val resource = OTLPClient.buildResource(config)
            val attrs    = resource.attributes
            assert(attrs.exists(kv => kv.key == "service.name" && kv.value.stringValue == Present("test-client")))
            assert(attrs.exists(kv => kv.key == "telemetry.sdk.name" && kv.value.stringValue == Present("kyo")))
            assert(attrs.exists(kv => kv.key == "telemetry.sdk.language" && kv.value.stringValue == Present("scala")))
            assert(attrs.exists(kv => kv.key == "telemetry.sdk.version" && kv.value.stringValue == Present("1.0.0")))
        }

        "includes custom resource attributes" in {
            val config   = testConfig(0)
            val resource = OTLPClient.buildResource(config)
            assert(resource.attributes.exists(kv => kv.key == "env" && kv.value.stringValue == Present("test")))
        }
    }

end OTLPClientTest
