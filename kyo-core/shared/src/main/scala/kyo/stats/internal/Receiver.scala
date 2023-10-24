package kyo.stats.internal

import kyo._
import kyo.ios._
import kyo.stats._

import java.util.ServiceLoader
import scala.jdk.CollectionConverters._
import kyo.lists.Lists

trait Receiver {

  def counter(
      scope: List[String],
      name: String,
      description: String,
      unit: String,
      a: Attributes
  ): Counter

  def histogram(
      scope: List[String],
      name: String,
      description: String,
      unit: String,
      a: Attributes
  ): Histogram

  def gauge(
      scope: List[String],
      name: String,
      description: String,
      unit: String,
      a: Attributes
  )(f: => Double): Gauge

  def startSpan(
      scope: List[String],
      name: String,
      parent: Option[Span] = None,
      attributes: Attributes = Attributes.empty
  ): Span > IOs
}

object Receiver {

  val get: Receiver =
    ServiceLoader.load(classOf[Receiver]).iterator().asScala.toList match {
      case Nil =>
        Receiver.noop
      case head :: Nil =>
        head
      case l =>
        Receiver.all(l)
    }

  val noop: Receiver =
    new Receiver {
      def counter(
          scope: List[String],
          name: String,
          description: String,
          unit: String,
          a: Attributes
      ) =
        Counter.noop
      def histogram(
          scope: List[String],
          name: String,
          description: String,
          unit: String,
          a: Attributes
      ) =
        Histogram.noop

      def gauge(
          scope: List[String],
          name: String,
          description: String,
          unit: String,
          a: Attributes
      )(f: => Double) =
        Gauge.noop

      def startSpan(
          scope: List[String],
          name: String,
          parent: Option[Span] = None,
          attributes: Attributes = Attributes.empty
      ) =
        internal.Span.noop
    }

  def all(receivers: List[Receiver]): Receiver =
    new Receiver {

      def counter(
          scope: List[String],
          name: String,
          description: String,
          unit: String,
          a: Attributes
      ) =
        Counter.all(receivers.map(_.counter(scope, name, description, unit, a)))

      def histogram(
          scope: List[String],
          name: String,
          description: String,
          unit: String,
          a: Attributes
      ) =
        Histogram.all(receivers.map(_.histogram(scope, name, description, unit, a)))

      def gauge(
          scope: List[String],
          name: String,
          description: String,
          unit: String,
          a: Attributes
      )(f: => Double) =
        Gauge.all(receivers.map(_.gauge(scope, name, description, unit, a)(f)))

      def startSpan(
          scope: List[String],
          name: String,
          parent: Option[Span] = None,
          a: Attributes = Attributes.empty
      ) =
        Lists
          .traverse(receivers)(_.startSpan(scope, name, None, a))
          .map(internal.Span.all)
    }
}
