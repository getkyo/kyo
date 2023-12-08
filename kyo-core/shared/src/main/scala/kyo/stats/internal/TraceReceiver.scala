package kyo.stats.internal

import kyo._
import kyo.ios._
import kyo.stats._

import java.util.ServiceLoader
import scala.jdk.CollectionConverters._
import kyo.seqs._
import kyo.stats.Attributes

trait TraceReceiver {

  def startSpan(
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
      def startSpan(
          scope: List[String],
          name: String,
          parent: Option[Span] = None,
          attributes: Attributes = Attributes.empty
      ) =
        Span.noop
    }

  def all(receivers: List[TraceReceiver]): TraceReceiver =
    new TraceReceiver {
      def startSpan(
          scope: List[String],
          name: String,
          parent: Option[Span] = None,
          a: Attributes = Attributes.empty
      ) =
        Seqs
          .traverse(receivers)(_.startSpan(scope, name, None, a))
          .map(Span.all)
    }
}
