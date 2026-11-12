package kyo

import kyo.stats.*
import kyo.stats.internal.*
import kyo.stats.internal.TraceExporter

/** A counter for tracking numeric values that only increase.
  *
  * A measurement carries the attributes of the series it belongs to, the way an OpenTelemetry data point carries attributes beside the
  * metric; a series is made the first time its attributes are observed. The methods without attributes are the series with none.
  */
abstract class Counter extends Serializable:
    /** The underlying unsafe counter implementation. */
    val unsafe: UnsafeCounter

    /** Get the current value of the counter.
      * @return
      *   The current count as a Long, wrapped in Sync
      */
    def get(using Frame): Long < Sync

    /** Increment the counter by 1.
      */
    def inc(using Frame): Unit < Sync

    /** Add a specific value to the counter.
      * @param v
      *   The value to add
      */
    def add(v: Long)(using Frame): Unit < Sync

    /** Add `v` to the series `attributes`. */
    def add(v: Long, attributes: Attributes)(using Frame): Unit < Sync

    /** Every series drained since its last read, the attribute-free one first: the read `get` is for one series. */
    def series(using Frame): Seq[UnsafeCounter.Series] < Sync
end Counter

/** A histogram for observing the distribution of values.
  *
  * A measurement carries the attributes of the series it belongs to, as for `Counter`; every series shares the histogram's boundaries.
  */
abstract class Histogram extends Serializable:
    /** The underlying unsafe histogram implementation. */
    val unsafe: UnsafeHistogram

    /** Record an observation of a long value.
      * @param v
      *   The value to observe
      */
    def observe(v: Long)(using Frame): Unit < Sync

    /** Record an observation of a double value.
      * @param v
      *   The value to observe
      */
    def observe(v: Double)(using Frame): Unit < Sync

    /** Record `v` in the series `attributes`. */
    def observe(v: Long, attributes: Attributes)(using Frame): Unit < Sync

    /** Record `v` in the series `attributes`. */
    def observe(v: Double, attributes: Attributes)(using Frame): Unit < Sync

    /** Read the current distribution: bucket counts, observation count, min, max and running sum.
      *
      * The safe-tier read that mirrors `Counter.get` and `Gauge.collect`. Reading is non-destructive: buckets
      * and sum describe the histogram's whole lifetime, so a dashboard can poll this without disturbing an
      * exporter reading the same instrument.
      * @return
      *   A snapshot of the distribution, wrapped in Sync
      */
    def summary(using Frame): Summary < Sync

    /** Every series' distribution, the attribute-free one first. Non-destructive, as `summary` is. */
    def series(using Frame): Seq[UnsafeHistogram.Series] < Sync
end Histogram

/** A gauge for measuring a specific value that can go up and down.
  */
abstract class Gauge extends Serializable:
    /** The underlying unsafe gauge implementation. */
    val unsafe: UnsafeGauge

    /** Collect the current value of the gauge.
      * @return
      *   The current value as a Double, wrapped in Sync
      */
    def collect(using Frame): Double < Sync

    /** Every series' current value, the attribute-free one first. */
    def series(using Frame): Seq[UnsafeGauge.Series] < Sync
end Gauge

/** A gauge that specifically measures counter-like values.
  */
abstract class CounterGauge extends Serializable:
    /** The underlying unsafe counter gauge implementation. */
    val unsafe: UnsafeCounterGauge

    /** Collect the current value of the counter gauge.
      * @return
      *   The current value as a Long, wrapped in Sync
      */
    def collect(using Frame): Long < Sync

    /** Every series drained since its last read, the attribute-free one first. */
    def series(using Frame): Seq[UnsafeCounterGauge.Series] < Sync
end CounterGauge

