package kyo

import kyo.stats.*
import kyo.stats.internal.*
import kyo.stats.internal.TraceReceiver

abstract class Counter:
    val unsafe: UnsafeCounter
    def get: Long < IOs
    def inc: Unit < IOs
    def add(v: Long): Unit < IOs
end Counter

abstract class Histogram:
    val unsafe: UnsafeHistogram
    def observe(v: Long): Unit < IOs
    def observe(v: Double): Unit < IOs
    def count: Long < IOs
    def valueAtPercentile(v: Double): Double < IOs
end Histogram

abstract class Gauge:
    val unsafe: UnsafeGauge
    def collect: Double < IOs

abstract class CounterGauge:
    val unsafe: UnsafeCounterGauge
    def collect: Long < IOs

class Stats(private val registryScope: StatsRegistry.Scope) extends AnyVal:

    def scope(path: String*): Stats =
        new Stats(registryScope.scope(path*))

    def initCounter(
        name: String,
        description: String = "empty"
    ): Counter =
        new Counter:
            val unsafe       = registryScope.counter(name, description)
            val get          = IOs(unsafe.get())
            val inc          = IOs(unsafe.inc())
            def add(v: Long) = IOs(unsafe.add(v))

    def initHistogram(
        name: String,
        description: String = "empty"
    ): Histogram =
        new Histogram:
            val unsafe                       = registryScope.histogram(name, description)
            def observe(v: Double)           = IOs(unsafe.observe(v))
            def observe(v: Long)             = IOs(unsafe.observe(v))
            val count                        = IOs(unsafe.count())
            def valueAtPercentile(v: Double) = IOs(unsafe.valueAtPercentile(v))

    def initGauge(
        name: String,
        description: String = "empty"
    )(f: => Double): Gauge =
        new Gauge:
            val unsafe  = registryScope.gauge(name, description)(f)
            val collect = IOs(unsafe.collect())

    def initCounterGauge(
        name: String,
        description: String = "empty"
    )(f: => Long): CounterGauge =
        new CounterGauge:
            val unsafe  = registryScope.counterGauge(name, description)(f)
            val collect = IOs(f)

    def traceSpan[T, S](
        name: String,
        attributes: Attributes = Attributes.empty
    )(v: => T < S): T < (IOs & S) =
        Stats.traceReceiver.use(internal.Span.trace(_, registryScope.path, name, attributes)(v))
end Stats

object Stats:

    private[Stats] val traceReceiver = Locals.init[TraceReceiver](TraceReceiver.get)

    def traceListen[T, S](receiver: TraceReceiver)(v: T < S): T < (IOs & S) =
        traceReceiver.use { curr =>
            traceReceiver.let(TraceReceiver.all(List(curr, receiver)))(v)
        }

    private[kyo] val kyoScope = initScope("kyo")

    def initScope(first: String, rest: String*): Stats =
        new Stats(StatsRegistry.scope(first).scope(rest*))

end Stats
