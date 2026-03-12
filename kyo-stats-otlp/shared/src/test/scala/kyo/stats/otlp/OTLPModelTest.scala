package kyo.stats.otlp

import kyo.*

class OTLPModelTest extends Test:

    given AllowUnsafe = AllowUnsafe.embrace.danger

    val testConfig = OTLPConfig(
        endpoint = "http://localhost:4318",
        metricsEndpoint = "http://localhost:4318/v1/metrics",
        tracesEndpoint = "http://localhost:4318/v1/traces",
        headers = Map.empty,
        timeout = 10.seconds,
        compression = "none",
        serviceName = "test-service",
        resourceAttributes = Map("env" -> "test", "version" -> "1.0"),
        bspScheduleDelay = 5.seconds,
        bspMaxQueueSize = 2048,
        bspMaxExportBatchSize = 512,
        bspExportTimeout = 30.seconds,
        metricExportInterval = 60.seconds,
        metricExportTimeout = 30.seconds
    )

    "AnyValue" - {
        "string" in run {
            val v = AnyValue.string("hello")
            assert(v.stringValue == Some("hello"))
            assert(v.intValue.isEmpty)
            assert(v.doubleValue.isEmpty)
            assert(v.boolValue.isEmpty)
        }

        "int" in run {
            val v = AnyValue.int(42L)
            assert(v.intValue == Some("42"))
            assert(v.stringValue.isEmpty)
        }

        "int negative" in run {
            val v = AnyValue.int(-1L)
            assert(v.intValue == Some("-1"))
        }

        "int zero" in run {
            val v = AnyValue.int(0L)
            assert(v.intValue == Some("0"))
        }

        "double" in run {
            val v = AnyValue.double(3.14)
            assert(v.doubleValue == Some(3.14))
            assert(v.stringValue.isEmpty)
        }

        "boolean true" in run {
            val v = AnyValue.boolean(true)
            assert(v.boolValue == Some(true))
            assert(v.stringValue.isEmpty)
        }

        "boolean false" in run {
            val v = AnyValue.boolean(false)
            assert(v.boolValue == Some(false))
        }
    }

    "KeyValue" - {
        "construction" in run {
            val kv = KeyValue("key", AnyValue.string("value"))
            assert(kv.key == "key")
            assert(kv.value.stringValue == Some("value"))
        }

        "with different value types" in run {
            val kvStr  = KeyValue("str", AnyValue.string("hello"))
            val kvInt  = KeyValue("num", AnyValue.int(42))
            val kvDbl  = KeyValue("dbl", AnyValue.double(3.14))
            val kvBool = KeyValue("flag", AnyValue.boolean(true))

            assert(kvStr.value.stringValue == Some("hello"))
            assert(kvInt.value.intValue == Some("42"))
            assert(kvDbl.value.doubleValue == Some(3.14))
            assert(kvBool.value.boolValue == Some(true))
        }
    }

    "OTLPModel constants" - {
        "temporality values" in run {
            assert(OTLPModel.DeltaTemporality == 1)
            assert(OTLPModel.CumulativeTemporality == 2)
        }

        "span kind values" in run {
            assert(OTLPModel.SpanKindInternal == 1)
            assert(OTLPModel.SpanKindServer == 2)
            assert(OTLPModel.SpanKindClient == 3)
        }

        "status code values" in run {
            assert(OTLPModel.StatusUnset == 0)
            assert(OTLPModel.StatusOk == 1)
            assert(OTLPModel.StatusError == 2)
        }
    }

    "data model defaults" - {
        "SpanStatus defaults" in run {
            val status = SpanStatus()
            assert(status.code == 0)
            assert(status.message == "")
        }

        "SpanStatus with values" in run {
            val ok    = SpanStatus(code = OTLPModel.StatusOk)
            val error = SpanStatus(code = OTLPModel.StatusError, message = "something failed")

            assert(ok.code == 1)
            assert(ok.message == "")
            assert(error.code == 2)
            assert(error.message == "something failed")
        }

        "OTLPSpan defaults" in run {
            val span = OTLPSpan(
                traceId = "abc",
                spanId = "def",
                name = "test",
                startTimeUnixNano = "100",
                endTimeUnixNano = "200"
            )
            assert(span.parentSpanId == "")
            assert(span.kind == 1)
            assert(span.attributes.isEmpty)
            assert(span.events.isEmpty)
            assert(span.status.code == 0 && span.status.message == "")
        }

        "OTLPSpan with all fields" in run {
            val span = OTLPSpan(
                traceId = "0af7651916cd43dd8448eb211c80319c",
                spanId = "b7ad6b7169203331",
                parentSpanId = "a1b2c3d4e5f60718",
                name = "HTTP GET /api",
                kind = OTLPModel.SpanKindServer,
                startTimeUnixNano = "1000000000",
                endTimeUnixNano = "2000000000",
                attributes = Seq(KeyValue("http.method", AnyValue.string("GET"))),
                events = Seq(SpanEvent("exception", "1500000000", Seq(KeyValue("message", AnyValue.string("error"))))),
                status = SpanStatus(code = OTLPModel.StatusError, message = "Internal error")
            )
            assert(span.traceId == "0af7651916cd43dd8448eb211c80319c")
            assert(span.spanId == "b7ad6b7169203331")
            assert(span.parentSpanId == "a1b2c3d4e5f60718")
            assert(span.name == "HTTP GET /api")
            assert(span.kind == OTLPModel.SpanKindServer)
            assert(span.attributes.size == 1)
            assert(span.events.size == 1)
            assert(span.status.code == OTLPModel.StatusError)
        }

        "SpanEvent construction" in run {
            val event = SpanEvent(
                name = "exception",
                timeUnixNano = "1234567890",
                attributes = Seq(
                    KeyValue("exception.type", AnyValue.string("RuntimeException")),
                    KeyValue("exception.message", AnyValue.string("test error"))
                )
            )
            assert(event.name == "exception")
            assert(event.timeUnixNano == "1234567890")
            assert(event.attributes.size == 2)
        }

        "SpanEvent defaults" in run {
            val event = SpanEvent(name = "log", timeUnixNano = "100")
            assert(event.attributes.isEmpty)
        }
    }

    "OTLPClient.buildResource" - {
        "includes service name" in run {
            val resource = OTLPClient.buildResource(testConfig)
            assert(resource.attributes.exists(kv =>
                kv.key == "service.name" && kv.value.stringValue == Some("test-service")
            ))
        }

        "includes SDK metadata" in run {
            val resource = OTLPClient.buildResource(testConfig)
            assert(resource.attributes.exists(kv =>
                kv.key == "telemetry.sdk.name" && kv.value.stringValue == Some("kyo")
            ))
            assert(resource.attributes.exists(kv =>
                kv.key == "telemetry.sdk.language" && kv.value.stringValue == Some("scala")
            ))
            assert(resource.attributes.exists(kv =>
                kv.key == "telemetry.sdk.version" && kv.value.stringValue == Some("1.0.0")
            ))
        }

        "includes resource attributes" in run {
            val resource = OTLPClient.buildResource(testConfig)
            assert(resource.attributes.exists(kv =>
                kv.key == "env" && kv.value.stringValue == Some("test")
            ))
            assert(resource.attributes.exists(kv =>
                kv.key == "version" && kv.value.stringValue == Some("1.0")
            ))
        }

        "empty resource attributes" in run {
            val config   = testConfig.copy(resourceAttributes = Map.empty)
            val resource = OTLPClient.buildResource(config)
            assert(resource.attributes.size == 4)
        }

        "attribute count with resource attributes" in run {
            val resource = OTLPClient.buildResource(testConfig)
            assert(resource.attributes.size == 6)
        }
    }

    "OTLPConfig" - {
        "loadIfEnabled returns None when endpoint not set" in run {
            assert(OTLPConfig.loadIfEnabled().isEmpty)
        }

        "case class construction" in run {
            assert(testConfig.endpoint == "http://localhost:4318")
            assert(testConfig.serviceName == "test-service")
            assert(testConfig.compression == "none")
            assert(testConfig.bspMaxQueueSize == 2048)
            assert(testConfig.bspMaxExportBatchSize == 512)
        }
    }

    "metrics model" - {
        "Metric with histogram" in run {
            val metric = Metric(
                name = "http.request.duration",
                description = "Request duration in ms",
                unit = "ms",
                histogram = Some(OTLPHistogram(
                    dataPoints = Seq(HistogramDataPoint(
                        startTimeUnixNano = "100",
                        timeUnixNano = "200",
                        count = "10",
                        explicitBounds = Seq(1.0, 5.0, 10.0, 50.0),
                        bucketCounts = Seq("2", "3", "4", "1", "0"),
                        min = 0.5,
                        max = 42.0
                    )),
                    aggregationTemporality = OTLPModel.DeltaTemporality
                ))
            )
            assert(metric.name == "http.request.duration")
            assert(metric.histogram.isDefined)
            assert(metric.sum.isEmpty)
            assert(metric.gauge.isEmpty)
        }

        "Metric with sum" in run {
            val metric = Metric(
                name = "http.request.count",
                description = "Total requests",
                unit = "1",
                sum = Some(OTLPSum(
                    dataPoints = Seq(NumberDataPoint(
                        startTimeUnixNano = "100",
                        timeUnixNano = "200",
                        asInt = Some("42")
                    )),
                    aggregationTemporality = OTLPModel.DeltaTemporality,
                    isMonotonic = true
                ))
            )
            assert(metric.sum.isDefined)
            assert(metric.sum.get.isMonotonic)
            assert(metric.histogram.isEmpty)
        }

        "Metric with gauge" in run {
            val metric = Metric(
                name = "system.cpu.usage",
                description = "CPU usage",
                unit = "1",
                gauge = Some(OTLPGauge(
                    dataPoints = Seq(NumberDataPoint(
                        startTimeUnixNano = "100",
                        timeUnixNano = "200",
                        asDouble = Some(0.75)
                    ))
                ))
            )
            assert(metric.gauge.isDefined)
            assert(metric.gauge.get.dataPoints.head.asDouble == Some(0.75))
        }

        "ExportMetricsRequest structure" in run {
            val request = ExportMetricsRequest(
                resourceMetrics = Seq(ResourceMetrics(
                    resource = OTLPClient.buildResource(testConfig),
                    scopeMetrics = Seq(ScopeMetrics(
                        scope = InstrumentationScope("kyo-stats", version = "1.0.0"),
                        metrics = Seq.empty
                    ))
                ))
            )
            assert(request.resourceMetrics.size == 1)
            assert(request.resourceMetrics.head.scopeMetrics.head.scope.name == "kyo-stats")
        }

        "ExportTraceRequest structure" in run {
            val request = ExportTraceRequest(
                resourceSpans = Seq(ResourceSpans(
                    resource = OTLPClient.buildResource(testConfig),
                    scopeSpans = Seq(ScopeSpans(
                        scope = InstrumentationScope("kyo-stats", version = "1.0.0"),
                        spans = Seq(OTLPSpan(
                            traceId = "abc",
                            spanId = "def",
                            name = "test",
                            startTimeUnixNano = "100",
                            endTimeUnixNano = "200"
                        ))
                    ))
                ))
            )
            assert(request.resourceSpans.size == 1)
            assert(request.resourceSpans.head.scopeSpans.head.spans.size == 1)
        }
    }
end OTLPModelTest
