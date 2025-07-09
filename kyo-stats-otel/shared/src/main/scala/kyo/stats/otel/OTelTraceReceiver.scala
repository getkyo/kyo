package kyo.stats.otel

import io.opentelemetry.api.*
import io.opentelemetry.api.trace.Span as OSpan
import io.opentelemetry.context.Context
import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import kyo.stats.internal.TraceReceiver
import kyo.stats.internal.TraceSpan

class OTelTraceReceiver extends TraceReceiver {

    private val otel = GlobalOpenTelemetry.get()

    def startSpan(
        scope: List[String],
        name: String,
        parent: Maybe[TraceSpan],
        attributes: Attributes
    )(implicit frame: Frame): TraceSpan < Sync =
        Sync.defer {
            val b =
                otel.getTracer(scope.mkString("_"))
                    .spanBuilder(name)
                    .setAllAttributes(OTelAttributes(attributes))
            discard {
                parent.collect {
                    case TraceSpan(SpanImpl(c)) =>
                        b.setParent(c)
                }
            }
            TraceSpan(SpanImpl(b.startSpan().storeInContext(Context.current())))
        }

    private case class SpanImpl(c: Context) extends TraceSpan.Unsafe {

        def end(): Unit =
            OSpan.fromContext(c).end()

        def event(name: String, a: Attributes) =
            discard(OSpan.fromContext(c).addEvent(name, OTelAttributes(a)))
    }
}
