package kyo.stats.otel

import io.opentelemetry.api._
import io.opentelemetry.api.metrics._
import kyo._
import kyo.ios.IOs
import kyo.stats.MetricReceiver
import kyo.stats.attributes.Attributes
import kyo.stats.metrics._

class OTelMetricReceiver extends MetricReceiver {

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

}
