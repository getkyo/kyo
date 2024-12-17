package kyo

import kyo.Tag
import kyo.kernel.*
import kyo.kernel.internal.Safepoint

/** Represents an IO effect for handling side effects in a pure functional manner.
  *
  * IO allows you to encapsulate and manage side-effecting operations (such as file I/O, network calls, or mutable state modifications)
  * within a purely functional context. This enables better reasoning about effects and helps maintain referential transparency.
  *
  * Like Async includes IO, this effect includes Abort[Nothing] to represent potential panics (untracked, unexpected exceptions). IO is
  * implemented as a type-level marker rather than a full ArrowEffect for performance. Since Effect.defer is only evaluated by the Pending
  * type's "eval" method, which can only handle computations without pending effects, side effects are properly deferred. This ensures they
  * can only be executed after an IO.run call, even though it is a purely type-level operation.
  */
opaque type IO <: Abort[Nothing] = Abort[Nothing]

object IO:

    /** Creates a unit IO effect, representing a no-op side effect.
      *
      * @return
      *   A unit value wrapped in an IO effect.
      */
    inline def unit: Unit < IO = ()

    /** Suspends a potentially side-effecting computation in an IO effect.
      *
      * This method allows you to lift any computation (including those with side effects) into the IO context, deferring its execution
      * until the IO is run.
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
      *   The suspended computation wrapped in an IO effect.
      */
    inline def apply[A, S](inline f: Safepoint ?=> A < S)(using inline frame: Frame): A < (IO & S) =
        Effect.defer(f)

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
      * @tparam S
      *   Additional effects in the computation.
      * @return
      *   The result of the main computation, with the finalizer guaranteed to run.
      */
    def ensure[A, S](f: => Unit < IO)(v: A < S)(using frame: Frame): A < (IO & S) =
        Unsafe(Safepoint.ensure(IO.Unsafe.evalOrThrow(f))(v))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        inline def apply[A, S](inline f: AllowUnsafe ?=> A < S)(using inline frame: Frame): A < (IO & S) =
            Effect.defer {
                import AllowUnsafe.embrace.danger
                f
            }

        /** Evaluates an IO effect that may throw exceptions, converting any thrown exceptions into the final result.
          *
          * WARNING: This is a low-level API that should be used with caution. It forcefully evaluates the IO effect and will throw any
          * encountered exceptions rather than handling them in a purely functional way.
          *
          * @param v
          *   The IO effect to evaluate, which may contain throwable errors
          * @param frame
          *   Implicit frame for the computation
          * @return
          *   The result of evaluating the IO effect, throwing any encountered exceptions
          * @throws Throwable
          *   If the evaluation results in an error
          */
        def evalOrThrow[A: Flat](v: A < (IO & Abort[Throwable]))(using Frame, AllowUnsafe): A =
            Abort.run(v).eval.getOrThrow

        /** Runs an IO effect, evaluating it and its side effects.
          *
          * WARNING: This is a low-level, unsafe API. It should be used with caution and only when absolutely necessary. This method
          * executes the IO effect and any associated side effects right away, potentially breaking referential transparency and making it
          * harder to reason about the code's behavior.
          *
          * In most cases, prefer higher-level, safer APIs for managing IO effects.
          *
          * @param v
          *   The IO effect to run.
          * @param frame
          *   Implicit frame for the computation.
          * @tparam A
          *   The result type of the IO effect.
          * @tparam S
          *   Additional effects in the computation.
          * @return
          *   The result of the IO effect after executing its side effects.
          */
        def run[E, A: Flat, S](v: => A < (IO & Abort[E] & S))(using Frame, AllowUnsafe): A < (S & Abort[E]) =
            v

    end Unsafe
end IO
