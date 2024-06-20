package kyo2

import java.io.Closeable
import kyo.Tag
import kyo2.kernel.ContextEffect

sealed trait Resource extends ContextEffect[Queue.Unbounded[Unit < (Async & IO)]]

object Resource:

    def ensure(v: => Unit < (Async & IO))(using Frame): Unit < (Resource & IO) =
        ContextEffect.suspendMap(Tag[Resource]) { q =>
            q.offer(IO(v)).map {
                case true => ()
                case false =>
                    bug("Resource finalizer queue already closed.")
            }
        }

    def acquireRelease[T, S](acquire: T < S)(release: T => Unit < (Async & IO))(using Frame): T < (Resource & IO & S) =
        acquire.map { resource =>
            ensure(release(resource)).andThen(resource)
        }

    def acquire[T <: Closeable, S](resource: T < S)(using Frame): T < (Resource & IO & S) =
        acquireRelease(resource)(r => IO(r.close()))

    def run[T, S](v: T < (Resource & S))(using Frame): T < (Async & IO & S) =
        Queue.initUnbounded[Unit < (Async & IO)](Access.Mpsc).map { q =>
            Promise.init[Nothing, Unit].map { p =>
                def close: Unit < IO =
                    q.close.map {
                        case Maybe.Empty =>
                            bug("Resource finalizer queue already closed.")
                        case Maybe.Defined(l) =>
                            Async.run(Kyo.seq.collectUnit(l)).map(p.become(_)).unit
                    }
                IO.ensure(close) {
                    ContextEffect.handle(Tag[Resource], q, _ => q)(v)
                }.map(result => p.get.andThen(result))
            }
        }

end Resource
