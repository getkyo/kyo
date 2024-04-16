package kyo.stats.otel

import io.opentelemetry.api.*
import io.opentelemetry.api.metrics.*
import io.opentelemetry.api.trace.Span as OSpan
import io.opentelemetry.context.Context
import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import kyo.stats.internal.MetricReceiver
import kyo.stats.internal.Span
import kyo.stats.internal.TraceReceiver
import kyo.stats.internal.UnsafeCounter
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram

class OTelReceiver extends MetricReceiver with TraceReceiver:

    private val otel = GlobalOpenTelemetry.get()

    def counter(
        scope: List[String],
        name: String,
        description: String,
        unit: String,
        a: Attributes
    ) =
        new UnsafeCounter:

            val impl =
                otel.getMeter(scope.mkString("_"))
                    .counterBuilder(name)
                    .setDescription(description)
                    .setUnit(unit)
                    .build()

            def inc() = add(1)

            def add(v: Long, a: Attributes) =
                impl.add(v, OTelAttributes(a))

            def add(v: Long) =
                impl.add(v)

            def attributes(b: Attributes) =
                counter(scope, name, description, unit, a.add(b))

    def histogram(
        scope: List[String],
        name: String,
        description: String,
        unit: String,
        a: Attributes
    ) =
        new UnsafeHistogram:

            val impl =
                otel.getMeter(scope.mkString("_"))
                    .histogramBuilder(name)
                    .setDescription(description)
                    .setUnit(unit)
                    .build()

            def observe(v: Double, b: Attributes) =
                impl.record(v, OTelAttributes(b))

            def observe(v: Double) =
                impl.record(v)

            def attributes(b: Attributes) =
                histogram(scope, name, description, unit, a.add(b))

    def gauge(
        scope: List[String],
        name: String,
        description: String,
        unit: String,
        a: Attributes
    )(f: => Double) =
        new UnsafeGauge:

            val impl =
                otel.getMeter(scope.mkString("_"))
                    .gaugeBuilder(name)
                    .setDescription(description)
                    .setUnit(unit)
                    .buildWithCallback(m => m.record(f))

            def close() =
                impl.close()

    def startSpan(
        scope: List[String],
        name: String,
        parent: Option[Span],
        attributes: Attributes
    ): Span < IOs =
        IOs {
            val b =
                otel.getTracer(scope.mkString("_"))
                    .spanBuilder(name)
                    .setAllAttributes(OTelAttributes(attributes))
            parent.collect {
                case Span(SpanImpl(c)) =>
                    b.setParent(c)
            }
            Span(SpanImpl(b.startSpan().storeInContext(Context.current())))
        }

    private case class SpanImpl(c: Context) extends Span.Unsafe:

        def end(): Unit =
            OSpan.fromContext(c).end()

        def event(name: String, a: Attributes) =
            discard(OSpan.fromContext(c).addEvent(name, OTelAttributes(a)))
    end SpanImpl
end OTelReceiver
