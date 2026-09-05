package kyo

import java.util.concurrent.ConcurrentHashMap
import kyo.Result.Error
import kyo.Result.Panic
import kyo.kernel.ContextEffect

/** A structured effect for safe acquisition and finalization of resources.
  *
  * Scope provides a principled mechanism for working with entities that require proper cleanup, ensuring resources are released in a
  * deterministic manner even in the presence of errors or interruptions. This effect is particularly valuable for managing external
  * dependencies with lifecycle requirements such as file handles, network connections, database sessions, or any action that needs a
  * corresponding cleanup step.
  *
  * Key features:
  *
  *   - Automatic resource finalization through `Scope.run` when computations complete or fail
  *   - Compositional API allowing resource dependencies to be built up safely with `acquireRelease` and `acquire`
  *   - Support for parallel cleanup through configurable concurrency levels with `run(closeParallelism)(...)`
  *   - Declarative cleanup registration using `Scope.ensure` for custom finalizers
  *
  * The Scope effect follows the bracket pattern (acquire-use-release) but with improved interruption handling and parallel cleanup
  * capabilities. Scope finalizers registered with `ensure` are guaranteed to run exactly once when the associated scope completes, with
  * failures in finalizers logged rather than thrown to avoid masking the primary computation result.
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
sealed trait Scope extends ContextEffect[Scope.Finalizer]

object Scope:

    /** Ensures that the given effect is executed when the resource is released.
      *
      * @param v
      *   The effect to be executed on resource release.
      * @param frame
      *   The implicit Frame for context.
      * @return
      *   A unit value wrapped in Resource and Sync effects.
      */
    inline def ensure(inline v: => Any < (Async & Abort[Throwable]))(using frame: Frame): Unit < (Scope & Sync) =
        ContextEffect.suspendWith(Tag[Scope])(_.ensure(_ => v))

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
    inline def ensure(inline f: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using frame: Frame): Unit < (Scope & Sync) =
        ContextEffect.suspendWith(Tag[Scope])(_.ensure(f))

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
    def acquireRelease[A, S](acquire: => A < S)(release: A => Any < (Async & Abort[Throwable]))(using
        frame: Frame
    ): A < (Scope & Sync & S) =
        ContextEffect.suspendWith(Tag[Scope]) { finalizer =>
            // The finalizer is read before the acquire runs, and the registration is a plain call rather than a
            // suspension, so `ensureMap` can put the acquire's completion and that registration in one step. Mapping
            // with `map` instead would leave a window: the registration would be a suspension of its own, and an
            // interrupt pending when the acquire completes parks the computation before that suspension is dispatched,
            // leaving the abandonment nothing to release the acquired value with.
            Sync.defer(acquire).ensureMap { resource =>
                // Unsafe: the registration has to complete in the same step the acquire's value arrives in, which
                // rules out returning it as an effect for the evaluator to dispatch later.
                import AllowUnsafe.embrace.danger
                finalizer.ensureUnsafe(_ => release(resource))
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
      *   The acquired Closeable resource wrapped in Resource, Sync, and S effects.
      */
    def acquire[A <: java.lang.AutoCloseable, S](resource: => A < S)(using Frame): A < (Scope & Sync & S) =
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
    def run[A, S](v: A < (Scope & S))(using frame: Frame): A < (Async & S) =
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
    def run[A, S](closeParallelism: Int)(v: A < (Scope & S))(using frame: Frame): A < (Async & S) =
        Sync.Unsafe.defer {
            val finalizer = Finalizer.Unsafe.init(closeParallelism)
            // The three hooks are the whole hierarchy. `derive` runs when this region installs, so a run
            // nested inside another joins its parent there; `fork` gives a computation crossing an isolation
            // boundary a scope of its own, already a member of the one it left; `join` closes that scope when
            // the crossing returns. Nothing reports upward: a parent reaches down through its membership.
            ContextEffect.handle(Tag[Scope])(
                derive = (outer: Maybe[Finalizer]) =>
                    outer.foreach(_.addChild(finalizer))
                    finalizer
                ,
                fork = (parent: Finalizer) => parent.newChild(),
                join = (parent: Finalizer, _: Finalizer, child: Finalizer) =>
                    // The crossing returned, so the child's extent is over and what it acquired is released
                    // now rather than at the end of the scope it left. It stays in membership until that
                    // release finishes, so a parent closing meanwhile still waits for it.
                    import AllowUnsafe.embrace.danger
                    child.closeUnsafe(Absent)
                    parent
            )(v)
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

    /** A node in the scope hierarchy: the finalizers registered against one scope, and the scopes derived or
      * forked from it.
      *
      * A scope's children are the scopes born under it, and closing one closes its children first and does not
      * run its own finalizers until they have finished. That is what makes a scope's release cover everything
      * that ran inside it rather than only what it registered directly.
      */
    sealed abstract class Finalizer:
        def ensure(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Sync

        /** Registers a finalizer without suspending, for the one caller that cannot afford a suspension.
          *
          * `acquireRelease` has to record the release in the same step the acquire's value arrives in, because a
          * suspension there could be parked by an interrupt and the acquired value would never be released. Every
          * other caller wants [[ensure]].
          */
        private[kyo] def ensureUnsafe(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame, AllowUnsafe): Unit

        /** Takes a scope born under this one into membership. Idempotent: the same child added twice is one member. */
        private[kyo] def addChild(child: Finalizer)(using AllowUnsafe): Unit

        /** Drops a child whose release has finished, so a scope that outlives many of them does not accumulate. */
        private[kyo] def removeChild(child: Finalizer)(using AllowUnsafe): Unit

        /** Builds the scope for a computation crossing an isolation boundary, already a member of this one. */
        private[kyo] def newChild()(using Frame, AllowUnsafe): Finalizer

        /** Runs `f` once this scope has finished releasing. */
        private[kyo] def onClosed(f: () => Unit)(using AllowUnsafe): Unit

        /** Closes this scope without waiting, for the caller that has no effect context to wait in. */
        private[kyo] def closeUnsafe(ex: Maybe[Error[Any]])(using Frame, AllowUnsafe): Unit

        /** Closes this scope: children first, then this scope's own finalizers once they have finished. */
        def close(ex: Maybe[Error[Any]])(using Frame): Unit < Sync

        /** Completes when this scope and everything under it has finished releasing. */
        def await(using Frame): Unit < Async
    end Finalizer

    object Finalizer:

        object Unsafe:
            def init(parallelism: Int)(using frame: Frame, u: AllowUnsafe): Finalizer =
                new Finalizer:
                    val queue = Queue.Unbounded.Unsafe.init[Maybe[Error[Any]] => Any < (Async & Abort[Throwable])](
                        Access.MultiProducerSingleConsumer
                    )
                    val promise = Promise.Unsafe.init[Unit, Any]().safe

                    // Membership. A set rather than a count, because closing reaches down through it and a
                    // count could only be waited on. Removal is why it is not the finalizer queue.
                    val children = ConcurrentHashMap.newKeySet[Finalizer]()

                    def ensure(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Sync =
                        Sync.Unsafe.defer {
                            if !queue.offer(v).contains(true) then Abort.panic(closed)
                            else ()
                        }

                    private[kyo] def ensureUnsafe(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(
                        using
                        Frame,
                        AllowUnsafe
                    ): Unit =
                        if !queue.offer(v).contains(true) then throw closed

                    private[kyo] def addChild(child: Finalizer)(using AllowUnsafe): Unit =
                        discard(children.add(child))

                    private[kyo] def removeChild(child: Finalizer)(using AllowUnsafe): Unit =
                        discard(children.remove(child))

                    private[kyo] def newChild()(using Frame, AllowUnsafe): Finalizer =
                        val child = Finalizer.Unsafe.init(parallelism)
                        addChild(child)
                        // Membership ends when the child has finished releasing, not when its extent ends. A
                        // child dropped at the end of its extent would still be running its finalizers, and
                        // this scope would stop waiting for releases that had not happened.
                        child.onClosed(() => discard(children.remove(child)))
                        child
                    end newChild

                    private[kyo] def onClosed(f: () => Unit)(using AllowUnsafe): Unit =
                        promise.unsafe.onComplete(_ => f())

                    private[kyo] def closeUnsafe(ex: Maybe[Error[Any]])(using Frame, AllowUnsafe): Unit =
                        Sync.Unsafe.evalOrThrow(close(ex))

                    private def closed(using Frame) =
                        new Closed(
                            "Finalizer",
                            frame,
                            "This finalizer is already closed. This may happen if a background fiber escapes the scope of a 'Scope.run' call."
                        )

                    def close(ex: Maybe[Error[Any]])(using Frame): Unit < Sync =
                        Sync.Unsafe.defer {
                            queue.close() match
                                case Absent         => ()
                                case Present(tasks) =>
                                    // Snapshot before closing: a child linked after this point belongs to a
                                    // computation that escaped, and its own registration already fails.
                                    val kids = Chunk.from(children.toArray(Array.empty[Finalizer]))
                                    // Children first and their releases awaited, so this scope never releases
                                    // something a scope under it may still hold. Own finalizers stay LIFO.
                                    Kyo.foreachDiscard(kids)(_.close(ex))
                                        .andThen(Kyo.foreachDiscard(kids)(_.await))
                                        .andThen {
                                            if tasks.isEmpty then Kyo.unit
                                            else
                                                Async.foreachDiscard(tasks.reverse, parallelism) { task =>
                                                    Abort.run[Throwable](task(ex))
                                                        .map(_.foldError(
                                                            _ => (),
                                                            ex => Log.error("Scope finalizer failed", ex.exception)
                                                        ))
                                                }
                                        }
                                        .handle(Fiber.initUnscoped[Nothing, Unit, Any, Any])
                                        .map(promise.becomeDiscard)
                        }

                    def await(using Frame): Unit < Async = promise.get
            end init
        end Unsafe
    end Finalizer

end Scope
