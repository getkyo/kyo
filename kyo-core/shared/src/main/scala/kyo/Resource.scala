package kyo

import java.io.Closeable
import kyo.Tag
import kyo.kernel.ContextEffect

/** An effect representing resources that can be acquired and released safely.
  *
  * Resources are typically used for managing external entities that need proper cleanup, such as file handles, network connections, or
  * database connections. The Resource effect ensures that acquired resources are properly released when they are no longer needed, even in
  * the presence of errors or exceptions.
  */
sealed trait Resource extends ContextEffect[Resource.Finalizer]

object Resource:

    /** Represents a finalizer for a resource. */
    case class Finalizer(createdAt: Frame, queue: Queue.Unbounded[Unit < Async])

    /** Ensures that the given effect is executed when the resource is released.
      *
      * @param v
      *   The effect to be executed on resource release.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   A unit value wrapped in Resource and IO effects.
      */
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

    /** Acquires a resource and provides a release function.
      *
      * @param acquire
      *   The effect to acquire the resource.
      * @param release
      *   The function to release the acquired resource.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The acquired resource wrapped in Resource, IO, and S effects.
      */
    def acquireRelease[A, S](acquire: A < S)(release: A => Unit < Async)(using Frame): A < (Resource & IO & S) =
        acquire.map { resource =>
            ensure(release(resource)).andThen(resource)
        }

    /** Acquires a Closeable resource.
      *
      * @param resource
      *   The effect to acquire the Closeable resource.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The acquired Closeable resource wrapped in Resource, IO, and S effects.
      */
    def acquire[A <: Closeable, S](resource: A < S)(using Frame): A < (Resource & IO & S) =
        acquireRelease(resource)(r => IO(r.close()))

    /** Runs a resource-managed effect.
      *
      * @param v
      *   The effect to run with resource management.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The result of the effect wrapped in Async and S effects.
      */
    def run[A, S](v: A < (Resource & S))(using frame: Frame): A < (Async & S) =
        Queue.initUnbounded[Unit < Async](Access.MultiProducerSingleConsumer).map { q =>
            Promise.init[Nothing, Unit].map { p =>
                val finalizer = Finalizer(frame, q)
                def close: Unit < IO =
                    q.close.map {
                        case Empty =>
                            bug("Resource finalizer queue already closed.")
                        case Defined(l) =>
                            Kyo.foreachDiscard(l)(task =>
                                Abort.run[Throwable](task)
                                    .map(_.fold(ex => Log.error("Resource finalizer failed", ex.exception))(_ => ()))
                            )
                                .pipe(Async.run)
                                .map(p.becomeDiscard)
                    }
                ContextEffect.handle(Tag[Resource], finalizer, _ => finalizer)(v)
                    .pipe(IO.ensure(close))
                    .map(result => p.get.andThen(result))
            }
        }

end Resource
