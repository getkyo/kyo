package kyo

import kyo.Result.Error
import kyo.Result.Panic
import kyo.Tag
import kyo.kernel.ContextEffect

/** A structured effect for safe acquisition and finalization of resources.
  *
  * Resource provides a principled mechanism for working with entities that require proper cleanup, ensuring resources are released in a
  * deterministic manner even in the presence of errors or interruptions. This effect is particularly valuable for managing external
  * dependencies with lifecycle requirements such as file handles, network connections, database sessions, or any action that needs a
  * corresponding cleanup step.
  *
  * Key features:
  *   - Automatic resource finalization through `Resource.run` when computations complete or fail
  *   - Compositional API allowing resource dependencies to be built up safely with `acquireRelease` and `acquire`
  *   - Support for parallel cleanup through configurable concurrency levels with `run(closeParallelism)(...)`
  *   - Declarative cleanup registration using `Resource.ensure` for custom finalizers
  *
  * The Resource effect follows the bracket pattern (acquire-use-release) but with improved interruption handling and parallel cleanup
  * capabilities. Resource finalizers registered with `ensure` are guaranteed to run exactly once when the associated scope completes, with
  * failures in finalizers logged rather than thrown to avoid masking the primary computation result.
  *
  * Typically, you would use `acquireRelease` to pair resource acquisition with its cleanup function, then compose multiple resources
  * together before running the combined effect with `Resource.run`.
  *
  * @see
  *   [[kyo.Resource.acquireRelease]] For creating resources with custom acquire and release functions
  * @see
  *   [[kyo.Resource.acquire]] For creating resources from Java Closeables
  * @see
  *   [[kyo.Resource.ensure]] For registering cleanup actions
  * @see
  *   [[kyo.Resource.run]] For executing resource-managed computations
  */
sealed trait Resource extends ContextEffect[Resource.Finalizer]

object Resource:

    /** Ensures that the given effect is executed when the resource is released.
      *
      * @param v
      *   The effect to be executed on resource release.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   A unit value wrapped in Resource and Sync effects.
      */
    inline def ensure(inline v: => Any < (Async & Abort[Throwable]))(using frame: Frame): Unit < (Resource & Sync) =
        ContextEffect.suspendWith(Tag[Resource])(_.ensure(_ => v))

    /** Ensures that the given effect is executed when the resource is released, with information about the computation's outcome.
      *
      * This version provides the finalizer with information about whether the computation completed successfully or failed with an
      * exception. The finalizer receives a `Maybe[Error[Any]]` which will be `Absent` if the computation succeeded, or `Present` if it
      * failed.
      *
      * @param f
      *   The finalizer function that receives information about the computation's outcome and performs cleanup actions.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   A unit value wrapped in Resource and Sync effects.
      */
    inline def ensure(inline f: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using frame: Frame): Unit < (Resource & Sync) =
        ContextEffect.suspendWith(Tag[Resource])(_.ensure(f))

    /** Acquires a resource and provides a release function.
      *
      * @param acquire
      *   The effect to acquire the resource.
      * @param release
      *   The function to release the acquired resource.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The acquired resource wrapped in Resource, Sync, and S effects.
      */
    def acquireRelease[A, S](acquire: => A < S)(release: A => Any < (Async & Abort[Throwable]))(using Frame): A < (Resource & Sync & S) =
        Sync {
            acquire.map { resource =>
                ensure(release(resource)).andThen(resource)
            }
        }

    /** Acquires a Closeable resource.
      *
      * @param resource
      *   The effect to acquire the Closeable resource.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The acquired Closeable resource wrapped in Resource, Sync, and S effects.
      */
    def acquire[A <: java.io.Closeable, S](resource: => A < S)(using Frame): A < (Resource & Sync & S) =
        acquireRelease(resource)(_.close())

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
        Sync.Unsafe {
            val finalizer = Finalizer.Awaitable.Unsafe.init(closeParallelism)
            ContextEffect.handle(Tag[Resource], finalizer, _ => finalizer)(v)
                .handle(
                    Sync.ensure(finalizer.close),
                    Abort.run[Any]
                ).map { result =>
                    finalizer
                        .close(result.error)
                        .andThen(finalizer.await)
                        .andThen(Abort.get(result.asInstanceOf[Result[Nothing, A]]))
                }
        }

    given Isolate.Contextual[Resource, Any] = Isolate.Contextual.derive[Resource, Any]

    /** Represents a finalizer for a resource. */
    sealed abstract class Finalizer:
        def ensure(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Sync

    object Finalizer:
        sealed abstract class Awaitable extends Finalizer:
            def close(ex: Maybe[Error[Any]])(using Frame): Unit < Sync
            def await(using Frame): Unit < Async

        object Awaitable:
            object Unsafe:
                def init(parallelism: Int)(using frame: Frame, u: AllowUnsafe): Awaitable =
                    new Awaitable:
                        val queue = Queue.Unbounded.Unsafe.init[Maybe[Error[Any]] => Any < (Async & Abort[Throwable])](
                            Access.MultiProducerSingleConsumer
                        )
                        val promise = Promise.Unsafe.init[Nothing, Unit]().safe

                        def ensure(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Sync =
                            Sync.Unsafe {
                                if !queue.offer(v).contains(true) then
                                    Abort.panic(new Closed(
                                        "Finalizer",
                                        frame,
                                        "This finalizer is already closed. This may happen if a background fiber escapes the scope of a 'Resource.run' call."
                                    ))
                                else ()
                            }
                        end ensure

                        def close(ex: Maybe[Error[Any]])(using Frame): Unit < Sync =
                            Sync.Unsafe {
                                queue.close() match
                                    case Absent => ()
                                    case Present(tasks) =>
                                        if tasks.isEmpty then
                                            promise.completeDiscard(Result.unit)
                                        else
                                            Async.foreachDiscard(tasks, parallelism) { task =>
                                                Abort.run[Throwable](task(ex))
                                                    .map(_.foldError(_ => (), ex => Log.error("Resource finalizer failed", ex.exception)))
                                            }
                                                .handle(Async.run[Nothing, Unit, Any])
                                                .map(promise.becomeDiscard)
                            }

                        def await(using Frame): Unit < Async = promise.get
                end init
            end Unsafe
        end Awaitable
    end Finalizer

end Resource
