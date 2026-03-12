package kyo.stats.otlp

import kyo.*
import kyo.stats.internal.*
import java.util.concurrent.atomic.AtomicReference

/** Periodically collects metrics from the global `StatsRegistry` and exports them to an OTLP collector.
  *
  * Counters and counter-gauges are exported as delta sums, histograms include explicit bucket boundaries and counts, and gauges report
  * their current value. Metrics with zero activity since the last export are skipped. Weak references to metric instances are cleaned up
  * during iteration to avoid a second pass that would deadlock on Scala Native.
  */
object OTLPMetricsExporter:

    private val lastExportTime = new AtomicReference[java.time.Instant](java.time.Instant.now())

    private def instantToNanos(instant: Instant): String =
        val j = instant.toJava
        (j.getEpochSecond * 1_000_000_000L + j.getNano).toString

    /** Starts a background fiber that exports metrics at `config.metricExportInterval`.
      *
      * @return
      *   A fiber handle that can be interrupted to stop periodic export
      */
    def run(config: OTLPConfig)(using Frame): Fiber[Unit, Unit] < Sync =
        Clock.repeatAtInterval(config.metricExportInterval) {
            Abort.run[Timeout] {
                Async.timeout(config.metricExportTimeout) {
                    flush(config)
                }
            }.unit
        }

    private def flush(config: OTLPConfig)(using Frame): Unit < Async =
        Clock.now.map { now =>
            Sync.defer {
                val prevExport = lastExportTime.getAndSet(now.toJava)
                val startStr   = (prevExport.getEpochSecond * 1_000_000_000L + prevExport.getNano).toString
                val nowStr     = instantToNanos(now)

                val registry = StatsRegistry.internal
                val metrics  = scala.collection.mutable.ArrayBuffer[Metric]()

                // Inline gc (remove entries with collected WeakReferences) during iteration
                // to avoid a second forEach on the same map, which deadlocks on Scala Native.

                registry.counters.map.forEach { (path, tuple) =>
                    val (ref, desc) = tuple
                    val counter     = ref.get()
                    if (counter == null) {
                        registry.counters.map.remove(path): Unit
                    } else {
                        val delta = counter.delta()
                        if (delta != 0L) {
                            metrics += Metric(
                                name = path.mkString("."),
                                description = desc,
                                unit = "1",
                                sum = Some(OTLPSum(
                                    dataPoints = Seq(NumberDataPoint(
                                        startTimeUnixNano = startStr,
                                        timeUnixNano = nowStr,
                                        asInt = Some(delta.toString)
                                    )),
                                    aggregationTemporality = OTLPModel.DeltaTemporality,
                                    isMonotonic = true
                                ))
                            )
                        }
                    }
                }

                registry.counterGauges.map.forEach { (path, tuple) =>
                    val (ref, desc) = tuple
                    val cg          = ref.get()
                    if (cg == null) {
                        registry.counterGauges.map.remove(path): Unit
                    } else {
                        val delta = cg.delta()
                        if (delta != 0L) {
                            metrics += Metric(
                                name = path.mkString("."),
                                description = desc,
                                unit = "1",
                                sum = Some(OTLPSum(
                                    dataPoints = Seq(NumberDataPoint(
                                        startTimeUnixNano = startStr,
                                        timeUnixNano = nowStr,
                                        asInt = Some(delta.toString)
                                    )),
                                    aggregationTemporality = OTLPModel.DeltaTemporality,
                                    isMonotonic = true
                                ))
                            )
                        }
                    }
                }

                registry.histograms.map.forEach { (path, tuple) =>
                    val (ref, desc) = tuple
                    val histogram   = ref.get()
                    if (histogram == null) {
                        registry.histograms.map.remove(path): Unit
                    } else {
                        val summary = histogram.summary()
                        if (summary.count > 0) {
                            metrics += Metric(
                                name = path.mkString("."),
                                description = desc,
                                unit = "1",
                                histogram = Some(OTLPHistogram(
                                    dataPoints = Seq(HistogramDataPoint(
                                        startTimeUnixNano = startStr,
                                        timeUnixNano = nowStr,
                                        count = summary.count.toString,
                                        explicitBounds = summary.boundaries.toSeq,
                                        bucketCounts = summary.bucketCounts.map(_.toString).toSeq,
                                        min = summary.min,
                                        max = summary.max
                                    )),
                                    aggregationTemporality = OTLPModel.DeltaTemporality
                                ))
                            )
                        }
                    }
                }

                registry.gauges.map.forEach { (path, tuple) =>
                    val (ref, desc) = tuple
                    val gauge       = ref.get()
                    if (gauge == null) {
                        registry.gauges.map.remove(path): Unit
                    } else {
                        val current = gauge.collect()
                        metrics += Metric(
                            name = path.mkString("."),
                            description = desc,
                            unit = "1",
                            gauge = Some(OTLPGauge(
                                dataPoints = Seq(NumberDataPoint(
                                    startTimeUnixNano = startStr,
                                    timeUnixNano = nowStr,
                                    asDouble = Some(current)
                                ))
                            ))
                        )
                    }
                }

                if (metrics.nonEmpty) {
                    val request = ExportMetricsRequest(
                        resourceMetrics = Seq(ResourceMetrics(
                            resource = OTLPClient.buildResource(config),
                            scopeMetrics = Seq(ScopeMetrics(
                                scope = InstrumentationScope("kyo-stats", version = "1.0.0"),
                                metrics = metrics.toSeq
                            ))
                        ))
                    )
                    OTLPClient.sendMetrics(config, request)
                } else {
                    ()
                }
            }
        }
end OTLPMetricsExporter
