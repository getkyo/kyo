package kyo.stats.internal

import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import scala.annotation.tailrec

final case class Span(unsafe: Span.Unsafe):

    def end(using Frame): Unit < Sync =
        Sync(unsafe.end())

    def event(name: String, a: Attributes)(using Frame): Unit < Sync =
        Sync(unsafe.event(name, a))
end Span

object Span:

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
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

    private val currentSpan = Local.init(Maybe.empty[Span])

    def trace[A, S](
        receiver: TraceReceiver,
        scope: List[String],
        name: String,
        attributes: Attributes = Attributes.empty
    )(v: => A < S)(using Frame): A < (Sync & S) =
        currentSpan.use { parent =>
            receiver
                .startSpan(scope, name, parent, attributes)
                .map { child =>
                    Sync.ensure(child.end) {
                        currentSpan.let(Maybe(child))(v)
                    }
                }
        }
end Span
