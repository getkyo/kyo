package kyo

import java.io.Closeable

opaque type Resources <: IOs = IOs

object Resources:

    private val local = Locals.init[Queues.Unbounded[() => Unit < IOs] | None.type](None)

    def ensure(v: => Unit < IOs): Unit < Resources =
        local.use {
            case None =>
                bug("Can't locate Resources finalizer queue.")
            case q: Queues.Unbounded[() => Unit < IOs] =>
                q.offer(() => v).map {
                    case true => ()
                    case false =>
                        bug("Resources finalizer queue already closed.")
                }
        }

    def acquire[T <: Closeable](resource: => T): T < Resources =
        lazy val v = resource
        ensure(v.close()).andThen(v)

    def run[T, S](v: T < (Resources & S)): T < (IOs & S) =
        Queues.initUnbounded[() => Unit < IOs](Access.Mpsc).map { q =>
            def close: Unit < IOs =
                q.close.map {
                    case None =>
                        bug("Resources finalizer queue already closed.")
                    case Some(l) =>
                        Seqs.collect(l.map(_())).unit
                }
            IOs.ensure(close) {
                local.let(q)(v)
            }
        }

end Resources
