package kyo

import kyo.Result.Error
import kyo.Result.Panic
import kyo.Tag
import kyo.kernel.ContextEffect

/** A structured effect for safe acquisition and finalization of resources.
  *
  * Scope provides a principled mechanism for working with entities that require proper cleanup, ensuring resources are released in a
  * deterministic manner even in the presence of errors or interruptions. This effect is particularly valuable for managing external
  * dependencies with lifecycle requirements such as file handles, network connections, database sessions, or any action that needs a
  * corresponding cleanup step.
  *
  * Key features:
  *   - Automatic resource finalization through `Scope.run` when computations complete or fail
  *   - Compositional API allowing resource dependencies to be built up safely with `acquireRelease` and `acquire`
  *   - Support for parallel cleanup through configurable concurrency levels with `run(closeParallelism)(...)`
  *   - Declarative cleanup registration using `Scope.ensure` for custom finalizers
  *   - Hierarchical scope management where child scopes are automatically closed when parent scopes close
  *
  * The Scope effect follows the bracket pattern (acquire-use-release) but with improved interruption handling and parallel cleanup
  * capabilities. Scope finalizers registered with `ensure` are guaranteed to run exactly once when the associated scope completes, with
  * failures in finalizers logged rather than thrown to avoid masking the primary computation result.
  *
  * Scope supports hierarchical nesting where child scopes are automatically closed when their parent scope closes. This ensures that
  * resources acquired in child scopes are properly cleaned up even if the parent scope completes or fails.
  *
  * Typically, you would use `acquireRelease` to pair resource acquisition with its cleanup function, then compose multiple resources
  * together before running the combined effect with `Scope.run`.
  *
  * @see
  *   [[kyo.Scope.acquireRelease]] For creating resources with custom acquire and release functions
  * @see
  *   [[kyo.Scope.acquire]] For creating resources from Java Closeables
  * @see
  *   [[kyo.Scope.ensure]] For registering cleanup actions
  * @see
  *   [[kyo.Scope.run]] For executing resource-managed computations
  */
opaque type Scope <: Sync = Sync

