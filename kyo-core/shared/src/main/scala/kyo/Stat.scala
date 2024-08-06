package kyo

import kyo.stats.*
import kyo.stats.internal.*
import kyo.stats.internal.TraceReceiver

abstract class Counter:
    val unsafe: UnsafeCounter
    def get(using Frame): Long < IO
    def inc(using Frame): Unit < IO
    def add(v: Long)(using Frame): Unit < IO
end Counter

abstract class Histogram:
    val unsafe: UnsafeHistogram
    def observe(v: Long)(using Frame): Unit < IO
    def observe(v: Double)(using Frame): Unit < IO
    def count(using Frame): Long < IO
    def valueAtPercentile(v: Double)(using Frame): Double < IO
end Histogram

abstract class Gauge:
    val unsafe: UnsafeGauge
    def collect(using Frame): Double < IO

abstract class CounterGauge:
    val unsafe: UnsafeCounterGauge
    def collect(using Frame): Long < IO

class Stat(private val registryScope: StatsRegistry.Scope) extends AnyVal:

    def scope(path: String*): Stat =
        new Stat(registryScope.scope(path*))

    def initCounter(
        name: String,
        description: String = "empty"
    ): Counter =
        new Counter:
            val unsafe                    = registryScope.counter(name, description)
            def get(using Frame)          = IO(unsafe.get())
            def inc(using Frame)          = IO(unsafe.inc())
            def add(v: Long)(using Frame) = IO(unsafe.add(v))

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

    def initGauge(
        name: String,
        description: String = "empty"
    )(f: => Double): Gauge =
        new Gauge:
            val unsafe               = registryScope.gauge(name, description)(f)
            def collect(using Frame) = IO(unsafe.collect())

    def initCounterGauge(
        name: String,
        description: String = "empty"
    )(f: => Long): CounterGauge =
        new CounterGauge:
            val unsafe               = registryScope.counterGauge(name, description)(f)
            def collect(using Frame) = IO(f)

    def traceSpan[T, S](
        name: String,
        attributes: Attributes = Attributes.empty
    )(v: => T < S)(using Frame): T < (IO & S) =
        Stat.traceReceiver.use(internal.Span.trace(_, registryScope.path, name, attributes)(v))
end Stat

object Stat:

    private[Stat] val traceReceiver = Local.init[TraceReceiver](TraceReceiver.get)

    def traceListen[T, S](receiver: TraceReceiver)(v: T < S)(using Frame): T < (IO & S) =
        traceReceiver.use { curr =>
            traceReceiver.let(TraceReceiver.all(List(curr, receiver)))(v)
        }

    private[kyo] val kyoScope = initScope("kyo")

    def initScope(first: String, rest: String*): Stat =
        new Stat(StatsRegistry.scope(first).scope(rest*))

end Stat
