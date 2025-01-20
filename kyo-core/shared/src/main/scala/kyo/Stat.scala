package kyo

import kyo.stats.*
import kyo.stats.internal.*
import kyo.stats.internal.TraceReceiver

/** A counter for tracking numeric values that only increase.
  */
abstract class Counter:
    /** The underlying unsafe counter implementation. */
    val unsafe: UnsafeCounter

    /** Get the current value of the counter.
      * @return
      *   The current count as a Long, wrapped in IO
      */
    def get(using Frame): Long < IO

    /** Increment the counter by 1.
      */
    def inc(using Frame): Unit < IO

    /** Add a specific value to the counter.
      * @param v
      *   The value to add
      */
    def add(v: Long)(using Frame): Unit < IO
end Counter

/** A histogram for observing the distribution of values.
  */
abstract class Histogram:
    /** The underlying unsafe histogram implementation. */
    val unsafe: UnsafeHistogram

    /** Record an observation of a long value.
      * @param v
      *   The value to observe
      */
    def observe(v: Long)(using Frame): Unit < IO

    /** Record an observation of a double value.
      * @param v
      *   The value to observe
      */
    def observe(v: Double)(using Frame): Unit < IO

    /** Get the total count of observations.
      * @return
      *   The count as a Long, wrapped in IO
      */
    def count(using Frame): Long < IO

    /** Get the value at a specific percentile.
      * @param v
      *   The percentile (0.0 to 100.0)
      * @return
      *   The value at the given percentile, wrapped in IO
      */
    def valueAtPercentile(v: Double)(using Frame): Double < IO
end Histogram

/** A gauge for measuring a specific value that can go up and down.
  */
abstract class Gauge:
    /** The underlying unsafe gauge implementation. */
    val unsafe: UnsafeGauge

    /** Collect the current value of the gauge.
      * @return
      *   The current value as a Double, wrapped in IO
      */
    def collect(using Frame): Double < IO
end Gauge

/** A gauge that specifically measures counter-like values.
  */
abstract class CounterGauge:
    /** The underlying unsafe counter gauge implementation. */
    val unsafe: UnsafeCounterGauge

    /** Collect the current value of the counter gauge.
      * @return
      *   The current value as a Long, wrapped in IO
      */
    def collect(using Frame): Long < IO
end CounterGauge

final class Stat(private val registryScope: StatsRegistry.Scope):

    /** Create a new Stat instance with an additional scope.
      * @param path
      *   The path elements to add to the scope
      * @return
      *   A new Stat instance with the extended scope
      */
    def scope(path: String*): Stat =
        new Stat(registryScope.scope(path*))

    /** Initialize a new Counter.
      * @param name
      *   The name of the counter
      * @param description
      *   A description of the counter (default is "empty")
      * @return
      *   A new Counter instance
      */
    def initCounter(
        name: String,
        description: String = "empty"
    ): Counter =
        new Counter:
            val unsafe                    = registryScope.counter(name, description)
            def get(using Frame)          = IO(unsafe.get())
            def inc(using Frame)          = IO(unsafe.inc())
            def add(v: Long)(using Frame) = IO(unsafe.add(v))

    /** Initialize a new Histogram.
      * @param name
      *   The name of the histogram
      * @param description
      *   A description of the histogram (default is "empty")
      * @return
      *   A new Histogram instance
      */
    def initHistogram(
        name: String,
        description: String = "empty"
    ): Histogram =
        new Histogram:
            val unsafe                                    = registryScope.histogram(name, description)
            def observe(v: Double)(using Frame)           = IO(unsafe.observe(v))
            def observe(v: Long)(using Frame)             = IO(unsafe.observe(v))
            def count(using Frame)                        = IO(unsafe.count())
            def valueAtPercentile(v: Double)(using Frame) = IO(unsafe.valueAtPercentile(v))

    /** Initialize a new Gauge.
      * @param name
      *   The name of the gauge
      * @param description
      *   A description of the gauge (default is "empty")
      * @param f
      *   A function that returns the gauge's value
      * @return
      *   A new Gauge instance
      */
    def initGauge(
        name: String,
        description: String = "empty"
    )(f: => Double): Gauge =
        new Gauge:
            val unsafe               = registryScope.gauge(name, description)(f)
            def collect(using Frame) = IO(unsafe.collect())

    /** Initialize a new CounterGauge.
      * @param name
      *   The name of the counter gauge
      * @param description
      *   A description of the counter gauge (default is "empty")
      * @param f
      *   A function that returns the counter gauge's value
      * @return
      *   A new CounterGauge instance
      */
    def initCounterGauge(
        name: String,
        description: String = "empty"
    )(f: => Long): CounterGauge =
        new CounterGauge:
            val unsafe               = registryScope.counterGauge(name, description)(f)
            def collect(using Frame) = IO(f)

    /** Trace a span of execution.
      * @param name
      *   The name of the span
      * @param attributes
      *   Additional attributes for the span (default is empty)
      * @param v
      *   The computation to trace
      * @return
      *   The result of the computation, wrapped in IO
      */
    def traceSpan[A, S](
        name: String,
        attributes: Attributes = Attributes.empty
    )(v: => A < S)(using Frame): A < (IO & S) =
        Stat.traceReceiver.use(internal.Span.trace(_, registryScope.path, name, attributes)(v))
end Stat

object Stat:

    private[Stat] val traceReceiver = Local.init[TraceReceiver](TraceReceiver.get)

    /** Listen to traces using a custom receiver.
      * @param receiver
      *   The TraceReceiver to use
      * @param v
      *   The computation to trace
      * @return
      *   The result of the computation, wrapped in IO
      */
    def traceListen[A, S](receiver: TraceReceiver)(v: A < S)(using Frame): A < (IO & S) =
        traceReceiver.use { curr =>
            traceReceiver.let(TraceReceiver.all(List(curr, receiver)))(v)
        }

    private[kyo] val kyoScope = initScope("kyo")

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

end Stat
