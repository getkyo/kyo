package kyo

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
      * The release is registered in the step that delivers the acquired value, so a stop pending at that step cannot separate the two.
      * That covers an acquire that settles in one step or whose last step produces the value. An acquire that joins a fiber or a
      * promise for its value is not covered: a stop landing after the join completed and before this fiber resumed abandons the value
      * with the release unregistered. Register the release in the producing step instead, inside the fiber that produces the value or
      * through `ensureMap` on it.
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
            // `ensureMap` registers in the step the acquire completes; with `map` the registration is a suspension
            // of its own, and a pending interrupt parks before it is dispatched, leaving the abandonment nothing to release.
            Sync.defer(acquire).ensureMap { resource =>
                // Unsafe: registering as an effect would put the registration in a step of its own.
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
            // A scope closes at the end of the `Scope.run` that opened it and nowhere else. See `Finalizer` for how a
            // nested run joins as a child, why children are closed rather than only waited for, and why a fork is a
            // root. `StreamCoreExtensionsTest:890` pins that a resource's lifetime does not depend on whether a
            // combinator forked.
            ContextEffect.handle(
                Tag[Scope],
                derive = (outer: Maybe[Finalizer]) =>
                    outer.foreach { enclosing =>
                        import AllowUnsafe.embrace.danger
                        enclosing.addChild(finalizer)
                    }
                    finalizer
                ,
                fork = (parent: Finalizer) => parent.forked,
                join = (parent: Finalizer, _: Finalizer, _: Finalizer) => parent
            )(v)
                // The first close to reach the queue is the one whose error the finalizers see. The `Sync.ensure`
                // backstop carries only what an ending carries (nothing on a normal return, an abandonment panic for a
                // typed abort), so catching the abort first lets the close run with the real error and the backstop
                // answer only for abandonment.
                .handle(Abort.run[Any])
                .map { result =>
                    finalizer
                        .close(result.error)
                        .andThen(finalizer.await)
                        .andThen(Abort.get(result.asInstanceOf[Result[Nothing, A]]))
                }
                .handle(Sync.ensure(finalizer.close))
        }

    /** The finalizers registered against one scope, run in reverse registration order when it closes. A nested run
      * joins as a child through [[addChild]], so inner resources release before outer. A fork shares registration but
      * not membership; see [[forked]].
      */
    sealed abstract class Finalizer:
        def ensure(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Sync

        /** Registers a finalizer without suspending, for `acquireRelease`: a suspension in the step the acquire's value
          * arrives could be parked by an interrupt and leave the value unreleased. Every other caller wants [[ensure]].
          */
        private[kyo] def ensureUnsafe(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame, AllowUnsafe): Unit

        /** Registers `v` if this scope is open, else does nothing. Failing is safe here, unlike [[ensureUnsafe]] which
          * raises on a leak: the caller is a nested run asking to be waited for, and if this scope has closed there is
          * nobody to wait and the run still closes itself.
          */
        private[kyo] def ensureIfOpen(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame, AllowUnsafe): Unit

        /** Records a nested run as the scope it opened, not a wait. A wait would deadlock: it completes only when the
          * nested run's computation ends, but that is often ended by one of THIS scope's finalizers, which reverse
          * order runs after the wait. Holding the child's finalizer lets this scope close it directly.
          */
        private[kyo] def addChild(child: Finalizer)(using Frame, AllowUnsafe): Unit

        /** This scope as a fork sees it: registrations still land here (a resource acquired in a fork belongs to the
          * scope it was made in), but a run opened inside is a root, not a child, since the fork carries a fiber this
          * scope does not end, so that run can outlive this one and closing it here would release what its owner uses.
          */
        private[kyo] def forked: Finalizer

        /** Closes this scope: closes the runs nested in it and waits for them, then releases its own resources in
          * reverse registration order.
          */
        def close(ex: Maybe[Error[Any]])(using Frame): Unit < Sync

        /** Completes when this scope has finished releasing. */
        def await(using Frame): Unit < Async
    end Finalizer

    object Finalizer:

        /** One scope seen from inside a fork: everything delegates except [[Finalizer.addChild]]; see [[Finalizer.forked]]. */
        final private class Forked(origin: Finalizer) extends Finalizer:
            def ensure(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Sync =
                origin.ensure(v)

            private[kyo] def ensureUnsafe(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(
                using
                Frame,
                AllowUnsafe
            ): Unit =
                origin.ensureUnsafe(v)

            private[kyo] def ensureIfOpen(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(
                using
                Frame,
                AllowUnsafe
            ): Unit =
                origin.ensureIfOpen(v)

            private[kyo] def addChild(child: Finalizer)(using Frame, AllowUnsafe): Unit = ()

            private[kyo] def forked: Finalizer = this

            def close(ex: Maybe[Error[Any]])(using Frame): Unit < Sync = origin.close(ex)

            def await(using Frame): Unit < Async = origin.await
        end Forked

        object Unsafe:
            def init(parallelism: Int)(using frame: Frame, u: AllowUnsafe): Finalizer =
                new Finalizer:
                    val queue = Queue.Unbounded.Unsafe.init[Maybe[Error[Any]] => Any < (Async & Abort[Throwable])](
                        Access.MultiProducerSingleConsumer
                    )
                    val children = Queue.Unbounded.Unsafe.init[Finalizer](Access.MultiProducerSingleConsumer)

                    // Uninterruptible: `close` `become`s this promise with the drain's fiber, so an interrupt at a
                    // caller's `await` would travel into the drain and stop the finalizers halfway (#1928).
                    val promise = Promise.Unsafe.initUninterruptible[Unit, Any]().safe

                    // The throw from `ensureUnsafe` on a closed scope becomes this computation's panic.
                    def ensure(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Sync =
                        Sync.Unsafe.defer(ensureUnsafe(v))

                    private[kyo] def ensureUnsafe(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(
                        using
                        frame: Frame,
                        allow: AllowUnsafe
                    ): Unit =
                        if !queue.offer(v).contains(true) then
                            // The scope already closed, so no later drain runs this release: it runs here, detached,
                            // or the resource leaks. The throw tells the caller its resource is unscoped.
                            Log.live.unsafe.warn(
                                s"Scope: a finalizer was registered on a closed scope at ${frame.position.show}, running it detached"
                            )
                            discard(Fiber.Unsafe.init {
                                Abort.recoverError[Throwable](error =>
                                    Log.error("Scope finalizer failed", error.exception)
                                )(v(Present(Result.Panic(closed))))
                            })
                            throw closed
                        end if
                    end ensureUnsafe

                    private[kyo] def ensureIfOpen(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(
                        using
                        Frame,
                        AllowUnsafe
                    ): Unit =
                        discard(queue.offer(v))

                    private[kyo] def addChild(child: Finalizer)(using Frame, AllowUnsafe): Unit =
                        discard(children.offer(child))

                    private[kyo] val forked: Finalizer = new Forked(this)

                    private def closed(using Frame) =
                        new Closed(
                            "Finalizer",
                            frame,
                            "This finalizer is already closed. This may happen if a background fiber escapes the scope of a 'Scope.run' call."
                        )

                    /** The claim on the queue's backlog and the drain of it are one detached fiber, spawned as this close's only
                      * step. Claiming here and draining in a continuation would leave a window between the two steps: an interrupt
                      * honored there abandons the continuation with the backlog already claimed, and the close that runs from the
                      * abandonment finds it claimed and rightly leaves it alone, so the finalizers in it never run (#1928). Inside
                      * the fiber the handover is awaited rather than continued, because an `ensure` that began before this close may
                      * still be committing its task. The drain that wins the claim completes `promise` once it has released; one that
                      * loses owns nothing and completes nothing. Spawning keeps this `Sync`, which both of `run`'s close paths need.
                      */
                    def close(ex: Maybe[Error[Any]])(using Frame): Unit < Sync =
                        Fiber.initUnscoped[Nothing, Unit, Any, Any] {
                            Sync.Unsafe.defer(queue.close().safe.get).map {
                                case Absent         => Kyo.unit
                                case Present(tasks) =>
                                    // Children close and are waited for before this scope's own releases run;
                                    // closing, not just waiting, frees a child whose computation is blocked (see `addChild`).
                                    val nested =
                                        Sync.Unsafe.defer(children.close()).map(_.safe.get).map {
                                            case Present(cs) =>
                                                Async.foreachDiscard(cs) { child =>
                                                    child.close(ex).andThen(child.await)
                                                }
                                            case Absent => Kyo.unit
                                        }
                                    val own =
                                        if tasks.isEmpty then Kyo.unit
                                        else
                                            Async.foreachDiscard(tasks.reverse, parallelism) { task =>
                                                Abort.run[Throwable](task(ex))
                                                    .map(_.foldError(
                                                        _ => (),
                                                        ex => Log.error("Scope finalizer failed", ex.exception)
                                                    ))
                                            }
                                    // Completed whatever the drain met, so a waiter is never left at a scope that has finished releasing.
                                    Abort.run[Throwable](nested.andThen(own))
                                        .map(_.foldError(_ => (), ex => Log.error("Scope close failed", ex.exception)))
                                        .andThen(promise.completeUnitDiscard)
                            }
                        }.unit

                    def await(using Frame): Unit < Async = promise.get
            end init
        end Unsafe

    end Finalizer

end Scope
