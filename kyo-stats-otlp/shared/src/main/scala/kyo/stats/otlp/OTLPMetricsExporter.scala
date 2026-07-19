package kyo.stats.otlp

import kyo.*
import kyo.stats.internal.*

/** Periodically collects metrics from the global `StatsRegistry` and exports them to an OTLP collector.
  *
  * Counters and counter-gauges are exported as DELTA sums: they drain on read, so each data point carries the activity since the previous
  * export and a counter with no activity is skipped. Histograms are exported with CUMULATIVE temporality and a fixed series-start time,
  * because their buckets, count, min, max and sum are lifetime values that never drain; a histogram that has ever been observed is
  * therefore re-sent on every export, carrying the same lifetime totals, rather than being skipped for having no new activity. Gauges
  * report their current value. Weak references to metric instances are cleaned up during iteration to avoid a second pass that would
  * deadlock on Scala Native.
  */
object OTLPMetricsExporter:

    private val lastExportTime =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Instant](Instant.Epoch)

    // The start of the histogram series, captured at the first flush and never advanced. A histogram data
    // point carries CUMULATIVE temporality because its buckets, count, min, max and sum are lifetime values
    // that never drain (the buckets are LongAdders and summary() is a non-draining snapshot every caller
    // re-reads), and a cumulative point's start time is the start of the SERIES, not the previous export.
    // A counter data point keeps the previous export as its start, because a counter genuinely drains on
    // read (UnsafeCounter.get is adder.sumThenReset).
    private val histogramSeriesStart =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init[Instant](Instant.Epoch)

    private def instantToNanos(instant: Instant): String =
        instant.toDuration.toNanos.toString

    /** Starts background fibers for periodic metric export. Fibers are registered with the enclosing Scope. */
    def run(config: OTLPConfig)(using Frame): Unit < (Sync & Scope) =
        Sync.Unsafe.defer {
            val trigger = Channel.Unsafe.init[Unit](1, Access.MultiProducerSingleConsumer)
            OTLPClient.startExportLoop(
                config.metricExportInterval,
                config.metricExportTimeout,
                "metrics",
                trigger
            )(flush(config))
        }

    private def flush(config: OTLPConfig)(using Frame): Unit < Async =
        Clock.now.map { now =>
            Sync.Unsafe.defer {
                val prevExport = lastExportTime.getAndSet(now)
                val startStr   = instantToNanos(prevExport)
                val nowStr     = instantToNanos(now)

                // The first flush fixes the histogram series start; every later flush reuses it.
                val seriesStart =
                    val current = histogramSeriesStart.get()
                    if current == Instant.Epoch then
                        discard(histogramSeriesStart.compareAndSet(Instant.Epoch, now))
                        histogramSeriesStart.get()
                    else current
                    end if
                end seriesStart
                val seriesStr = instantToNanos(seriesStart)

                val registry = StatsRegistry.internal
                val metrics  = ChunkBuilder.init[Metric]

                // Inline gc (remove entries with collected WeakReferences) during iteration
                // to avoid a second forEach on the same map, which deadlocks on Scala Native.
                registry.counters.map.forEach { (path, tuple) =>
                    val (ref, desc) = tuple
                    val counter     = ref.get()
                    if counter == null then
                        registry.counters.map.remove(path): Unit
                    else
                        val delta = counter.delta()
                        if delta != 0L then
                            metrics.addOne(Metric(
                                name = path.mkString("."),
                                description = desc,
                                unit = "1",
                                sum = Present(OTLPSum(
                                    dataPoints = Seq(NumberDataPoint(
                                        startTimeUnixNano = startStr,
                                        timeUnixNano = nowStr,
                                        asInt = Present(delta.toString)
                                    )),
                                    aggregationTemporality = OTLPModel.DeltaTemporality,
                                    isMonotonic = true
                                ))
                            ))
                        end if
                    end if
                }

                registry.counterGauges.map.forEach { (path, tuple) =>
                    val (ref, desc) = tuple
                    val cg          = ref.get()
                    if cg == null then
                        registry.counterGauges.map.remove(path): Unit
                    else
                        val delta = cg.delta()
                        if delta != 0L then
                            metrics.addOne(Metric(
                                name = path.mkString("."),
                                description = desc,
                                unit = "1",
                                sum = Present(OTLPSum(
                                    dataPoints = Seq(NumberDataPoint(
                                        startTimeUnixNano = startStr,
                                        timeUnixNano = nowStr,
                                        asInt = Present(delta.toString)
                                    )),
                                    aggregationTemporality = OTLPModel.DeltaTemporality,
                                    isMonotonic = true
                                ))
                            ))
                        end if
                    end if
                }

                registry.histograms.map.forEach { (path, tuple) =>
                    val (ref, desc) = tuple
                    val histogram   = ref.get()
                    if histogram == null then
                        registry.histograms.map.remove(path): Unit
                    else
                        val summary = histogram.summary()
                        if summary.count > 0 then
                            metrics.addOne(Metric(
                                name = path.mkString("."),
                                description = desc,
                                unit = "1",
                                histogram = Present(OTLPHistogram(
                                    dataPoints = Seq(HistogramDataPoint(
                                        startTimeUnixNano = seriesStr,
                                        timeUnixNano = nowStr,
                                        count = summary.count.toString,
                                        explicitBounds = summary.boundaries.toSeq,
                                        bucketCounts = summary.bucketCounts.map(_.toString).toSeq,
                                        min = summary.min,
                                        max = summary.max,
                                        sum = summary.sum
                                    )),
                                    aggregationTemporality = OTLPModel.CumulativeTemporality
                                ))
                            ))
                        end if
                    end if
                }

                registry.gauges.map.forEach { (path, tuple) =>
                    val (ref, desc) = tuple
                    val gauge       = ref.get()
                    if gauge == null then
                        registry.gauges.map.remove(path): Unit
                    else
                        val current = gauge.collect()
                        metrics.addOne(Metric(
                            name = path.mkString("."),
                            description = desc,
                            unit = "1",
                            gauge = Present(OTLPGauge(
                                dataPoints = Seq(NumberDataPoint(
                                    startTimeUnixNano = startStr,
                                    timeUnixNano = nowStr,
                                    asDouble = Present(current)
                                ))
                            ))
                        ))
                    end if
                }

                if metrics.knownSize > 0 then
                    val request = ExportMetricsRequest(
                        resourceMetrics = Seq(ResourceMetrics(
                            resource = OTLPClient.buildResource(config),
                            scopeMetrics = Seq(ScopeMetrics(
                                scope = OTLPClient.instrumentationScope,
                                metrics = metrics.result()
                            ))
                        ))
                    )
                    OTLPClient.sendMetrics(config, request)
                else
                    (
                )
                end if
            }
        }
end OTLPMetricsExporter
