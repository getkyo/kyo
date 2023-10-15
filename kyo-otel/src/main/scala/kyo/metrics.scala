package kyo

import kyo._
import kyo.ios._
import io.opentelemetry.api._
import io.opentelemetry.api.metrics._
import kyo.attributes._

object metrics {

  private val meter = GlobalOpenTelemetry.get().getMeter("kyo")

  class Counter private[metrics] (c: LongCounter, a: Attributes) {

    def inc: Unit > IOs =
      add(1)

    def add(v: Long): Unit > IOs =
      IOs(c.add(v, a.o))

    def attributes(b: Attributes): Counter =
      new Counter(c, a.add(b))
  }

  class Histogram private[metrics] (c: DoubleHistogram, a: Attributes) {

    def observe(v: Double) =
      c.record(v, a.o)

    def attributes(b: Attributes): Histogram =
      new Histogram(c, a.add(b))
  }

  object Metrics {

    def initCounter(name: String, description: String = "", unit: String = ""): Counter =
      new Counter(
          meter.counterBuilder(name)
            .setDescription(description)
            .setUnit(unit)
            .build(),
          Attributes.empty
      )

    def initHistogram(name: String, description: String = "", unit: String = ""): Histogram =
      new Histogram(
          meter.histogramBuilder(name)
            .setDescription(description)
            .setUnit(unit)
            .build(),
          Attributes.empty
      )
  }

}
