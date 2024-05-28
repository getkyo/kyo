package kyo

import java.io.Closeable
import kyo.internal.Trace

opaque type Resources <: Fibers = Fibers

object Resources:

    private val local = Locals.init[Queues.Unbounded[Unit < Fibers] | None.type](None)

    def ensure(v: => Unit < Fibers)(using Trace): Unit < Resources =
        local.use {
            case _: None.type =>
                bug("Can't locate Resources finalizer queue.")
            case q: Queues.Unbounded[Unit < Fibers] =>
                q.offer(IOs(v)).map {
                    case true => ()
                    case false =>
                        bug("Resources finalizer queue already closed.")
                }
        }

    def acquireRelease[T, S](acquire: => T < (S & Fibers))(release: T => Unit < Fibers)(using Trace): T < (Resources & S) =
        IOs {
            acquire.map { resource =>
                ensure(release(resource)).andThen(resource)
            }
        }

    def acquire[T <: Closeable](resource: => T < Fibers)(using Trace): T < Resources =
        acquireRelease(resource)(r => IOs(r.close()))

    def run[T, S](v: T < (Resources & S))(using Trace): T < (Fibers & S) =
        Queues.initUnbounded[Unit < Fibers](Access.Mpsc).map { q =>
            Fibers.initPromise[Unit].map { p =>
                def close: Unit < IOs =
                    q.close.map {
                        case None =>
                            bug("Resources finalizer queue already closed.")
                        case Some(l) =>
                            Fibers.run(Seqs.collectUnit(l)).map(p.become(_)).unit
                    }
                IOs.ensure(close) {
                    local.let(q)(v)
                }.map(result => p.get.andThen(result))
            }
        }

end Resources
