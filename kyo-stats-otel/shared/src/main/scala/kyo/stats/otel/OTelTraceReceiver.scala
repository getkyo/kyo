package kyo.stats.otel

import io.opentelemetry.api.*
import io.opentelemetry.api.trace.Span as OSpan
import io.opentelemetry.context.Context
import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import kyo.stats.internal.Span
import kyo.stats.internal.TraceReceiver

class OTelTraceReceiver extends TraceReceiver {

    private val otel = GlobalOpenTelemetry.get()

    def startSpan(
        scope: List[String],
        name: String,
        parent: Maybe[Span],
        attributes: Attributes
    )(implicit frame: Frame): Span < Sync =
        Sync {
            val b =
                otel.getTracer(scope.mkString("_"))
                    .spanBuilder(name)
                    .setAllAttributes(OTelAttributes(attributes))
            discard {
                parent.collect {
                    case Span(SpanImpl(c)) =>
                        b.setParent(c)
                }
            }
            Span(SpanImpl(b.startSpan().storeInContext(Context.current())))
        }

    private case class SpanImpl(c: Context) extends Span.Unsafe {

        def end(): Unit =
            OSpan.fromContext(c).end()

        def event(name: String, a: Attributes) =
            discard(OSpan.fromContext(c).addEvent(name, OTelAttributes(a)))
    }
}
