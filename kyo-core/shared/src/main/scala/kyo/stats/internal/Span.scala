package kyo.stats.internal

import kyo._
import kyo.ios._
import kyo.choices._
import kyo.locals._
import kyo.stats._

trait Span {

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
        Choices.traverseUnit(l)(_.end)
      def event(name: String, a: Attributes) =
        Choices.traverseUnit(l)(_.event(name, a))
    }

  private val local = Locals.init[Option[Span]](None)

  def trace[T, S](
      scope: List[String],
      name: String,
      attributes: Attributes = Attributes.empty
  )(v: => T > S): T > (IOs with S) =
    local.get.map { parent =>
      Receiver.get
        .startSpan(scope, name, parent, attributes)
        .map { child =>
          IOs.ensure(child.end) {
            local.let(Some(child))(v)
          }
        }
    }
}
