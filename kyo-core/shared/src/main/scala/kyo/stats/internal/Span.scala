package kyo.stats.internal

import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import scala.annotation.tailrec

case class Span(unsafe: Span.Unsafe):

    def end: Unit < IOs =
        IOs(unsafe.end())

    def event(name: String, a: Attributes): Unit < IOs =
        IOs(unsafe.event(name, a))
end Span

object Span:

    abstract class Unsafe:
        def end(): Unit
        def event(name: String, a: Attributes): Unit

    val noop: Span =
        Span(
            new Unsafe:
                def end() =
                    ()
                def event(name: String, a: Attributes) =
                    ()
        )

    def all(l: Seq[Span]): Span =
        l match
            case Seq() =>
                Span.noop
            case h +: Nil =>
                h
            case l =>
                Span(
                    new Span.Unsafe:
                        def end() =
                            @tailrec def loop(c: Seq[Span]): Unit =
                                if c ne Nil then
                                    c.head.unsafe.end()
                                    loop(c.tail)
                            loop(l)
                        end end
                        def event(name: String, a: Attributes) =
                            @tailrec def loop(c: Seq[Span]): Unit =
                                if c ne Nil then
                                    c.head.unsafe.event(name, a)
                                    loop(c.tail)
                            loop(l)
                        end event
                )

    private val currentSpan = Locals.init[Option[Span]](None)

    def trace[T, S](
        receiver: TraceReceiver,
        scope: List[String],
        name: String,
        attributes: Attributes = Attributes.empty
    )(v: => T < S): T < (IOs & S) =
        currentSpan.use { parent =>
            receiver
                .startSpan(scope, name, parent, attributes)
                .map { child =>
                    IOs.ensure(child.end) {
                        currentSpan.let(Some(child))(v)
                    }
                }
        }
end Span