object Scope:

    private val local = Local.init(Maybe.empty[Finalizer])

    /** Ensures that the given effect is executed when the scope is released.
      *
      * @param v
      *   The effect to be executed on scope release.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   A unit value wrapped in Scope effect.
      */
    def ensure(v: => Any < (Async & Abort[Throwable]))(using Frame): Unit < Scope =
        ensure(_ => v)

    /** Ensures that the given effect is executed when the scope is released, with information about the computation's outcome.
      *
      * This version provides the finalizer with information about whether the computation completed successfully or failed with an
      * exception. The finalizer receives a `Maybe[Error[Any]]` which will be `Absent` if the computation succeeded, or `Present` if it
      * failed.
      *
      * @param callback
      *   The finalizer function that receives information about the computation's outcome and performs cleanup actions.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   A unit value wrapped in Scope effect.
      */
    def ensure(callback: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Scope =
        withFinalizerUnsafe(_.ensure(callback))

    /** Acquires a resource and provides a release function.
      *
      * @param acquire
      *   The effect to acquire the resource.
      * @param release
      *   The function to release the acquired resource.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The acquired resource wrapped in Scope and S effects.
      */
    def acquireRelease[A, S](acquire: => A < S)(release: A => Any < (Async & Abort[Throwable]))(using Frame): A < (Scope & S) =
        withFinalizerUnsafe { finalizer =>
            acquire.ensureMap { resource =>
                finalizer.ensure(_ => release(resource))
                resource
            }
        }

    /** Acquires a Closeable resource.
      *
      * @param resource
      *   The effect to acquire the Closeable resource.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The acquired Closeable resource wrapped in Scope and S effects.
      */
    def acquire[A <: AutoCloseable, S](resource: => A < S)(using Frame): A < (Scope & S) =
        acquireRelease(resource)(_.close())

    /** Runs a scope-managed effect with default parallelism of 1.
      *
      * This method collects all resources used within the computation and ensures they are properly closed when the computation completes
      * (either successfully or with an error). Resources are closed sequentially (parallelism = 1).
      *
      * @param v
      *   The effect to run with scope management.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The result of the effect wrapped in Async and S effects.
      */
    def run[A, S](v: A < (Scope & S))(using Frame): A < (Async & S) =
        run(1, true)(v)

    /** Runs a scope-managed effect with specified parallelism for cleanup.
      *
      * This method tracks all resources acquired during the computation and ensures they are properly closed when the computation completes
      * (either successfully or with an error). The cleanup phase runs resource finalizers in parallel, grouped according to the specified
      * parallelism level. For example, with closeParallelism=3, up to 3 resources can be cleaned up simultaneously.
      *
      * @param closeParallelism
      *   The number of parallel tasks to use when running finalizers. This controls how many resources can be cleaned up simultaneously.
      * @param awaitClose
      *   Whether to wait for all finalizers to complete before returning the result.
      * @param v
      *   The effect to run with scope management.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The result of the effect wrapped in Async and S effects.
      */
    def run[A, S](closeParallelism: Int, awaitClose: Boolean = true)(v: A < (Scope & S))(using Frame): A < (Async & S) =
        runLocally(closeParallelism, awaitClose) { finalizer =>
            local.let(Present(finalizer))(v)
        }

    /** Runs a scope-managed effect with local finalizer management.
      *
      * This is an advanced method that is useful for library functions that need to acquire temporary resources (like database connections,
      * caches, etc.) but want to ensure these resources don't interfere with other scope-managed resources. The temporary resources are
      * cleaned up when the function completes, regardless of the outer scope state, and resources in child scopes are left untouched.
      *
      * @param f
      *   The function that receives a finalizer and returns the effect to run.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The result of the effect wrapped in Async and S effects.
      */
    def runLocally[A, S](f: Finalizer => A < (Scope & S))(using Frame): A < (Async & S) =
        runLocally(1)(f)

    /** Runs a scope-managed effect with local finalizer management and specified parallelism.
      *
      * This is an advanced method that is useful for library functions that need to acquire temporary resources (like database connections,
      * caches, etc.) but want to ensure these resources don't interfere with other scope-managed resources. The temporary resources are
      * cleaned up when the function completes, regardless of the outer scope state, and resources in child scopes are left untouched.
      *
      * @param closeParallelism
      *   The number of parallel tasks to use when running finalizers.
      * @param awaitClose
      *   Whether to wait for all finalizers to complete before returning the result.
      * @param f
      *   The function that receives a finalizer and returns the effect to run.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   The result of the effect wrapped in Async and S effects.
      */
    def runLocally[A, S](closeParallelism: Int, awaitClose: Boolean = true)(f: Finalizer => A < (Scope & S))(
        using Frame
    ): A < (Async & S) =
        Sync.withLocal(local) { parent =>
            import AllowUnsafe.embrace.danger
            val finalizer = new Finalizer.Unsafe
            parent.foreach(p => discard(p.tryEnsure(finalizer.close(_, closeParallelism))))
            f(finalizer).handle(
                Sync.ensure(finalizer.close(_, closeParallelism)),
                Abort.run[Any]
            ).map { result =>
                finalizer.close(result.error, closeParallelism)
                Kyo.when(awaitClose)(finalizer.await().safe.get)
                    .andThen(Abort.get(result.asInstanceOf[Result[Nothing, A]]))
            }
        }

    private def withFinalizerUnsafe[A, S](f: AllowUnsafe ?=> Finalizer => A < S)(using Frame): A < (Scope & S) =
        local.use {
            case Present(finalizer) => f(using AllowUnsafe.embrace.danger)(finalizer)
            case Absent             => bug("Missing finalizer from context")
        }

    given Isolate.Contextual[Scope, Sync] = Isolate.Contextual.derive[Sync, Sync]

    /** Represents a finalizer for a scope. */
    opaque type Finalizer = Finalizer.Unsafe

    object Finalizer:

        extension (self: Finalizer)

            /** Ensures that the given callback is executed when the scope is released.
              *
              * @param callback
              *   The callback function to be executed on scope release.
              * @param frame
              *   The implicit Frame for context.
              * @return
              *   A unit value wrapped in Sync effect.
              */
            def ensure(callback: Callback)(using Frame): Unit < Sync =
                Sync.Unsafe(self.ensure(callback))

            def unsafe: Unsafe = self

        end extension

        /** Type alias for the callback function used in finalizers. */
        type Callback = Maybe[Error[Any]] => Any < (Async & Abort[Throwable])

        /** Unsafe implementation of a finalizer. */
        final class Unsafe(using frame: Frame, allow: AllowUnsafe):

            private val queue   = Queue.Unbounded.Unsafe.init[Callback](Access.MultiProducerSingleConsumer)
            private val promise = Promise.Unsafe.init[Nothing, Unit]()

            private[Scope] def tryEnsure(callback: Callback): Unit =
                discard(queue.offer(callback))

            def ensure(callback: Callback)(using AllowUnsafe): Unit =
                if !queue.offer(callback).contains(true) then
                    throw new Closed(
                        "Finalizer",
                        frame,
                        "This finalizer is already closed. This may happen if a background fiber escapes the scope of a 'Scope.run' call."
                    )

            def close(ex: Maybe[Error[Any]], parallelism: Int)(using AllowUnsafe): Unit =
                queue.close() match
                    case Absent => ()
                    case Present(tasks) =>
                        if tasks.isEmpty then
                            promise.completeDiscard(Result.unit)
                        else
                            Sync.Unsafe.evalOrThrow(
                                Async.foreachDiscard(tasks, parallelism) { task =>
                                    Abort.run[Throwable](task(ex))
                                        .map(_.foldError(_ => (), ex => Log.error("Scope finalizer failed", ex.exception)))
                                }
                                    .handle(Fiber.init[Nothing, Unit, Any])
                                    .map(promise.safe.becomeDiscard)
                            )

            def await()(using AllowUnsafe): Fiber.Unsafe[Nothing, Unit] =
                promise

            def safe: Finalizer = this
        end Unsafe

    end Finalizer

end Scope
