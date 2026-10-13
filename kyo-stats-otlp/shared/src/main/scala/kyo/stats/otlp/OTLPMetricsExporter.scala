package kyo.stats.otlp

import kyo.*
import kyo.stats.internal.*

/** Periodically collects metrics from the global `StatsRegistry` and exports them to an OTLP collector.
  *
  * An instrument is the metric at its registry path, and its series, one per attribute set observed, are the metric's data points, each
  * carrying its attributes as OTLP key-values. Counters and counter-gauges are exported as DELTA sums: they drain on read, so each data
  * point carries the activity since the previous export and a series with no activity is skipped. Histograms are exported with CUMULATIVE
  * temporality and a fixed series-start time, because their buckets, count, min, max and sum are lifetime values that never drain; a
  * histogram series that has ever been observed is therefore re-sent on every export, carrying the same lifetime totals, rather than being
  * skipped for having no new activity. Gauges report their current value. Weak references to metric instances are cleaned up during
  * iteration to avoid a second pass that would deadlock on Scala Native.
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

    /** Walks one store and hands each live instrument, with its path, description and unit, to `metric`; removes the entries whose
      * instrument was collected. Inline gc during the iteration, to avoid a second forEach on the same map, which deadlocks on Scala
      * Native. `metric` answers Absent for an instrument with nothing to report this export.
      */
    private def gather[A <: AnyRef](
        store: StatsRegistry.internal.Store[A],
        metrics: ChunkBuilder[Metric]
    )(metric: (A, String, String, String) => Maybe[Metric])(using AllowUnsafe): Unit =
        store.map.forEach { (path, entry) =>
            val instrument = entry.reference.get()
            if instrument == null then
                store.map.remove(path, entry): Unit
            else
                metric(instrument, path.mkString("."), entry.description, entry.unit) match
                    case Present(m) => metrics.addOne(m): Unit
                    case Absent     => ()
            end if
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

                def sum(name: String, description: String, unit: String, points: Seq[NumberDataPoint]): Maybe[Metric] =
                    if points.isEmpty then Absent
                    else
                        Present(Metric(
                            name = name,
                            description = description,
                            unit = unit,
                            sum = Present(OTLPSum(
                                dataPoints = points,
                                aggregationTemporality = OTLPModel.DeltaTemporality,
                                isMonotonic = true
                            ))
                        ))

                def deltaPoints(series: Seq[(kyo.stats.Attributes, Long)]): Seq[NumberDataPoint] =
                    series.collect {
                        case (attributes, delta) if delta != 0L =>
                            NumberDataPoint(
                                startTimeUnixNano = startStr,
                                timeUnixNano = nowStr,
                                asInt = Present(delta.toString),
                                attributes = KeyValue.of(attributes)
                            )
                    }

                gather(registry.counters, metrics) { (counter, name, description, unit) =>
                    sum(name, description, unit, deltaPoints(counter.series().map(s => (s.attributes, s.delta))))
                }
                gather(registry.counterGauges, metrics) { (gauge, name, description, unit) =>
                    sum(name, description, unit, deltaPoints(gauge.series().map(s => (s.attributes, s.delta))))
                }

                gather(registry.histograms, metrics) { (histogram, name, description, unit) =>
                    val points = histogram.series().collect {
                        case series if series.summary.count != 0L =>
                            val summary = series.summary
                            HistogramDataPoint(
                                startTimeUnixNano = seriesStr,
                                timeUnixNano = nowStr,
                                count = summary.count.toString,
                                explicitBounds = summary.boundaries.toSeq,
                                bucketCounts = summary.bucketCounts.map(_.toString).toSeq,
                                min = summary.min,
                                max = summary.max,
                                sum = summary.sum,
                                attributes = KeyValue.of(series.attributes)
                            )
                    }
                    if points.isEmpty then Absent
                    else
                        Present(Metric(
                            name = name,
                            description = description,
                            unit = unit,
                            histogram = Present(OTLPHistogram(
                                dataPoints = points,
                                aggregationTemporality = OTLPModel.CumulativeTemporality
                            ))
                        ))
                    end if
                }

                gather(registry.gauges, metrics) { (gauge, name, description, unit) =>
                    val points = gauge.series().map { series =>
                        NumberDataPoint(
                            startTimeUnixNano = startStr,
                            timeUnixNano = nowStr,
                            asDouble = Present(series.value),
                            attributes = KeyValue.of(series.attributes)
                        )
                    }
                    Present(Metric(
                        name = name,
                        description = description,
                        unit = unit,
                        gauge = Present(OTLPGauge(dataPoints = points))
                    ))
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
