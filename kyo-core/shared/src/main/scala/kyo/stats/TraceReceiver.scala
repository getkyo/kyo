package kyo.stats

import kyo._
import kyo.ios._
import kyo.choices._
import kyo.stats.Attributes
import java.util.ServiceLoader
import scala.jdk.CollectionConverters._

trait TraceReceiver {
  def span[T, S](
      scope: List[String],
      name: String,
      parent: Option[Span] = None,
      attributes: Attributes = Attributes.empty
  ): Span > IOs
}

object TraceReceiver {

  val get: TraceReceiver =
    ServiceLoader.load(classOf[TraceReceiver]).iterator().asScala.toList match {
      case Nil =>
        TraceReceiver.noop
      case head :: Nil =>
        head
      case l =>
        TraceReceiver.all(l)
    }

  val noop: TraceReceiver =
    new TraceReceiver {
      def span[T, S](
          scope: List[String],
          name: String,
          parent: Option[Span] = None,
          attributes: Attributes = Attributes.empty
      ) =
        Span.noop
    }

  def all(l: List[TraceReceiver]): TraceReceiver =
    new TraceReceiver {
      def span[T, S](
          scope: List[String],
          name: String,
          parent: Option[Span] = None,
          attributes: Attributes = Attributes.empty
      ) =
        Span.all(Choices.traverse(l)(_.span(scope, name, parent, attributes)))
    }
}
