package kyo.stats.otel

import io.opentelemetry.api._
import io.opentelemetry.api.metrics._
import kyo._
import kyo.ios.IOs
import kyo.stats.MetricReceiver
import kyo.stats.attributes.Attributes
import kyo.stats.metrics._

class OTelMetricReceiver extends MetricReceiver {

  private val meter = GlobalOpenTelemetry.get().getMeter("kyo")

  def counter(name: String, description: String, unit: String, a: Attributes) =
    new Counter {

      val impl = meter.counterBuilder(name).setDescription(description).setUnit(unit).build()

      def add(v: Long, a: Attributes) =
        IOs(impl.add(v, OTelAttributes(a)))

      def add(v: Long) =
        IOs(impl.add(v))

      def attributes(b: Attributes) =
        counter(name, description, unit, a.add(b))
    }

  def histogram(name: String, description: String, unit: String, a: Attributes) =
    new Histogram {

      val impl = meter.histogramBuilder(name).setDescription(description).setUnit(unit).build()

      def observe(v: Double, b: Attributes) =
        IOs(impl.record(v, OTelAttributes(b)))

      def observe(v: Double): Unit > IOs =
        IOs(impl.record(v))

      def attributes(b: Attributes) =
        histogram(name, description, unit, a.add(b))
    }

}
