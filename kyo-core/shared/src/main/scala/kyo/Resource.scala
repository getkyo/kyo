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
    case class Finalizer(createdAt: Frame, queue: Queue.Unbounded[Unit < (Async & Abort[Throwable])])

    /** Ensures that the given effect is executed when the resource is released.
      *
      * @param v
      *   The effect to be executed on resource release.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   A unit value wrapped in Resource and IO effects.
      */
    def ensure(v: => Any < (Async & Abort[Throwable]))(using frame: Frame): Unit < (Resource & IO) =
        ContextEffect.suspendWith(Tag[Resource]) { finalizer =>
            Abort.run(finalizer.queue.offer(IO(v.unit))).map {
                case Result.Success(_) => ()
                case _ =>
                    throw new Closed(
                        "Finalizer",
                        finalizer.createdAt,
                        "The finalizer queue is already closed. This may happen if " +
                            "a background fiber escapes the scope of a 'Resource.run' call."
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
    def acquireRelease[A, S](acquire: A < S)(release: A => Any < (Async & Abort[Throwable]))(using Frame): A < (Resource & IO & S) =
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

    /** Runs a resource-managed effect with default parallelism of 1.
      *
      * This method collects all resources used within the computation and ensures they are properly closed when the computation completes
      * (either successfully or with an error). Resources are closed sequentially (parallelism = 1).
      *
      * @param v
      *   The effect to run with resource management.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The result of the effect wrapped in Async and S effects.
      */
    def run[A, S](v: A < (Resource & S))(using frame: Frame): A < (Async & S) =
        run(1)(v)

    /** Runs a resource-managed effect with specified parallelism for cleanup.
      *
      * This method tracks all resources acquired during the computation and ensures they are properly closed when the computation completes
      * (either successfully or with an error). The cleanup phase runs resource finalizers in parallel, grouped according to the specified
      * parallelism level. For example, with closeParallelism=3, up to 3 resources can be cleaned up simultaneously.
      *
      * @param closeParallelism
      *   The number of parallel tasks to use when running finalizers. This controls how many resources can be cleaned up simultaneously.
      * @param v
      *   The effect to run with resource management.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The result of the effect wrapped in Async and S effects.
      */
    def run[A, S](closeParallelism: Int)(v: A < (Resource & S))(using frame: Frame): A < (Async & S) =
        Queue.Unbounded.initWith[Unit < (Async & Abort[Throwable])](Access.MultiProducerSingleConsumer) { q =>
            Promise.initWith[Nothing, Unit] { p =>
                val finalizer = Finalizer(frame, q)
                def close: Unit < IO =
                    q.close.map {
                        case Absent =>
                            bug("Resource finalizer queue already closed.")
                        case Present(tasks) =>
                            Async.parallel(closeParallelism) {
                                tasks.map { task =>
                                    Abort.run[Throwable](task)
                                        .map(_.foldError(_ => (), ex => Log.error("Resource finalizer failed", ex.exception)))
                                }
                            }
                                .unit
                                .pipe(Async.run[Nothing, Unit, Any])
                                .map(p.becomeDiscard)
                    }
                ContextEffect.handle(Tag[Resource], finalizer, _ => finalizer)(v)
                    .pipe(IO.ensure(close))
                    .map(result => p.get.andThen(result))
            }
        }

    given Isolate.Contextual[Resource, Any] = Isolate.Contextual.derive[Resource, Any]

end Resource
