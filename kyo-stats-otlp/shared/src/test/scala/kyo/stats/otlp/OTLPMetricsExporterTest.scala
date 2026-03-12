package kyo.stats.otlp

import kyo.*
import kyo.stats.*
import kyo.stats.internal.StatsRegistry

class OTLPMetricsExporterTest extends Test:

    def testConfig(port: Int) = OTLPConfig(
        endpoint = s"http://localhost:$port",
        tracesEndpoint = s"http://localhost:$port/v1/traces",
        metricsEndpoint = s"http://localhost:$port/v1/metrics",
        headers = Map.empty,
        timeout = 5.seconds,
        compression = "none",
        serviceName = "test-metrics",
        resourceAttributes = Map("env" -> "test"),
        bspScheduleDelay = 100.millis,
        bspMaxQueueSize = 100,
        bspMaxExportBatchSize = 50,
        bspExportTimeout = 30.seconds,
        metricExportInterval = 1.second,
        metricExportTimeout = 30.seconds
    )

    def withCollector[A, S](
        test: (OTLPConfig, Channel[ExportMetricsRequest]) => A < (S & Async & Abort[Any])
    )(using Frame): A < (S & Async & Scope & Abort[Any]) =
        for
            metricCh <- Channel.init[ExportMetricsRequest](100)
            traceRoute = HttpRoute.postRaw("v1" / "traces")
                .request(_.bodyJson[ExportTraceRequest])
                .response(_.bodyJson[ExportTraceResponse])
            metricRoute = HttpRoute.postRaw("v1" / "metrics")
                .request(_.bodyJson[ExportMetricsRequest])
                .response(_.bodyJson[ExportMetricsResponse])
            traceHandler = traceRoute.handler { _ =>
                HttpResponse.ok.addField("body", ExportTraceResponse())
            }
            metricHandler = metricRoute.handler { req =>
                metricCh.put(req.fields.body).andThen {
                    HttpResponse.ok.addField("body", ExportMetricsResponse())
                }
            }
            server <- HttpServer.init(0, "localhost")(traceHandler, metricHandler)
            config = testConfig(server.port)
            result <- test(config, metricCh)
        yield result

    "periodic export" - {

        "exports registered counter at interval" in run {
            Clock.withTimeControl { control =>
                withCollector { (config, metricCh) =>
                    val uniqueName = "test.export.counter." + java.util.UUID.randomUUID().toString.take(8)
                    val counter    = StatsRegistry.scope("test", "export").counter(uniqueName, "test counter")
                    counter.add(100)
                    for
                        fiber    <- OTLPMetricsExporter.run(config)
                        _        <- control.advance(config.metricExportInterval)
                        received <- metricCh.take
                        _        <- fiber.interrupt
                    yield
                        val allMetrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                        val found      = allMetrics.find(_.name == s"test.export.$uniqueName")
                        assert(found.isDefined)
                        assert(found.get.sum.isDefined)
                        assert(found.get.sum.get.dataPoints.head.asInt == Some("100"))
                }
            }
        }

        "exports registered histogram at interval" in run {
            Clock.withTimeControl { control =>
                withCollector { (config, metricCh) =>
                    val uniqueName = "test.export.histogram." + java.util.UUID.randomUUID().toString.take(8)
                    val histogram  = StatsRegistry.scope("test", "export").histogram(uniqueName, "test histogram")
                    histogram.observe(42.0)
                    histogram.observe(7.0)
                    for
                        fiber    <- OTLPMetricsExporter.run(config)
                        _        <- control.advance(config.metricExportInterval)
                        received <- metricCh.take
                        _        <- fiber.interrupt
                    yield
                        val allMetrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                        val found      = allMetrics.find(_.name == s"test.export.$uniqueName")
                        assert(found.isDefined)
                        assert(found.get.histogram.isDefined)
                        assert(found.get.histogram.get.dataPoints.head.count == "2")
                }
            }
        }

        "exports registered gauge at interval" in run {
            Clock.withTimeControl { control =>
                withCollector { (config, metricCh) =>
                    val uniqueName = "test.export.gauge." + java.util.UUID.randomUUID().toString.take(8)
                    @volatile var gaugeValue = 99.5
                    val _ = StatsRegistry.scope("test", "export").gauge(uniqueName, "test gauge")(gaugeValue)
                    for
                        fiber    <- OTLPMetricsExporter.run(config)
                        _        <- control.advance(config.metricExportInterval)
                        received <- metricCh.take
                        _        <- fiber.interrupt
                    yield
                        val allMetrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                        val found      = allMetrics.find(_.name == s"test.export.$uniqueName")
                        assert(found.isDefined)
                        assert(found.get.gauge.isDefined)
                        assert(found.get.gauge.get.dataPoints.head.asDouble == Some(99.5))
                }
            }
        }

        "multiple intervals trigger multiple exports" in run {
            Clock.withTimeControl { control =>
                withCollector { (config, metricCh) =>
                    val uniqueName = "test.export.multi." + java.util.UUID.randomUUID().toString.take(8)
                    val counter    = StatsRegistry.scope("test", "export").counter(uniqueName, "test counter")
                    counter.add(10)
                    for
                        fiber <- OTLPMetricsExporter.run(config)
                        _     <- control.advance(config.metricExportInterval)
                        req1  <- metricCh.take
                        _     = counter.add(20)
                        _     <- control.advance(config.metricExportInterval)
                        req2  <- metricCh.take
                        _     <- fiber.interrupt
                    yield
                        val metrics1 = req1.resourceMetrics.head.scopeMetrics.head.metrics
                        val metrics2 = req2.resourceMetrics.head.scopeMetrics.head.metrics
                        assert(metrics1.exists(_.name == s"test.export.$uniqueName"))
                        assert(metrics2.exists(_.name == s"test.export.$uniqueName"))
                }
            }
        }
    }

end OTLPMetricsExporterTest
