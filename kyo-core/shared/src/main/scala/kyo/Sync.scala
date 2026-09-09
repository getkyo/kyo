package kyo

import kyo.Result.Error
import kyo.Result.flatten
import kyo.kernel.*
import kyo.kernel.internal.Safepoint

/** Pure suspension of side effects.
  *
  * Unlike traditional monadic Sync types that combine effect suspension and async execution, Kyo leverages algebraic effects to cleanly
  * separate these concerns. Sync focuses solely on suspending side effects, while async execution (fibers, scheduling) is handled by the
  * Async effect.
  *
  * This separation enables an important design principle in Kyo's codebase: methods that only declare Sync in their pending effects are run
  * to completion without parking or locking. This property, combined with Kyo's lock-free primitives, makes it easier to reason about
  * performance characteristics and identify potential async operations in the code.
  *
  * Sync is implemented as a type-level marker rather than a full ArrowEffect for performance. Since Effect.defer is only evaluated by the
  * Pending type's "eval" method, which can only handle computations without pending effects, side effects are properly deferred. This
  * ensures they can only be executed after an Sync.run call, even though it is a purely type-level operation.
  *
  * Like Async includes Sync, this effect includes Abort[Nothing] to represent potential panics (untracked, unexpected exceptions).
  */
opaque type Sync <: Abort[Nothing] = Abort[Nothing]

