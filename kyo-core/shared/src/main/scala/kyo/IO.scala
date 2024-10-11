package kyo

import kyo.Tag
import kyo.kernel.*

/** Represents an IO effect for handling side effects in a pure functional manner.
  *
  * IO allows you to encapsulate and manage side-effecting operations (such as file I/O, network calls, or mutable state modifications)
  * within a purely functional context. This enables better reasoning about effects and helps maintain referential transparency.
  */
sealed trait IO extends ArrowEffect[Const[Unit], Const[Unit]]

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
        ArrowEffect.suspendMap[Any](Tag[IO], ())(_ => f)

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
    inline def ensure[A, S](inline f: => Unit < IO)(v: A < S)(using inline frame: Frame): A < (IO & S) =
        Unsafe(Safepoint.ensure(IO.Unsafe.run(f).eval)(v))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        inline def apply[A, S](inline f: AllowUnsafe ?=> A < S)(using inline frame: Frame): A < (IO & S) =
            import AllowUnsafe.embrace.danger
            ArrowEffect.suspendMap[Any](Tag[IO], ())(_ => f)

        /** Runs an IO effect, evaluating it and its side effects immediately.
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
        def run[A: Flat, S](v: => A < IO)(using Frame, AllowUnsafe): A < Any =
            runLazy(v)

        /** Runs an IO effect lazily, only evaluating when needed.
          *
          * WARNING: This is an extremely low-level and unsafe API. It should be used with great caution and only in very specific scenarios
          * where fine-grained control over IO effect execution is absolutely necessary.
          *
          * This method allows for delayed execution of the IO effect and its side effects, which can lead to unpredictable behavior if not
          * managed carefully. It bypasses many of the safety guarantees provided by the IO abstraction.
          *
          * In most cases, prefer higher-level, safer APIs for managing IO effects.
          *
          * @param v
          *   The IO effect to run lazily.
          * @param frame
          *   Implicit frame for the computation.
          * @tparam A
          *   The result type of the IO effect.
          * @tparam S
          *   Additional effects in the computation.
          * @return
          *   The result of the IO effect, evaluated lazily along with its side effects.
          */
        def runLazy[A: Flat, S](v: => A < (IO & S))(using Frame, AllowUnsafe): A < S =
            ArrowEffect.handle(Tag[IO], v) {
                [C] => (_, cont) => cont(())
            }
    end Unsafe
end IO
