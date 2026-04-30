package kyo.stats.otlp

import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import kyo.stats.internal.TraceExporter
import kyo.stats.internal.TraceSpan
import kyo.stats.internal.UnsafeTraceSpan

class OTLPTraceExporterTest extends Test:

    import AllowUnsafe.embrace.danger

    private def now() = java.time.Instant.now()

    "noop exporter" - {
        val exporter = TraceExporter.noop

        "startSpan returns noop" in {
            val span = exporter.startSpan(List("test"), "op", now())
            assert(span eq UnsafeTraceSpan.noop)
        }

        "end is safe" in {
            val span = exporter.startSpan(List("test"), "op", now())
            span.end(now())
            succeed
        }

        "event is safe" in {
            val span = exporter.startSpan(List("test"), "op", now())
            span.event("test-event", Attributes.empty, now())
            succeed
        }

        "setStatus is safe for all statuses" in {
            val span = exporter.startSpan(List("test"), "op", now())
            span.setStatus(UnsafeTraceSpan.Status.Unset)
            span.setStatus(UnsafeTraceSpan.Status.Ok)
            span.setStatus(UnsafeTraceSpan.Status.Error("test error"))
            succeed
        }
    }

    "trace integration" - {
        val exporter = TraceExporter.noop

        "executes body when disabled" in run {
            TraceSpan.trace(exporter, List("test"), "op") {
                42
            }.map(result => assert(result == 42))
        }

        "sets current span in trace block" in run {
            TraceSpan.trace(exporter, List("test"), "op") {
                TraceSpan.current
            }.map { span =>
                span match
                    case Present(_) => succeed
                    case _          => fail("Expected current span to be set")
            }
        }

        "nested traces work" in run {
            TraceSpan.trace(exporter, List("test"), "outer") {
                TraceSpan.trace(exporter, List("test"), "inner") {
                    TraceSpan.current
                }
            }.map { span =>
                span match
                    case Present(_) => succeed
                    case _          => fail("Expected current span")
            }
        }

        "trace with attributes" in run {
            val attrs = Attributes.add("http.method", "GET").add("http.status_code", 200)
            TraceSpan.trace(exporter, List("http"), "request", attrs) {
                TraceSpan.current
            }.map { span =>
                span match
                    case Present(_) => succeed
                    case _          => fail("Expected current span")
            }
        }
    }

    def exporterTestConfig(
        port: Int,
        queueSize: Int = 100,
        batchSize: Int = 50,
        scheduleDelay: Duration = 60.seconds
    ) = OTLPConfig(
        endpoint = s"http://localhost:$port",
        tracesEndpoint = s"http://localhost:$port/v1/traces",
        metricsEndpoint = s"http://localhost:$port/v1/metrics",
        headers = Map.empty,
        timeout = 5.seconds,
        compression = "none",
        serviceName = "test-exporter",
        resourceAttributes = Map.empty,
        bspScheduleDelay = scheduleDelay,
        bspMaxQueueSize = queueSize,
        bspMaxExportBatchSize = batchSize,
        bspExportTimeout = 30.seconds,
        metricExportInterval = 60.seconds,
        metricExportTimeout = 30.seconds
    )

    def mkTraceRoute = HttpRoute.postRaw("v1" / "traces")
        .request(_.bodyJson[ExportTraceRequest])
        .response(_.bodyJson[ExportTraceResponse])

    def mkMetricRoute = HttpRoute.postRaw("v1" / "metrics")
        .request(_.bodyJson[ExportMetricsRequest])
        .response(_.bodyJson[ExportMetricsResponse])

    def defaultHandlers =
        val traceHandler = mkTraceRoute.handler { _ =>
            HttpResponse.ok.addField("body", ExportTraceResponse())
        }
        val metricHandler = mkMetricRoute.handler { _ =>
            HttpResponse.ok.addField("body", ExportMetricsResponse())
        }
        (traceHandler, metricHandler)
    end defaultHandlers

    "startSpan" - {

        "generates valid hex trace and span IDs" taggedAs (jvmOnly) in run {
            Clock.withTimeControl { control =>
                for
                    traceCh <- Channel.init[ExportTraceRequest](10)
                    traceHandler = mkTraceRoute.handler { req =>
                        traceCh.put(req.fields.body).andThen {
                            HttpResponse.ok.addField("body", ExportTraceResponse())
                        }
                    }
                    (_, metricHandler) = defaultHandlers
                    server <- HttpServer.init(0, "localhost")(traceHandler, metricHandler)
                    config   = exporterTestConfig(server.port, batchSize = 1)
                    exporter = OTLPTraceExporter.init(config)
                    _ <- control.advance(100.millis)
                    _ = exporter.startSpan(List("test"), "id-test", now()).end(now())
                    received <- traceCh.take
                yield
                    val span = received.resourceSpans.head.scopeSpans.head.spans.head
                    assert(span.traceId.length == 32, s"traceId should be 32 hex chars, got ${span.traceId.length}")
                    assert(span.spanId.length == 16, s"spanId should be 16 hex chars, got ${span.spanId.length}")
                    assert(span.traceId.matches("[0-9a-f]+"), s"traceId should be lowercase hex: ${span.traceId}")
                    assert(span.spanId.matches("[0-9a-f]+"), s"spanId should be lowercase hex: ${span.spanId}")
                    assert(span.parentSpanId.isEmpty, "root span should have empty parentSpanId")
            }
        }

        "child inherits traceId from Propagatable parent" taggedAs (jvmOnly) in run {
            Clock.withTimeControl { control =>
                for
                    traceCh <- Channel.init[ExportTraceRequest](10)
                    traceHandler = mkTraceRoute.handler { req =>
                        traceCh.put(req.fields.body).andThen {
                            HttpResponse.ok.addField("body", ExportTraceResponse())
                        }
                    }
                    (_, metricHandler) = defaultHandlers
                    server <- HttpServer.init(0, "localhost")(traceHandler, metricHandler)
                    config   = exporterTestConfig(server.port, batchSize = 2)
                    exporter = OTLPTraceExporter.init(config)
                    _ <- control.advance(100.millis)
                    parent = exporter.startSpan(List("test"), "parent-op", now())
                    child  = exporter.startSpan(List("test"), "child-op", now(), parent = Some(parent))
                    _      = child.end(now())
                    _      = parent.end(now())
                    received <- traceCh.take
                yield
                    val spans      = received.resourceSpans.head.scopeSpans.head.spans
                    val parentSpan = spans.find(_.name == "parent-op").get
                    val childSpan  = spans.find(_.name == "child-op").get
                    assert(childSpan.traceId == parentSpan.traceId, "child should inherit parent's traceId")
                    assert(childSpan.parentSpanId == parentSpan.spanId, "child's parentSpanId should be parent's spanId")
                    assert(parentSpan.parentSpanId.isEmpty, "parent should have empty parentSpanId")
            }
        }

        "non-Propagatable parent generates new traceId" taggedAs (jvmOnly) in run {
            Clock.withTimeControl { control =>
                for
                    traceCh <- Channel.init[ExportTraceRequest](10)
                    traceHandler = mkTraceRoute.handler { req =>
                        traceCh.put(req.fields.body).andThen {
                            HttpResponse.ok.addField("body", ExportTraceResponse())
                        }
                    }
                    (_, metricHandler) = defaultHandlers
                    server <- HttpServer.init(0, "localhost")(traceHandler, metricHandler)
                    config   = exporterTestConfig(server.port, batchSize = 1)
                    exporter = OTLPTraceExporter.init(config)
                    _ <- control.advance(100.millis)
                    nonPropParent = UnsafeTraceSpan.noop
                    _             = exporter.startSpan(List("test"), "orphan-op", now(), parent = Some(nonPropParent)).end(now())
                    received <- traceCh.take
                yield
                    val span = received.resourceSpans.head.scopeSpans.head.spans.head
                    assert(span.traceId.length == 32, "should generate fresh traceId for non-Propagatable parent")
                    assert(span.parentSpanId.isEmpty, "should have empty parentSpanId for non-Propagatable parent")
            }
        }
    }

    "flush" - {

        "recursive flush drains all spans at exact batch boundary" taggedAs (jvmOnly) in run {
            Clock.withTimeControl { control =>
                for
                    traceCh <- Channel.init[ExportTraceRequest](10)
                    traceHandler = mkTraceRoute.handler { req =>
                        traceCh.put(req.fields.body).andThen {
                            HttpResponse.ok.addField("body", ExportTraceResponse())
                        }
                    }
                    (_, metricHandler) = defaultHandlers
                    server <- HttpServer.init(0, "localhost")(traceHandler, metricHandler)
                    // batchSize=3, queue 6 spans → flush should recurse once (3+3)
                    config   = exporterTestConfig(server.port, queueSize = 100, batchSize = 3)
                    exporter = OTLPTraceExporter.init(config)
                    _ <- control.advance(100.millis)
                    _ = (1 to 6).foreach { i =>
                        exporter.startSpan(List("test"), s"batch-span-$i", now()).end(now())
                    }
                    _    <- exporter.flush(config)
                    req1 <- traceCh.take
                    req2 <- traceCh.take
                yield
                    val spans1 = req1.resourceSpans.head.scopeSpans.head.spans
                    val spans2 = req2.resourceSpans.head.scopeSpans.head.spans
                    assert(spans1.size == 3, s"First batch should have 3 spans, got ${spans1.size}")
                    assert(spans2.size == 3, s"Second batch should have 3 spans, got ${spans2.size}")
            }
        }
    }

    "queue overflow" - {

        "drops spans when queue is full" taggedAs (jvmOnly) in run {
            Clock.withTimeControl { control =>
                for
                    traceCh <- Channel.init[ExportTraceRequest](10)
                    traceHandler = mkTraceRoute.handler { req =>
                        traceCh.put(req.fields.body).andThen {
                            HttpResponse.ok.addField("body", ExportTraceResponse())
                        }
                    }
                    (_, metricHandler) = defaultHandlers
                    server <- HttpServer.init(0, "localhost")(traceHandler, metricHandler)
                    config   = exporterTestConfig(server.port, queueSize = 2, batchSize = 100)
                    exporter = OTLPTraceExporter.init(config)
                    _ <- control.advance(100.millis)
                    _ = (1 to 10).foreach { i =>
                        val span = exporter.startSpan(List("test"), s"span-$i", now())
                        span.end(now())
                    }
                    _        <- exporter.flush(config)
                    received <- traceCh.take
                yield
                    val spans = received.resourceSpans.head.scopeSpans.head.spans
                    assert(spans.size <= 2, s"Expected at most 2 spans (queue capacity), got ${spans.size}")
                    assert(spans.nonEmpty, "Expected at least 1 span")
            }
        }
    }

    "flush signal" - {

        "triggers eager flush on batch size threshold" taggedAs (jvmOnly) in run {
            Clock.withTimeControl { control =>
                for
                    traceCh <- Channel.init[ExportTraceRequest](10)
                    traceHandler = mkTraceRoute.handler { req =>
                        traceCh.put(req.fields.body).andThen {
                            HttpResponse.ok.addField("body", ExportTraceResponse())
                        }
                    }
                    (_, metricHandler) = defaultHandlers
                    server <- HttpServer.init(0, "localhost")(traceHandler, metricHandler)
                    config   = exporterTestConfig(server.port, queueSize = 100, batchSize = 3)
                    exporter = OTLPTraceExporter.init(config)
                    _ <- control.advance(100.millis)
                    _ = (1 to 3).foreach { i =>
                        val span = exporter.startSpan(List("test"), s"signal-span-$i", now())
                        span.end(now())
                    }
                    received <- traceCh.take
                yield
                    val spans = received.resourceSpans.head.scopeSpans.head.spans
                    assert(spans.size == 3, s"Expected 3 spans from signal flush, got ${spans.size}")
            }
        }
    }

end OTLPTraceExporterTest