final class Stat(private val registryScope: StatsRegistry.Scope) extends Serializable:

    /** Create a new Stat instance with an additional scope.
      * @param path
      *   The path elements to add to the scope
      * @return
      *   A new Stat instance with the extended scope
      */
    def scope(path: String*): Stat =
        new Stat(registryScope.scope(path*))

    /** Initialize a new Counter.
      *
      * `unit` is the OpenTelemetry unit string an exporter sends with the metric, `"1"` for a dimensionless
      * count; a name carries no unit suffix. The counter's series are made as they are observed, with the
      * attributes of each measurement.
      *
      * @param name
      *   The name of the counter
      * @param description
      *   A description of the counter (default is "empty")
      * @param unit
      *   The unit of the counter (default is "1")
      * @return
      *   A new Counter instance
      */
    def initCounter(
        name: String,
        description: String = "empty",
        unit: String = "1"
    ): Counter =
        Stat.counter(registryScope.counter(name, description, unit))

    /** The counter already registered at `name`, or `Absent`. Never registers.
      *
      * `initCounter` is first-writer-wins: a reader that calls it for someone else's metric registers its
      * own instrument at that path and permanently shadows the producer's value, so a consumer reading a
      * metric it does not own has to ask instead of mint. That is what this is for: reading a family a
      * library registered (kyo-stats-machine's `machine.*`, an integration's own counters), and reading a
      * name that was never registered answers `Absent` rather than quietly creating it.
      *
      * The handle is the same safe `Counter` `initCounter` returns, so the read is `< Sync` and needs no
      * `AllowUnsafe` at the call site. `StatsRegistry`'s own `find*` remain the unsafe-tier equivalents an
      * exporter uses on the hot path.
      */
    def findCounter(name: String)(using Frame): Maybe[Counter] < Sync =
        Sync.Unsafe.defer(Maybe.fromOption(registryScope.findCounter(name)).map(Stat.counter))

    /** The histogram already registered at `name`, or `Absent`. Never registers.
      *
      * Same reasoning as `findCounter`: reading a distribution someone else produces, without minting a
      * second instrument at that path. `summary` on the result is the non-destructive read, so polling it
      * does not disturb an exporter reading the same histogram.
      */
    def findHistogram(name: String)(using Frame): Maybe[Histogram] < Sync =
        Sync.Unsafe.defer(Maybe.fromOption(registryScope.findHistogram(name)).map(Stat.histogram))

    /** The gauge already registered at `name`, or `Absent`. Never registers.
      *
      * The shadowing hazard is worst here: `initGauge` takes the caller's own thunk, so a reader that mints
      * instead of finding replaces what the producer publishes with whatever it happened to pass.
      */
    def findGauge(name: String)(using Frame): Maybe[Gauge] < Sync =
        Sync.Unsafe.defer(Maybe.fromOption(registryScope.findGauge(name)).map(Stat.gauge(_, Attributes.empty)))

    /** Initialize a new Histogram.
      * @param name
      *   The name of the histogram
      * @param description
      *   A description of the histogram (default is "empty")
      * @param boundaries
      *   The bucket boundaries, strictly ascending, shared by every series (default is OpenTelemetry's millisecond set)
      * @param unit
      *   The unit of the histogram (default is "1")
      * @return
      *   A new Histogram instance
      */
    def initHistogram(
        name: String,
        description: String = "empty",
        boundaries: Array[Double] = UnsafeHistogram.defaultBoundaries,
        unit: String = "1"
    ): Histogram =
        Stat.histogram(registryScope.histogram(name, description, boundaries, unit))

    /** Initialize a new Gauge.
      *
      * With `attributes`, `f` is registered as that series of the gauge at `name`, first-writer-wins per attribute
      * set, and the `Gauge` returned reads that series.
      *
      * @param name
      *   The name of the gauge
      * @param description
      *   A description of the gauge (default is "empty")
      * @param unit
      *   The unit of the gauge (default is "1")
      * @param attributes
      *   The attributes of the series `f` is (default is none)
      * @param f
      *   A function that returns the gauge's value
      * @return
      *   A new Gauge instance
      */
    def initGauge(
        name: String,
        description: String = "empty",
        unit: String = "1",
        attributes: Attributes = Attributes.empty
    )(f: => Double): Gauge =
        Stat.gauge(registryScope.gauge(name, description, unit, attributes)(f), attributes)

    /** Initialize a new CounterGauge.
      *
      * With `attributes`, `f` is registered as that series of the counter gauge at `name`, as for `initGauge`.
      *
      * @param name
      *   The name of the counter gauge
      * @param description
      *   A description of the counter gauge (default is "empty")
      * @param unit
      *   The unit of the counter gauge (default is "1")
      * @param attributes
      *   The attributes of the series `f` is (default is none)
      * @param f
      *   A function that returns the counter gauge's value
      * @return
      *   A new CounterGauge instance
      */
    def initCounterGauge(
        name: String,
        description: String = "empty",
        unit: String = "1",
        attributes: Attributes = Attributes.empty
    )(f: => Long): CounterGauge =
        val u = registryScope.counterGauge(name, description, unit, attributes)(f)
        new CounterGauge:
            val unsafe               = u
            def collect(using Frame) =
                if attributes.get.isEmpty then Sync.Unsafe.defer(unsafe.collect())
                else Sync.Unsafe.defer(unsafe.collect(attributes))
            def series(using Frame) = Sync.Unsafe.defer(unsafe.series())
        end new
    end initCounterGauge

    /** Trace a span of execution.
      * @param name
      *   The name of the span
      * @param attributes
      *   Additional attributes for the span (default is empty)
      * @param v
      *   The computation to trace
      * @return
      *   The result of the computation, wrapped in Sync
      */
    def traceSpan[A, S](
        name: String,
        attributes: Attributes = Attributes.empty
    )(v: => A < S)(using Frame): A < (Sync & S) =
        Stat.traceExporter.use(internal.TraceSpan.trace(_, registryScope.path, name, attributes)(v))
