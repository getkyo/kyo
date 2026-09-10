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
            // A scope closes at the end of the `Scope.run` that opened it, and nowhere else.
            //
            // A nested run joins as a child. An enclosing scope closes its children and waits for them before
            // releasing its own, which orders an inner resource's release before an outer one's.
            //
            // Closing rather than only waiting: a nested run blocked inside a handler closes when something
            // ends that handler, often a finalizer of this scope, so a queued wait could sit ahead of the
            // finalizer that would release it and wait on itself. Tolerantly, since the enclosing scope may
            // already be closed by a fiber that outlived it.
            //
            // A crossing shares this scope rather than getting its own, so a resource's lifetime does not
            // depend on whether a combinator forked internally (pinned in StreamCoreExtensionsTest:890).
            // It does not share membership, which `forked` withholds: a run opened inside a fork is its own
            // root, because this scope does not end the fiber carrying it. Closing it from here would take a
            // resource from an owner still using it.
            ContextEffect.handle(Tag[Scope])(
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
                // Whichever close reaches the queue first is the one whose error the finalizers see, and the
                // backstop carries only what an ending carries: nothing on a normal return, and a synthesised
                // "fiber abandoned" panic for a typed abort. So the abort is caught first, turning the ending
                // into a value; the close below runs with the real error in hand; and the backstop answers
                // only for an abandonment, the one ending that never reaches the close below.
                .handle(Abort.run[Any])
                .map { result =>
                    finalizer
                        .close(result.error)
                        .andThen(finalizer.await)
                        .andThen(Abort.get(result.asInstanceOf[Result[Nothing, A]]))
                }
                .handle(Sync.ensure(finalizer.close))
        }

    /** The finalizers registered against one scope, run in reverse registration order when it closes.
      *
      * A run nested inside another joins it as a child through [[addChild]], and closing a scope closes its
      * children and waits for them before releasing anything of its own, so an inner resource's release comes
      * before an outer one's. What a fork carries is registration without membership; see [[forked]].
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

        /** Registers `v` if this scope is still open, and does nothing if it is not.
          *
          * [[ensureUnsafe]] raises on a closed scope because a release that cannot be registered is a resource
          * that will never be freed. This is for the registration where failing is not a leak: a nested run
          * asking to be waited for. If the enclosing scope has already closed there is nobody left to wait, and
          * the nested run still closes itself. That happens whenever a fiber outlives the scope it captured,
          * which is the case the raised message describes.
          */
        private[kyo] def ensureIfOpen(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame, AllowUnsafe): Unit

        /** Records a run nested in this one, as the scope it opened rather than as a wait for it.
          *
          * The difference is what closing can do about it. A wait can only be satisfied by the nested run closing
          * itself, which needs the computation carrying it to end, and that computation is often ended by one of
          * THIS scope's finalizers: reverse order can put the wait ahead of the finalizer that would end it, and
          * the close waits on itself. Holding the child's finalizer instead lets this scope close it, which runs
          * the child's own finalizers and settles it whether or not its computation ever gets to finish.
          */
        private[kyo] def addChild(child: Finalizer)(using Frame, AllowUnsafe): Unit

        /** This scope as a forked computation sees it: registrations still land here, and a run opened inside the
          * fork is a root rather than a child of this one.
          *
          * A resource acquired in a fork belongs to the scope the fork was made in, so registration is shared. What
          * is not shared is membership: a fork carries a fiber this scope does not end, so a run opened there can
          * still be live after this one closes, and closing it from here would release what its owner is using.
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

        /** One scope seen from inside a fork: everything delegates, and only [[Finalizer.addChild]] does not.
          * See [[Finalizer.forked]] for why membership is the one thing a crossing withholds.
          */
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

                    // Uninterruptible, because `close` hands the drain to a fiber and `become`s this promise with it, and
                    // `await` is what a caller parks on. An interrupt landing on that caller would otherwise
                    // travel through the promise into the drain and stop the finalizers halfway, which loses
                    // exactly the releases the interrupt was supposed to trigger (#1928). Interrupting a scope's
                    // cleanup is never what an interrupt means.
                    val promise = Promise.Unsafe.initUninterruptible[Unit, Any]().safe

                    // Delegates rather than repeating the offer, so a closed scope answers both registration
                    // paths alike: the finalizer runs, and the caller still learns it is not scoped. The throw
                    // becomes this computation's panic.
                    def ensure(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): Unit < Sync =
                        Sync.Unsafe.defer(ensureUnsafe(v))

                    private[kyo] def ensureUnsafe(v: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(
                        using
                        frame: Frame,
                        allow: AllowUnsafe
                    ): Unit =
                        if !queue.offer(v).contains(true) then
                            // The scope has already closed, so no later drain would run this release and throwing
                            // alone would leak what the caller holds. It runs here, detached, since this is not an
                            // effectful position; the throw below still tells the caller its resource is unscoped.
                            // Logged because a finalizer running off its scope is invisible from the outside.
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

                    def close(ex: Maybe[Error[Any]])(using Frame): Unit < Sync =
                        Sync.Unsafe.defer {
                            // The handover is asynchronous because an `ensure` that began before this close may
                            // still be committing its task. Nothing waits for it here: registering a continuation
                            // keeps this `Sync`, which both of `run`'s close paths need, the panic path included.
                            queue.close().safe.onComplete { backlog =>
                                backlog.foldError(
                                    _.map {
                                        case Absent         => Kyo.unit
                                        case Present(tasks) =>
                                            // Nested runs are closed and waited for first, so their resources release
                                            // before this scope's own. Closing rather than waiting keeps a child whose
                                            // computation is blocked from holding this close open. See `addChild`.
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
                                            nested.andThen(own)
                                                .handle(Fiber.initUnscoped[Nothing, Unit, Any, Any])
                                                .map(promise.becomeDiscard)
                                    },
                                    // The backlog handover is completed with a success by whoever wins the drain, so this is
                                    // unreachable; leaving `promise` alone lets `await` surface the real failure.
                                    _ => Kyo.unit
                                )
                            }
                        }

                    def await(using Frame): Unit < Async = promise.get
            end init
        end Unsafe

    end Finalizer

end Scope
