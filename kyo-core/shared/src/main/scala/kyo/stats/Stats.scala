package kyo.stats

import kyo._
import kyo.ios._
import kyo.stats.attributes._
import kyo.stats.metrics._
import kyo.stats.traces._

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

  def span[T, S](
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
      ) = Counter.noop

      def initHistogram(
          name: String,
          description: String,
          unit: String,
          a: Attributes
      ) = Histogram.noop

      def span[T, S](
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
        Metrics.initCounter(path.reverse, name, description, unit, a)

      def initHistogram(
          name: String,
          description: String,
          unit: String,
          a: Attributes
      ) =
        Metrics.initHistogram(path.reverse, name, description, unit, a)

      def span[T, S](
          name: String,
          attributes: Attributes
      )(v: => T > S): T > (IOs with S) =
        Traces.span(path.reverse, name, attributes)(v)
    }
}
