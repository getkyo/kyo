package kyo.stats.internal

import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import scala.annotation.tailrec

final case class TraceSpan(unsafe: UnsafeTraceSpan):

    def end(using Frame): Unit < Sync =
        Sync.Unsafe.defer(unsafe.end())

    def event(name: String, a: Attributes)(using Frame): Unit < Sync =
        Sync.Unsafe.defer(unsafe.event(name, a))

    def setStatus(status: UnsafeTraceSpan.Status)(using Frame): Unit < Sync =
        Sync.Unsafe.defer(unsafe.setStatus(status))
end TraceSpan

object TraceSpan:

    type Status = UnsafeTraceSpan.Status
    val Status = UnsafeTraceSpan.Status

    type Propagatable = UnsafeTraceSpan.Propagatable

    type Unsafe = UnsafeTraceSpan

    val noop: TraceSpan =
        TraceSpan(UnsafeTraceSpan.noop)

    def all(l: Seq[TraceSpan]): TraceSpan =
        l match
            case Seq() =>
                TraceSpan.noop
            case h +: Nil =>
                h
            case l =>
                TraceSpan(
                    new UnsafeTraceSpan:
                        def end()(using AllowUnsafe) =
                            @tailrec def loop(c: Seq[TraceSpan]): Unit =
                                if c ne Nil then
                                    c.head.unsafe.end()
                                    loop(c.tail)
                            loop(l)
                        end end
                        def event(name: String, a: Attributes)(using AllowUnsafe) =
                            @tailrec def loop(c: Seq[TraceSpan]): Unit =
                                if c ne Nil then
                                    c.head.unsafe.event(name, a)
                                    loop(c.tail)
                            loop(l)
                        end event
                        def setStatus(status: UnsafeTraceSpan.Status)(using AllowUnsafe) =
                            @tailrec def loop(c: Seq[TraceSpan]): Unit =
                                if c ne Nil then
                                    c.head.unsafe.setStatus(status)
                                    loop(c.tail)
                            loop(l)
                        end setStatus
                )

    private val currentSpan = Local.init(Maybe.empty[TraceSpan])

    def current(using Frame): Maybe[TraceSpan] < Sync =
        currentSpan.get

    def let[A, S](span: TraceSpan)(v: => A < S)(using Frame): A < S =
        currentSpan.let(Maybe(span))(v)

    def trace[A, S](
        exporter: TraceExporter,
        scope: List[String],
        name: String,
        attributes: Attributes = Attributes.empty
    )(v: => A < S)(using Frame): A < (Sync & S) =
        currentSpan.use { parent =>
            Sync.Unsafe.defer {
                val parentUnsafe = parent.toOption.map(_.unsafe)
                val child        = TraceSpan(exporter.startSpan(scope, name, parentUnsafe, attributes))
                Sync.ensure(child.end) {
                    currentSpan.let(Maybe(child))(v)
                }
            }
        }
end TraceSpan
