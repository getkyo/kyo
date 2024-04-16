package kyo

import kyo.stats.*
import kyo.stats.internal.TraceReceiver

abstract class Stats:

    def scope(path: String*): Stats

    def initCounter(
        name: String,
        description: String = "empty",
        unit: String = "",
        attributes: Attributes = Attributes.empty
    ): Counter

    def initHistogram(
        name: String,
        description: String = "empty",
        unit: String = "",
        attributes: Attributes = Attributes.empty
    ): Histogram

    def initGauge(
        name: String,
        description: String = "empty",
        unit: String = "",
        attributes: Attributes = Attributes.empty
    )(f: => Double): Gauge

    def traceSpan[T, S](
        name: String,
        attributes: Attributes = Attributes.empty
    )(v: => T < S): T < (IOs & S)
end Stats

object Stats:
    val noop: Stats =
        new Stats:

            def scope(path: String*) = this

            def initCounter(
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                Counter.noop

            def initHistogram(
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                Histogram.noop

            def initGauge(
                name: String,
                description: String = "",
                unit: String = "",
                a: Attributes = Attributes.empty
            )(f: => Double) =
                Gauge.noop

            def traceSpan[T, S](
                name: String,
                attributes: Attributes = Attributes.empty
            )(v: => T < S): T < (IOs & S) = v

    private val traceReceiver = Locals.init[TraceReceiver](TraceReceiver.get)

    def traceListen[T, S](receiver: TraceReceiver)(v: T < S): T < (IOs & S) =
        traceReceiver.use { curr =>
            traceReceiver.let(TraceReceiver.all(List(curr, receiver)))(v)
        }

    private[kyo] val kyoScope = initScope("kyo")

    def initScope(first: String, rest: String*): Stats =
        scope(first :: rest.toList)

    private def scope(path: List[String]): Stats =
        new Stats:
            def scope(path2: String*) =
                Stats.scope(path2.toList ::: path)

            def initCounter(
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                Counter(internal.MetricReceiver.get.counter(path.reverse, name, description, unit, a))

            def initHistogram(
                name: String,
                description: String,
                unit: String,
                a: Attributes
            ) =
                Histogram(internal.MetricReceiver.get.histogram(path.reverse, name, description, unit, a))

            def initGauge(
                name: String,
                description: String = "",
                unit: String = "",
                a: Attributes = Attributes.empty
            )(f: => Double) =
                Gauge(internal.MetricReceiver.get.gauge(path.reverse, name, description, unit, a)(f))

            def traceSpan[T, S](
                name: String,
                attributes: Attributes = Attributes.empty
            )(v: => T < S): T < (IOs & S) =
                traceReceiver.use(internal.Span.trace(_, path.reverse, name, attributes)(v))

            override def toString = s"Stats(scope = ${path.reverse})"
end Stats
