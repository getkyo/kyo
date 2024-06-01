package kyo

import kyo.internal.Trace
import kyo.stats.*
import kyo.stats.internal.*
import kyo.stats.internal.TraceReceiver

abstract class Counter:
    val unsafe: UnsafeCounter
    def get(using Trace): Long < IOs
    def inc(using Trace): Unit < IOs
    def add(v: Long)(using Trace): Unit < IOs
end Counter

abstract class Histogram:
    val unsafe: UnsafeHistogram
    def observe(v: Long)(using Trace): Unit < IOs
    def observe(v: Double)(using Trace): Unit < IOs
    def count(using Trace): Long < IOs
    def valueAtPercentile(v: Double)(using Trace): Double < IOs
end Histogram

abstract class Gauge:
    val unsafe: UnsafeGauge
    def collect(using Trace): Double < IOs

abstract class CounterGauge:
    val unsafe: UnsafeCounterGauge
    def collect(using Trace): Long < IOs

class Stats(private val registryScope: StatsRegistry.Scope) extends AnyVal:

    def scope(path: String*): Stats =
        new Stats(registryScope.scope(path*))

    def initCounter(
        name: String,
        description: String = "empty"
    ): Counter =
        new Counter:
            val unsafe                    = registryScope.counter(name, description)
            def get(using Trace)          = IOs(unsafe.get())
            def inc(using Trace)          = IOs(unsafe.inc())
            def add(v: Long)(using Trace) = IOs(unsafe.add(v))

    def initHistogram(
        name: String,
        description: String = "empty"
    ): Histogram =
        new Histogram:
            val unsafe                                    = registryScope.histogram(name, description)
            def observe(v: Double)(using Trace)           = IOs(unsafe.observe(v))
            def observe(v: Long)(using Trace)             = IOs(unsafe.observe(v))
            def count(using Trace)                        = IOs(unsafe.count())
            def valueAtPercentile(v: Double)(using Trace) = IOs(unsafe.valueAtPercentile(v))

    def initGauge(
        name: String,
        description: String = "empty"
    )(f: => Double): Gauge =
        new Gauge:
            val unsafe               = registryScope.gauge(name, description)(f)
            def collect(using Trace) = IOs(unsafe.collect())

    def initCounterGauge(
        name: String,
        description: String = "empty"
    )(f: => Long): CounterGauge =
        new CounterGauge:
            val unsafe               = registryScope.counterGauge(name, description)(f)
            def collect(using Trace) = IOs(f)

    def traceSpan[T, S](
        name: String,
        attributes: Attributes = Attributes.empty
    )(v: => T < S)(using Trace): T < (IOs & S) =
        Stats.traceReceiver.use(internal.Span.trace(_, registryScope.path, name, attributes)(v))
end Stats

object Stats:

    private[Stats] val traceReceiver = Locals.init[TraceReceiver](TraceReceiver.get)

    def traceListen[T, S](receiver: TraceReceiver)(v: T < S)(using Trace): T < (IOs & S) =
        traceReceiver.use { curr =>
            traceReceiver.let(TraceReceiver.all(List(curr, receiver)))(v)
        }

    private[kyo] val kyoScope = initScope("kyo")

    def initScope(first: String, rest: String*): Stats =
        new Stats(StatsRegistry.scope(first).scope(rest*))

end Stats