end Stat

object Stat:

    private def counter(u: UnsafeCounter): Counter =
        new Counter:
            val unsafe                                            = u
            def get(using Frame)                                  = Sync.Unsafe.defer(unsafe.get())
            def inc(using Frame)                                  = Sync.Unsafe.defer(unsafe.inc())
            def add(v: Long)(using Frame)                         = Sync.Unsafe.defer(unsafe.add(v))
            def add(v: Long, attributes: Attributes)(using Frame) = Sync.Unsafe.defer(unsafe.add(v, attributes))
            def series(using Frame)                               = Sync.Unsafe.defer(unsafe.series())

    private def histogram(u: UnsafeHistogram): Histogram =
        new Histogram:
            val unsafe                                                  = u
            def observe(v: Long)(using Frame)                           = Sync.Unsafe.defer(unsafe.observe(v))
            def observe(v: Double)(using Frame)                         = Sync.Unsafe.defer(unsafe.observe(v))
            def observe(v: Long, attributes: Attributes)(using Frame)   = Sync.Unsafe.defer(unsafe.observe(v, attributes))
            def observe(v: Double, attributes: Attributes)(using Frame) = Sync.Unsafe.defer(unsafe.observe(v, attributes))
            def summary(using Frame)                                    = Sync.Unsafe.defer(unsafe.summary())
            def series(using Frame)                                     = Sync.Unsafe.defer(unsafe.series())

    private def gauge(u: UnsafeGauge, attributes: Attributes): Gauge =
        new Gauge:
            val unsafe               = u
            def collect(using Frame) =
                if attributes.get.isEmpty then Sync.Unsafe.defer(unsafe.collect())
                else Sync.Unsafe.defer(unsafe.collect(attributes))
            def series(using Frame) = Sync.Unsafe.defer(unsafe.series())

    /** The single `TraceExporter` construction shared by the trace path and the eager class-init scan.
      *
      * Service-loader discovery constructs every classpath-present `ExporterFactory` (and, for a factory
      * that starts a background collector, activates it) exactly once. Both the `traceExporter` Local
      * default and `eagerExporterScan` read THIS lazy val, so the exporters are constructed once for the
      * object's lifetime: a second construction would start a second background export loop against the same
      * process-global registry, splitting every destructive counter/histogram read between two loops.
      * Discovery is the per-factory-isolated variant, so one throwing provider is skipped rather than
      * failing this class initializer.
      */
    // Unsafe: read at class-init and on first trace use, before any effect handler exists to supply
    // AllowUnsafe; TraceExporter.getIsolated is a plain value read with no Sync context available here.
    private[Stat] lazy val scannedExporter: TraceExporter =
        import AllowUnsafe.embrace.danger
        TraceExporter.getIsolated

    private[Stat] val traceExporter =
        Local.init[TraceExporter](scannedExporter)

    /** Listen to traces using a custom exporter.
      * @param exporter
      *   The TraceExporter to use
      * @param v
      *   The computation to trace
      * @return
      *   The result of the computation, wrapped in Sync
      */
    def traceListen[A, S](exporter: TraceExporter)(v: A < S)(using Frame): A < (Sync & S) =
        traceExporter.use { curr =>
            traceExporter.let(TraceExporter.all(List(curr, exporter)))(v)
        }

    private[kyo] val kyoScope = initScope("kyo")

    /** How many times `activate` has been called, for tests that need to prove a call site is wired.
      *
      * Activation itself is idempotent (this object initializes once), so the flag a test would otherwise
      * read says nothing about WHICH code path reached it. The count does, and it is order-independent: a
      * test records it, exercises the path, and asserts it moved.
      */
    // Unsafe: a plain process-global counter incremented from `activate`, which runs before any effect
    // handler exists to supply AllowUnsafe.
    private val activations =
        import AllowUnsafe.embrace.danger
        AtomicInt.Unsafe.init(0)
    end activations

    private[kyo] def activationCount: Int =
        import AllowUnsafe.embrace.danger
        activations.get()
    end activationCount

    /** Forces service-loader discovery of every classpath-present `ExporterFactory` at class
      * initialization, so a metrics-only app that never opens a trace still constructs each factory.
      * A factory whose construction starts a background collector (host-metrics sampling) activates
      * on classpath presence alone. Forcing `scannedExporter` (the one shared construction) here means
      * the factories are constructed exactly once, whether the app traces or not. Must remain the last
      * `val` in this object: a discovered factory constructor runs inside this class initializer and must
      * not observe a later, not-yet-initialized field.
      */
    private[kyo] val eagerExporterScan: Unit =
        val _ = scannedExporter
        ()
    end eagerExporterScan

    /** Initialize a new Stat instance with a custom scope.
      * @param first
      *   The first element of the scope path
      * @param rest
      *   The remaining elements of the scope path
      * @return
      *   A new Stat instance with the specified scope
      */
    def initScope(first: String, rest: String*): Stat =
        new Stat(StatsRegistry.scope(first).scope(rest*))

    /** Runs classpath-presence activation for the stats service providers, once per process.
      *
      * A provider on the classpath (kyo-stats-otlp's exporter, kyo-stats-machine's host sampler) activates
      * when this object initializes, and this method is the way to make that happen at a chosen moment
      * instead of waiting for the first metric to be touched. `kyo.KyoApp` calls it before running the
      * application's own code, so an ordinary kyo application never calls it itself.
      *
      * Call it once at startup from a host that is not a `KyoApp`: a library embedded in an application that
      * is not kyo's, or an entrypoint of your own. Nothing else in kyo-core activates. The only hook that
      * would cover every process is the fiber-creation path, and a stats concern does not belong in the
      * scheduler's hot path. Repeated calls do nothing.
      */
    def activate(): Unit =
        import AllowUnsafe.embrace.danger
        val _ = eagerExporterScan
        discard(activations.incrementAndGet())
    end activate

end Stat
