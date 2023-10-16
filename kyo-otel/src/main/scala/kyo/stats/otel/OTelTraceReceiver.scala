package kyo.stats.otel

import io.opentelemetry.api._
import io.opentelemetry.api.metrics._
import io.opentelemetry.api.trace.{Span => OSpan}
import io.opentelemetry.context.Context
import kyo._
import kyo.ios.IOs
import kyo.stats.TraceReceiver
import kyo.stats.attributes.Attributes
import kyo.stats.metrics._
import kyo.stats.traces.Span

class OTelTraceReceiver extends TraceReceiver {

  private val tracer = GlobalOpenTelemetry.get().getTracer("kyo")

  def span[T, S](name: String, parent: Option[Span], attributes: Attributes) =
    IOs {
      val b =
        tracer.spanBuilder(name)
          .setAllAttributes(OTelAttributes(attributes))
      parent.collect {
        case SpanImpl(c) =>
          b.setParent(c)
      }
      SpanImpl(b.startSpan().storeInContext(Context.current()))
    }

  private case class SpanImpl(c: Context) extends Span {

    def end: Unit > IOs =
      IOs(OSpan.fromContext(c).end())

    def event(name: String, a: Attributes) =
      IOs {
        OSpan.fromContext(c).addEvent(name, OTelAttributes(a))
        ()
      }
  }
}
