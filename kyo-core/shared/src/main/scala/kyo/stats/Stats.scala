package kyo.stats

import kyo._
import kyo.ios._

trait Stats {

  def scope(name: String): Stats

  def initCounter(
      name: String,
      description: String = "",
      unit: String = "",
      a: Attributes = Attributes.empty
  ): Counter

  def initHistogram(
      name: String,
      description: String = "",
      unit: String = "",
      a: Attributes = Attributes.empty
  ): Histogram

  def initGauge(
      name: String,
      description: String = "",
      unit: String = "",
      a: Attributes = Attributes.empty
  )(f: => Double): Gauge

  def traceSpan[T, S](
      name: String,
      attributes: Attributes = Attributes.empty
  )(v: => T > S): T > (IOs with S)
}

object Stats {
  val noop: Stats =
    new Stats {

      def scope(name: String) = this

      def initCounter(
          name: String,
          description: String,
          unit: String,
          a: Attributes
      ) =
        Counter.noop

      def initHistogram(
          name: String,
          description: String,
          unit: String,
          a: Attributes
      ) =
        Histogram.noop

      def initGauge(
          name: String,
          description: String = "",
          unit: String = "",
          a: Attributes = Attributes.empty
      )(f: => Double) =
        Gauge.noop

      def traceSpan[T, S](
          name: String,
          attributes: Attributes
      )(v: => T > S): T > (IOs with S) = v
    }

  def scope(name: String): Stats =
    scope(name :: Nil)

  private def scope(path: List[String]): Stats =
    new Stats {
      def scope(name: String) =
        Stats.scope(name :: path)

      def initCounter(
          name: String,
          description: String,
          unit: String,
          a: Attributes
      ) =
        internal.Receiver.get.counter(path.reverse, name, description, unit, a)

      def initHistogram(
          name: String,
          description: String,
          unit: String,
          a: Attributes
      ) =
        internal.Receiver.get.histogram(path.reverse, name, description, unit, a)

      def initGauge(
          name: String,
          description: String = "",
          unit: String = "",
          a: Attributes = Attributes.empty
      )(f: => Double) =
        internal.Receiver.get.gauge(path.reverse, name, description, unit, a)(f)

      def traceSpan[T, S](
          name: String,
          attributes: Attributes
      )(v: => T > S): T > (IOs with S) =
        internal.Span.trace(path.reverse, name, attributes)(v)

      override def toString = s"Stats(scope = ${path.reverse})"
    }
}
