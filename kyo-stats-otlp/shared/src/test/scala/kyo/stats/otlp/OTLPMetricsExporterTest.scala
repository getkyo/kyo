package kyo.stats.otlp

import kyo.*
import kyo.stats.*

class OTLPMetricsExporterTest extends kyo.test.Test[Any]:

    // The periodic-export leaves read the PROCESS-GLOBAL stats registry (it also holds the scheduler's own metrics) and
    // register WeakReference-held counters/histograms that a concurrent leaf's GC pressure can collect before the export
    // interval fires. Run this suite sequentially (shared global registry); ScalaTest ran it sequentially too.
    // Socket-only opt-out (see BaseHttpTest): the HttpServer/HttpClient this suite runs leaves a
    // transport-deferred socket:[inode] no allowlist can match; thread, fiber, and file detection stay on.
    override def config = super.config.sequential.leakCheckSockets(false)

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

    /** Takes exports until `name` appears, returning that `Metric`.
      *
      * `keepAlive` is the registered metric instance: the registry holds only a WeakReference to it, so a
      * concurrent leaf's GC pressure can collect it mid-loop and it would then vanish from every export.
      * Capturing it inside the loop closure keeps it strongly reachable, the same recipe the leaves above use.
      */
    def awaitMetric(
        metricCh: Channel[ExportMetricsRequest],
        name: String,
        keepAlive: AnyRef
    )(using Frame): Metric < (Async & Abort[Any]) =
        Loop.indexed { i =>
            discard(keepAlive)
            metricCh.take.map { received =>
                val allMetrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                allMetrics.find(_.name == name) match
                    case Some(m)       => Loop.done(m)
                    case None if i < 9 => Loop.continue
                    case None          => throw new AssertionError(s"Metric $name not found after ${i + 1} exports")
                end match
            }
        }
    end awaitMetric

    "periodic export" - {

        "exports registered counter at interval".onlyJvm in {
            withCollector { (config, metricCh) =>
                val uniqueName = "test.export.counter." + java.util.UUID.randomUUID().toString.take(8)
                val counter    = Stat.initScope("test", "export").initCounter(uniqueName, "test counter")
                for
                    _ <- counter.add(100)
                    _ <- OTLPMetricsExporter.run(config)
                    // Same Loop+reachability pattern as the histogram test below — the first export
                    // may fire before the counter is fully registered with the global registry, and
                    // the registry holds a WeakReference to the underlying UnsafeCounter so the JVM
                    // is otherwise free to GC `counter` mid-test. Capture `counter` inside the Loop
                    // closure to keep it strongly reachable.
                    found <- Loop.indexed { i =>
                        discard(counter)
                        metricCh.take.map { received =>
                            val allMetrics = received.resourceMetrics.head.scopeMetrics.head.metrics
                            allMetrics.find(_.name == s"test.export.$uniqueName") match
                                case Some(m)       => Loop.done(m)
                                case None if i < 9 => Loop.continue
                                case None => throw new AssertionError(
                                        s"Counter test.export.$uniqueName not found after ${i + 1} exports"
                                    )
                            end match
                        }
                    }
                yield
                    assert(found.sum.isDefined)
                    assert(found.sum.get.dataPoints.head.asInt == Present("100"))
                end for
            }
        }

        "exports registered histogram at interval".onlyJvm in {
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

        "exports registered gauge at interval".onlyJvm.flaky in {
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

        // Histograms never drain: their buckets, count, min, max and sum are lifetime values, so they are
        // exported with CUMULATIVE temporality and a fixed series start. Counters DO drain on read
        // (UnsafeCounter.get is adder.sumThenReset), so they keep DELTA temporality and a per-export start.
        // These leaves pin both halves of that contract against the real exporter over two exports.

        "a histogram data point carries CumulativeTemporality and a fixed series-start startTimeUnixNano".onlyJvm in {
            withCollector { (config, metricCh) =>
                val uniqueName = "test.export.cumulative." + java.util.UUID.randomUUID().toString.take(8)
                val histogram  = Stat.initScope("test", "export").initHistogram(uniqueName, "cumulative histogram")
                for
                    _      <- histogram.observe(42.0)
                    _      <- OTLPMetricsExporter.run(config)
                    first  <- awaitMetric(metricCh, s"test.export.$uniqueName", histogram)
                    second <- awaitMetric(metricCh, s"test.export.$uniqueName", histogram)
                yield
                    val firstHist  = first.histogram.get
                    val secondHist = second.histogram.get
                    assert(firstHist.aggregationTemporality == OTLPModel.CumulativeTemporality)
                    assert(secondHist.aggregationTemporality == OTLPModel.CumulativeTemporality)
                    val firstPoint  = firstHist.dataPoints.head
                    val secondPoint = secondHist.dataPoints.head
                    // The series start is fixed: a start that advanced with each export (the previous-export
                    // instant a delta point carries) would differ here.
                    assert(firstPoint.startTimeUnixNano == secondPoint.startTimeUnixNano)
                    assert(secondPoint.timeUnixNano.toLong > firstPoint.timeUnixNano.toLong)
                    assert(secondPoint.startTimeUnixNano.toLong < secondPoint.timeUnixNano.toLong)
                end for
            }
        }

        "a counter data point still carries DeltaTemporality with an advancing start".onlyJvm in {
            withCollector { (config, metricCh) =>
                val uniqueName = "test.export.delta." + java.util.UUID.randomUUID().toString.take(8)
                val counter    = Stat.initScope("test", "export").initCounter(uniqueName, "delta counter")
                for
                    _     <- counter.add(10)
                    _     <- OTLPMetricsExporter.run(config)
                    first <- awaitMetric(metricCh, s"test.export.$uniqueName", counter)
                    _     <- counter.add(20)
                    // The counter drains on read, so it only reappears once it has new activity.
                    second <- awaitMetric(metricCh, s"test.export.$uniqueName", counter)
                yield
                    val firstSum  = first.sum.get
                    val secondSum = second.sum.get
                    assert(firstSum.aggregationTemporality == OTLPModel.DeltaTemporality)
                    assert(secondSum.aggregationTemporality == OTLPModel.DeltaTemporality)
                    val firstPoint  = firstSum.dataPoints.head
                    val secondPoint = secondSum.dataPoints.head
                    assert(firstPoint.asInt == Present("10"))
                    assert(secondPoint.asInt == Present("20"))
                    // A delta point's start is the previous export, so it advances with every export. A fixed
                    // series start (the histogram's shape) would report the same value on both.
                    assert(secondPoint.startTimeUnixNano.toLong > firstPoint.startTimeUnixNano.toLong)
                    assert(secondPoint.startTimeUnixNano.toLong >= firstPoint.timeUnixNano.toLong)
                    assert(secondPoint.startTimeUnixNano.toLong < secondPoint.timeUnixNano.toLong)
                end for
            }
        }

        "the exported histogram sum is the cumulative total and is stable across two exports".onlyJvm in {
            withCollector { (config, metricCh) =>
                val uniqueName = "test.export.sum." + java.util.UUID.randomUUID().toString.take(8)
                val histogram  = Stat.initScope("test", "export").initHistogram(uniqueName, "sum histogram")
                for
                    _      <- histogram.observe(100.0)
                    _      <- histogram.observe(250.0)
                    _      <- OTLPMetricsExporter.run(config)
                    first  <- awaitMetric(metricCh, s"test.export.$uniqueName", histogram)
                    second <- awaitMetric(metricCh, s"test.export.$uniqueName", histogram)
                yield
                    val firstPoint  = first.histogram.get.dataPoints.head
                    val secondPoint = second.histogram.get.dataPoints.head
                    // A draining implementation would report 350.0 then 0.0.
                    assert(firstPoint.sum == 350.0)
                    assert(secondPoint.sum == 350.0)
                    assert(firstPoint.count == "2")
                    assert(secondPoint.count == "2")
                end for
            }
        }

        "multiple intervals trigger multiple exports".onlyJvm in {
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
