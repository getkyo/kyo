package kyo

import java.io.Closeable

opaque type Resources <: IOs = IOs

object Resources:

    private val local = Locals.init[Queues.Unbounded[Unit < IOs] | None.type](None)

    def ensure(v: => Unit < IOs): Unit < Resources =
        local.use {
            case _: None.type =>
                bug("Can't locate Resources finalizer queue.")
            case q: Queues.Unbounded[Unit < IOs] =>
                q.offer(IOs(v)).map {
                    case true => ()
                    case false =>
                        bug("Resources finalizer queue already closed.")
                }
        }

    def acquireRelease[T](acquire: => T < IOs)(release: T => Unit < IOs): T < Resources =
        IOs {
            acquire.map { resource =>
                ensure(release(resource)).andThen(resource)
            }
        }

    def acquire[T <: Closeable](resource: => T < IOs): T < Resources =
        acquireRelease(resource)(r => IOs(r.close()))

    def run[T, S](v: T < (Resources & S)): T < (IOs & S) =
        Queues.initUnbounded[Unit < IOs](Access.Mpsc).map { q =>
            def close: Unit < IOs =
                q.close.map {
                    case None =>
                        bug("Resources finalizer queue already closed.")
                    case Some(l) =>
                        Seqs.collectUnit(l)
                }
            IOs.ensure(close) {
                local.let(q)(v)
            }
        }

end Resources
