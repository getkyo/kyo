package kyo

import kyo._
import kyo.ios._
import io.opentelemetry.api._
import io.opentelemetry.context._
import io.opentelemetry.api.trace._
import kyo.attributes._
import kyo.locals.Locals

object traces {

  private val tracer = GlobalOpenTelemetry.get().getTracer("kyo")

  object Traces {

    private val local = Locals.init[Option[Context]](None)

    def span[T, S](
        name: String,
        attributes: Attributes = Attributes.empty
    )(v: => T > S): T > (IOs with S) =
      local.get.map { parent =>
        val p = parent.getOrElse(Context.current())
        val span =
          tracer.spanBuilder(name)
            .setAllAttributes(attributes.o)
            .setParent(p)
            .startSpan()
        IOs.ensure(span.end()) {
          local.let(Some(span.storeInContext(p)))(v)
        }
      }

    def event(name: String, attributes: Attributes = Attributes.empty): Unit > IOs =
      local.get.map {
        case None =>
          span(name, attributes)(())
        case Some(ctx) =>
          IOs {
            Span.fromContext(ctx).addEvent(name, attributes.o)
            ()
          }
      }

  }
}