object Sync:

    /** Suspends a potentially side-effecting computation in an Sync effect.
      *
      * This method allows you to lift any computation (including those with side effects) into the Sync context, deferring its execution
      * until the Sync is run.
      *
      * @param f
      *   The computation to suspend, potentially containing side effects.
      * @param frame
      *   Implicit frame for the computation.
      * @tparam A
      *   The result type of the computation.
      * @tparam S
      *   Additional effects in the computation.
      * @return
      *   The suspended computation wrapped in an Sync effect.
      */
    inline def defer[A, S](inline f: => A < S)(using inline frame: Frame): A < (Sync & S) =
        Effect.deferInline(f)

    /** Ensures that a finalizer is run after the main computation, regardless of success or failure.
      *
      * This is useful for resource management, allowing you to specify cleanup actions that should always occur, such as closing file
      * handles or network connections.
      *
      * @param f
      *   The finalizer to run, typically containing cleanup side effects.
      * @param v
      *   The main computation.
      * @param frame
      *   Implicit frame for the computation.
      * @tparam A
      *   The result type of the main computation.
      */
    inline def ensure[A, S](inline f: => Any < (Sync & Abort[Throwable]))(v: => A < S)(using inline frame: Frame): A < (Sync & S) =
        ensure(_ => f)(v)

    /** Acquires a resource, uses it, and releases it after use.
      *
      * This is a lightweight bracket-style operator for Sync resources. The release action is registered only after acquisition succeeds
      * and is guaranteed to run after the use computation completes or panics.
      *
      * @param acquire
      *   The resource acquisition computation.
      * @param release
      *   The release action for a successfully acquired resource.
      * @param use
      *   The computation that uses the acquired resource.
      * @return
      *   The result of the use computation.
      */
    def acquireReleaseWith[A, S1](acquire: => A < (Sync & S1))(
        release: (A, Result[Any, Any]) => Any < (Sync & Abort[Throwable])
    )[B, E, S2](use: A => B < (Abort[E] & S2))(using ConcreteTag[E], Frame): B < (Sync & S1 & Abort[E] & S2) =
        // the one bracket in this file, which every other ensure and acquireReleaseWith lands on. The
        // kernel bracket owns the exactly-once guarantee and tells the release how the extent ended: the
        // failure an unwind carried through it, the signal that the remainder holding it was discarded,
        // or Absent for an ending that ran to completion. An abort is none of those to the kernel, which
        // does not know Abort, so the use runs under its own Abort region and this method routes the
        // failure to the release itself, raising it again past the bracket, typed as it came.
        //
        // First failure wins, because a handler that replays ends the extent once per resumption: a
        // branch that aborted must not be overwritten by a later branch that succeeded, or a release
        // that commits on success would commit over it.
        Sync.Unsafe.defer {
            val aborted = AtomicRef.Unsafe.init[Maybe[Result.Error[Any]]](Absent)(using AllowUnsafe.embrace.danger)
            // Unsafe: the kernel's release is synchronous, so the effectful release runs to completion here,
            // and only its own Abort surfaces, as a throw
            Bracket(acquire) { resource =>
                Abort.runWith[E](use(resource)) { result =>
                    result.foldError(
                        _ => (),
                        e => discard(aborted.compareAndSet(Absent, Maybe(e))(using AllowUnsafe.embrace.danger))
                    )
                    result
                }
            } { (resource, failure) =>
                val outcome: Result[Any, Any] =
                    failure match
                        // constructed rather than built through `Result.Panic.apply`, which refuses to hold a fatal:
                        // the release is owed the failure that ended its extent whatever it is
                        case Present(ex) => new Result.Panic(ex)
                        case Absent      => aborted.get()(using AllowUnsafe.embrace.danger).getOrElse(Result.unit)
                discard(Sync.Unsafe.evalOrThrow(release(resource, outcome))(using summon[Frame], AllowUnsafe.embrace.danger))
            }.map(result => Abort.get(result))
        }
    end acquireReleaseWith

    def acquireReleaseWith[A, S1](acquire: => A < (Sync & S1))(
        release: A => Any < (Sync & Abort[Throwable])
    )[B, E, S2](use: A => B < (Abort[E] & S2))(using ConcreteTag[E], Frame): B < (Sync & S1 & Abort[E] & S2) =
        acquireReleaseWith(acquire)((resource, _) => release(resource))(use)

    /** Ensures that a finalizer is run after the computation, regardless of success or failure.
      *
      * This version provides the finalizer with information about how the computation ended. The finalizer receives a
      * `Maybe[Error[Any]]`: `Absent` when the computation completed, the `Failure` when it aborted, and a `Panic` when it threw or when
      * its extent was ended from outside, as a scheduler does when it abandons a parked remainder.
      *
      * @param f
      *   The finalizer function that receives information about potential errors and performs cleanup actions.
      * @param v
      *   The computation.
      * @param frame
      *   Implicit frame for the computation.
      * @tparam A
      *   The result type of the computation.
      * @tparam S
      *   Additional effects in the computation.
      * @return
      *   The result of the computation, with the finalizer guaranteed to run.
      */
    // `f` is intentionally not inline. Under Scala 3.8.4 an inlined pure-value finalizer body (a
    // Unit-returning side effect) is inferred as the unfolded `Unit | Kyo[Unit, Any]` union, which no
    // longer conforms to the opaque `Any < (Sync & Abort[Throwable])`. As a non-inline function value
    // the body adapts to the opaque type at the call site; the cost is one finalizer-closure
    // allocation. Restore inline once the upstream inference regression is resolved.
    inline def ensure[A, E, S](f: Maybe[Error[Any]] => Any < (Sync & Abort[Throwable]))(v: => A < (Abort[E] & S))(using
        ct: ConcreteTag[E],
        inline frame: Frame
    ): A < (Sync & Abort[E] & S) =
        // `Bracket.ensuring` rather than a bracket over a `()` acquire, because there is a difference between
        // the two that this method needs. A bracket cannot install its region until the acquire's value
        // arrives, so a computation abandoned before it ever ran has no region and the finalizer does not
        // run: right for a bracket, since nothing was acquired, and wrong here, where the caller asked for
        // cleanup that always occurs and may well be closing over something acquired outside. `ensuring`
        // installs the region as a node, which the abandonment walk finds whether or not a step ever ran.
        //
        // The abort routing below is the same as `acquireReleaseWith`'s and is here for the same reason: the
        // kernel does not know `Abort`, so without it the finalizer would still run on a typed abort but
        // would be told the discard signal rather than the failure the caller raised.
        //
        // The finalizer has exactly one call site, the release, because that is the only place that knows
        // whether this ending fires now or is held: under a handler that replays, an ending only records
        // and the release runs once after every branch. Calling the finalizer from the body instead, where
        // the outcome is already in hand, closes the resource at the first branch's ending and the branches
        // after it run against a resource that is gone. So the body leaves the failure here and the release
        // reads it, rather than calling the finalizer itself.
        //
        // First failure wins, because a handler that replays ends the extent once per resumption: a branch
        // that aborted must not be overwritten by a later branch that succeeded, or a release that commits
        // on success would commit over it.
        Sync.Unsafe.defer {
            val aborted = AtomicRef.Unsafe.init[Maybe[Result.Error[Any]]](Absent)(using AllowUnsafe.embrace.danger)
            Bracket.ensuring { failure =>
                val outcome: Maybe[Result.Error[Any]] =
                    failure match
                        // constructed rather than through `Result.Panic.apply`, which refuses to hold a fatal
                        case Present(ex) => Present(new Result.Panic(ex))
                        case Absent      => aborted.get()(using AllowUnsafe.embrace.danger)
                // Unsafe: the kernel's release is synchronous, so the effectful finalizer runs to completion here
                discard(Sync.Unsafe.evalOrThrow(f(outcome))(using summon[Frame], AllowUnsafe.embrace.danger))
            } {
                Abort.run[E](v).map { result =>
                    result.foldError(
                        _ => (),
                        e => discard(aborted.compareAndSet(Absent, Maybe(e))(using AllowUnsafe.embrace.danger))
                    )
                    result
                }
            }.map(result => Abort.get(result))
        }
    end ensure

    /** Retrieves a local value and applies a function that can perform side effects.
      *
      * This is the preferred way to access a local value when you need to perform side effects with it. Common use cases include accessing
      * loggers, configuration, or request-scoped values that you need to use in computations that produce side effects.
      *
      * While `local.get.map(v => Sync.defer(f(v)))` would also work, this method is more direct since both Sync and Local use the same
      * underlying mechanism to handle effects. Under the hood, accessing a local value and performing Sync operations both use the same
      * type of suspension, the kernel's internal `Defer` effect. This means we can safely combine them without creating unnecessary layers
      * of suspension.
      *
      * @param local
      *   The local value to access
      * @param f
      *   Function that can perform side effects with the local value
      * @return
      *   An Sync effect containing the result of applying the function
      */
    def withLocal[A, B, S](local: Local[A])(f: A => B < S)(using Frame): B < (S & Sync) =
        local.use(f)

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        inline def defer[A, S](inline f: AllowUnsafe ?=> A < S)(using inline frame: Frame): A < (Sync & S) =
            Effect.deferInline {
                f(using AllowUnsafe.embrace.danger)
            }

        inline def ensure[A, S](inline f: AllowUnsafe ?=> Any < (Sync & Abort[Throwable]))(v: => A < S)(using
            inline frame: Frame
        ): A < (Sync & S) =
            Sync.ensure(f(using AllowUnsafe.embrace.danger))(v)

        def withLocal[A, B, S](local: Local[A])(f: AllowUnsafe ?=> A => B < S)(using Frame): B < (S & Sync) =
            local.use(f(using AllowUnsafe.embrace.danger))

        /** Evaluates an Sync effect that may throw exceptions, converting any thrown exceptions into the final result.
          *
          * WARNING: This is a low-level API that should be used with caution. It forcefully evaluates the Sync effect and will throw any
          * encountered exceptions rather than handling them in a purely functional way.
          *
          * @param v
          *   The Sync effect to evaluate, which may contain throwable errors
          * @param frame
          *   Implicit frame for the computation
          * @return
          *   The result of evaluating the Sync effect, throwing any encountered exceptions
          * @throws Throwable
          *   If the evaluation results in an error
          */
        def evalOrThrow[A](v: A < (Sync & Abort[Throwable]))(using Frame, AllowUnsafe): A =
            Abort.run(v).eval.getOrThrow

        /** Runs an Sync effect, evaluating it and its side effects.
          *
          * WARNING: This is a low-level, unsafe API. It should be used with caution and only when absolutely necessary. This method
          * executes the Sync effect and any associated side effects right away, potentially breaking referential transparency and making it
          * harder to reason about the code's behavior.
          *
          * In most cases, prefer higher-level, safer APIs for managing Sync effects.
          *
          * @param v
          *   The Sync effect to run.
          * @param frame
          *   Implicit frame for the computation.
          * @tparam A
          *   The result type of the Sync effect.
          * @tparam S
          *   Additional effects in the computation.
          * @return
          *   The result of the Sync effect after executing its side effects.
          */
        def run[E, A, S](v: => A < (Sync & Abort[E] & S))(using Frame, AllowUnsafe): A < (S & Abort[E]) =
            v

    end Unsafe
end Sync
