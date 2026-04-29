package kyo.stats.otlp

import kyo.*
import kyo.stats.*

class OTLPMetricsExporterTest extends Test:

    import AllowUnsafe.embrace.danger

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
            withCollector { (config, metricCh) =>
                val uniqueName = "test.export.counter." + java.util.UUID.randomUUID().toString.take(8)
                val counter    = Stat.initScope("test", "export").initCounter(uniqueName, "test counter")
                for
                    _        <- counter.add(100)
                    _        <- OTLPMetricsExporter.run(config)
                    received <- metricCh.take
                yield
                    val allMetrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                    val found      = allMetrics.find(_.name == s"test.export.$uniqueName")
                    assert(found.isDefined)
                    assert(found.get.sum.isDefined)
                    assert(found.get.sum.get.dataPoints.head.asInt == Present("100"))
                end for
            }
        }

        "exports registered histogram at interval" in run {
            withCollector { (config, metricCh) =>
                val uniqueName = "test.export.histogram." + java.util.UUID.randomUUID().toString.take(8)
                val histogram  = Stat.initScope("test", "export").initHistogram(uniqueName, "test histogram")
                for
                    _ <- histogram.observe(42.0)
                    _ <- histogram.observe(7.0)
                    _ <- OTLPMetricsExporter.run(config)
                    // The first export may not include the histogram if it fires before the metric is
                    // fully registered. Take up to 10 exports — arm64 runners are noticeably slower
                    // and 3 attempts was sometimes too tight there.
                    //
                    // The registry holds a WeakReference to the underlying UnsafeHistogram, so the
                    // JVM is otherwise free to GC `histogram` once the for-comprehension stops
                    // using it (observed on JVM-arm64 in CI: WeakReference goes null mid-loop and
                    // the histogram disappears from every exported batch). Capturing `histogram`
                    // inside the Loop closure keeps it strongly reachable until the Loop completes,
                    // and works on all platforms (vs `Reference.reachabilityFence` which is JVM-only).
                    found <- Loop.indexed { i =>
                        discard(histogram)
                        metricCh.take.map { received =>
                            val allMetrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                            allMetrics.find(_.name == s"test.export.$uniqueName") match
                                case Some(m)       => Loop.done(m)
                                case None if i < 9 => Loop.continue
                                case None => throw new AssertionError(
                                        s"Histogram test.export.$uniqueName not found after ${i + 1} exports"
                                    )
                            end match
                        }
                    }
                yield
                    assert(found.histogram.isDefined)
                    assert(found.histogram.get.dataPoints.head.count == "2")
                end for
            }
        }

        "exports registered gauge at interval" in run {
            withCollector { (config, metricCh) =>
                val uniqueName           = "test.export.gauge." + java.util.UUID.randomUUID().toString.take(8)
                @volatile var gaugeValue = 99.5
                val _                    = Stat.initScope("test", "export").initGauge(uniqueName, "test gauge")(gaugeValue)
                for
                    _        <- OTLPMetricsExporter.run(config)
                    received <- metricCh.take
                yield
                    val allMetrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                    val found      = allMetrics.find(_.name == s"test.export.$uniqueName")
                    assert(found.isDefined)
                    assert(found.get.gauge.isDefined)
                    assert(found.get.gauge.get.dataPoints.head.asDouble == Present(99.5))
                end for
            }
        }

        "multiple intervals trigger multiple exports" in run {
            withCollector { (config, metricCh) =>
                val uniqueName = "test.export.multi." + java.util.UUID.randomUUID().toString.take(8)
                val counter    = Stat.initScope("test", "export").initCounter(uniqueName, "test counter")
                for
                    _    <- counter.add(10)
                    _    <- OTLPMetricsExporter.run(config)
                    req1 <- metricCh.take
                    _    <- counter.add(20)
                    req2 <- metricCh.take
                yield
                    val all = Seq(req1, req2).flatMap(_.resourceMetrics.head.scopeMetrics.head.metrics)
                    assert(all.count(_.name == s"test.export.$uniqueName") >= 1)
                end for
            }
        }
    }

end OTLPMetricsExporterTest
