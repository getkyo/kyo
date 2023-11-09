package kyo.stats.otel

import io.opentelemetry.api.trace.{Span => OSpan}
import io.opentelemetry.context.Context
import io.opentelemetry.api._
import io.opentelemetry.api.metrics._
import kyo._
import kyo.ios.IOs
import kyo.stats._
import kyo.stats.internal.Span
import kyo.stats.internal.MetricReceiver
import kyo.stats.Attributes
import kyo.stats.internal.TraceReceiver

class OTelMetricReceiver extends MetricReceiver with TraceReceiver {

  private val otel = GlobalOpenTelemetry.get()

  def counter(scope: List[String], name: String, description: String, unit: String, a: Attributes) =
    new Counter {

      val impl =
        otel.getMeter(scope.mkString("_"))
          .counterBuilder(name)
          .setDescription(description)
          .setUnit(unit)
          .build()

      def add(v: Long, a: Attributes) =
        IOs(impl.add(v, OTelAttributes(a)))

      def add(v: Long) =
        IOs(impl.add(v))

      def attributes(b: Attributes) =
        counter(scope, name, description, unit, a.add(b))
    }

  def histogram(
      scope: List[String],
      name: String,
      description: String,
      unit: String,
      a: Attributes
  ) =
    new Histogram {

      val impl =
        otel.getMeter(scope.mkString("_"))
          .histogramBuilder(name)
          .setDescription(description)
          .setUnit(unit)
          .build()

      def observe(v: Double, b: Attributes) =
        IOs(impl.record(v, OTelAttributes(b)))

      def observe(v: Double): Unit > IOs =
        IOs(impl.record(v))

      def attributes(b: Attributes) =
        histogram(scope, name, description, unit, a.add(b))
    }

  def gauge(
      scope: List[String],
      name: String,
      description: String,
      unit: String,
      a: Attributes
  )(f: => Double) =
    new Gauge {

      val impl =
        otel.getMeter(scope.mkString("_"))
          .gaugeBuilder(name)
          .setDescription(description)
          .setUnit(unit)
          .buildWithCallback(m => m.record(f))

      def close =
        IOs(impl.close())
    }

  def startSpan(
      scope: List[String],
      name: String,
      parent: Option[Span],
      attributes: Attributes
  ): Span > IOs =
    IOs {
      val b =
        otel.getTracer(scope.mkString("_"))
          .spanBuilder(name)
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
