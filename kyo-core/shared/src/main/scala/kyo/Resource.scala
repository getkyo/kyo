package kyo

import java.io.Closeable
import kyo.Tag
import kyo.kernel.ContextEffect

sealed trait Resource extends ContextEffect[Resource.Finalizer]

object Resource:

    case class Finalizer(createdAt: Frame, queue: Queue.Unbounded[Unit < Async])

    def ensure(v: => Unit < Async)(using frame: Frame): Unit < (Resource & IO) =
        ContextEffect.suspendMap(Tag[Resource]) { finalizer =>
            finalizer.queue.offer(IO(v)).map {
                case true => ()
                case false =>
                    throw new Closed(
                        "Resource finalizer queue already closed. This may happen if " +
                            "a background fiber escapes the scope of a 'Resource.run' call.",
                        finalizer.createdAt,
                        frame
                    )
            }
        }

    def acquireRelease[A, S](acquire: A < S)(release: A => Unit < Async)(using Frame): A < (Resource & IO & S) =
        acquire.map { resource =>
            ensure(release(resource)).andThen(resource)
        }

    def acquire[A <: Closeable, S](resource: A < S)(using Frame): A < (Resource & IO & S) =
        acquireRelease(resource)(r => IO(r.close()))

    def run[A, S](v: A < (Resource & S))(using frame: Frame): A < (Async & S) =
        Queue.initUnbounded[Unit < Async](Access.Mpsc).map { q =>
            Promise.init[Nothing, Unit].map { p =>
                val finalizer = Finalizer(frame, q)
                def close: Unit < IO =
                    q.close.map {
                        case Maybe.Empty =>
                            bug("Resource finalizer queue already closed.")
                        case Maybe.Defined(l) =>
                            Kyo.seq.foreach(l)(task =>
                                Abort.run[Throwable](task)
                                    .map(_.fold(ex => Log.error("Resource finalizer failed", ex.exception))(_ => ()))
                            )
                                .pipe(Async.run)
                                .map(p.becomeUnit)
                    }
                ContextEffect.handle(Tag[Resource], finalizer, _ => finalizer)(v)
                    .pipe(IO.ensure(close))
                    .map(result => p.get.andThen(result))
            }
        }

end Resource
