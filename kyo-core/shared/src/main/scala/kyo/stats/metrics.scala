package kyo.stats

import kyo._
import kyo.choices.Choices
import kyo.ios._
import kyo.stats.attributes._

object metrics {

  object Metrics {
    def initCounter(
        name: String,
        description: String = "",
        unit: String = "",
        a: Attributes = Attributes.empty
    ): Counter =
      MetricReceiver.get.counter(name, description, unit, a)

    def initHistogram(
        name: String,
        description: String = "",
        unit: String = "",
        a: Attributes = Attributes.empty
    ): Histogram =
      MetricReceiver.get.histogram(name, description, unit, a)
  }

  trait Counter {
    def inc: Unit > IOs = add(1)
    def add(v: Long): Unit > IOs
    def add(v: Long, b: Attributes): Unit > IOs
    def attributes(b: Attributes): Counter
  }

  object Counter {
    val noop: Counter =
      new Counter {
        def add(v: Long)                = ()
        def add(v: Long, b: Attributes) = ()
        def attributes(b: Attributes)   = this
      }
    def all(l: List[Counter]): Counter =
      new Counter {
        def add(v: Long)                = Choices.foreach(l)(_.add(v))
        def add(v: Long, b: Attributes) = Choices.foreach(l)(_.add(v, b))
        def attributes(b: Attributes)   = all(l.map(_.attributes(b)))
      }
  }

  trait Histogram {
    def observe(v: Double): Unit > IOs
    def observe(v: Double, b: Attributes): Unit > IOs
    def attributes(b: Attributes): Histogram
  }

  object Histogram {
    val noop: Histogram =
      new Histogram {
        def observe(v: Double)                = ()
        def observe(v: Double, b: Attributes) = ()
        def attributes(b: Attributes)         = this
      }
    def all(l: List[Histogram]): Histogram =
      new Histogram {
        def observe(v: Double)                = Choices.foreach(l)(_.observe(v))
        def observe(v: Double, b: Attributes) = Choices.foreach(l)(_.observe(v, b))
        def attributes(b: Attributes)         = all(l.map(_.attributes(b)))
      }
  }
}
