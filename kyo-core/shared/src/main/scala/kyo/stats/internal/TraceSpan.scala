package kyo.stats.internal

import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import scala.annotation.tailrec

final case class TraceSpan(unsafe: TraceSpan.Unsafe):

    def end(using Frame): Unit < Sync =
        Sync.defer(unsafe.end())

    def event(name: String, a: Attributes)(using Frame): Unit < Sync =
        Sync.defer(unsafe.event(name, a))
end TraceSpan

object TraceSpan:

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe:
        def end(): Unit
        def event(name: String, a: Attributes): Unit

    val noop: TraceSpan =
        TraceSpan(
            new Unsafe:
                def end() =
                    ()
                def event(name: String, a: Attributes) =
                    ()
        )

    def all(l: Seq[TraceSpan]): TraceSpan =
        l match
            case Seq() =>
                TraceSpan.noop
            case h +: Nil =>
                h
            case l =>
                TraceSpan(
                    new TraceSpan.Unsafe:
                        def end() =
                            @tailrec def loop(c: Seq[TraceSpan]): Unit =
                                if c ne Nil then
                                    c.head.unsafe.end()
                                    loop(c.tail)
                            loop(l)
                        end end
                        def event(name: String, a: Attributes) =
                            @tailrec def loop(c: Seq[TraceSpan]): Unit =
                                if c ne Nil then
                                    c.head.unsafe.event(name, a)
                                    loop(c.tail)
                            loop(l)
                        end event
                )

    private val currentSpan = Local.init(Maybe.empty[TraceSpan])

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
end TraceSpan
