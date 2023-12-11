package kyo.stats.internal

import kyo._
import kyo.ios._
import kyo.seqs._
import kyo.locals._
import kyo.stats._
import kyo.stats.Attributes

case class Span(unsafe: Span.Unsafe) {

  def end: Unit < IOs =
    IOs(unsafe.end())

  def event(name: String, a: Attributes): Unit < IOs =
    IOs(unsafe.event(name, a))
}

object Span {

  abstract class Unsafe {
    def end(): Unit
    def event(name: String, a: Attributes): Unit
  }

  val noop: Span =
    Span(
        new Unsafe {
          def end() =
            ()
          def event(name: String, a: Attributes) =
            ()
        }
    )

  def all(l: Seq[Span]): Span =
    l match {
      case Seq() =>
        Span.noop
      case h +: Nil =>
        h
      case l =>
        Span(
            new Span.Unsafe {
              def end() = {
                var c = l
                while (c ne Nil) {
                  c.head.unsafe.end()
                  c = c.tail
                }
              }
              def event(name: String, a: Attributes) = {
                var c = l
                while (c ne Nil) {
                  c.head.unsafe.event(name, a)
                  c = c.tail
                }
              }
            }
        )
    }

  private val currentSpan = Locals.init[Option[Span]](None)

  def trace[T, S](
      receiver: TraceReceiver,
      scope: List[String],
      name: String,
      attributes: Attributes = Attributes.empty
  )(v: => T < S): T < (IOs with S) =
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
