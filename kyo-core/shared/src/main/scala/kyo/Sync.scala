package kyo

import kyo.Result.Error
import kyo.Tag
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

@deprecated("use `Sync`", "0.19.1")
type IO = Sync

@deprecated("use `Sync`", "0.19.1")
val IO = Sync

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
    inline def apply[A, S](inline f: Safepoint ?=> A < S)(using inline frame: Frame): A < (Sync & S) =
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
    inline def ensure[A, S](inline f: => Any < Sync)(v: => A < S)(using inline frame: Frame): A < (Sync & S) =
        ensure(_ => f)(v)

    /** Ensures that a finalizer is run after the computation, regardless of success or failure.
      *
      * This version provides the finalizer with information about whether the computation completed successfully or failed with an
      * exception. The finalizer receives a `Maybe[Error[Any]]` which will be `Absent` if the computation succeeded, or `Present` if it
      * failed.
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
    inline def ensure[A, S](inline f: Maybe[Error[Any]] => Any < Sync)(v: => A < S)(using inline frame: Frame): A < (Sync & S) =
        Unsafe(Safepoint.ensure(ex => Sync.Unsafe.evalOrThrow(f(ex)))(v))

    /** Retrieves a local value and applies a function that can perform side effects.
      *
      * This is the preferred way to access a local value when you need to perform side effects with it. Common use cases include accessing
      * loggers, configuration, or request-scoped values that you need to use in computations that produce side effects.
      *
      * While `local.get.map(v => Sync(f(v)))` would also work, this method is more direct since both Sync and Local use the same underlying
      * mechanism to handle effects. Under the hood, accessing a local value and performing Sync operations both use the same type of
      * suspension, the kernel's internal `Defer` effect. This means we can safely combine them without creating unnecessary layers of
      * suspension.
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

        inline def apply[A, S](inline f: AllowUnsafe ?=> A < S)(using inline frame: Frame): A < (Sync & S) =
            Effect.deferInline {
                f(using AllowUnsafe.embrace.danger)
            }

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
