package kyo.stats.internal

import kyo._
import kyo.ios._
import kyo.lists._
import kyo.locals._
import kyo.stats._
import kyo.stats.Attributes

abstract class Span {

  def end: Unit > IOs

  def event(name: String, a: Attributes): Unit > IOs
}

object Span {

  val noop: Span =
    new Span {
      def end =
        ()
      def event(name: String, a: Attributes) =
        ()
    }

  def all(l: List[Span] > IOs): Span =
    new Span {
      def end =
        Lists.traverseUnit(l)(_.end)
      def event(name: String, a: Attributes) =
        Lists.traverseUnit(l)(_.event(name, a))
    }

  private val currentSpan = Locals.init[Option[Span]](None)

  def trace[T, S](
      receiver: TraceReceiver,
      scope: List[String],
      name: String,
      attributes: Attributes = Attributes.empty
  )(v: => T > S): T > (IOs with S) =
    currentSpan.get.map { parent =>
      receiver
        .startSpan(scope, name, parent, attributes)
        .map { child =>
          IOs.ensure(child.end) {
            currentSpan.let(Some(child))(v)
          }
        }
    }
}
