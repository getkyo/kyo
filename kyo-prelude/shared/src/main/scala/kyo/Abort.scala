package kyo

import kernel.ArrowEffect
import kyo.Result.*
import kyo.Tag
import kyo.kernel.Effect
import scala.annotation.targetName

/** The Abort effect represents early termination of computations with typed failure values.
  *
  * Abort provides a functional approach to error handling, allowing you to explicitly model failures in your type signatures and handle
  * them in a composable way. It serves as a more powerful alternative to exceptions that integrates with Kyo's effect system.
  *
  * A computation using Abort can complete in three distinct ways:
  *   - `Success[A]`: The computation completes successfully with a value of type A
  *   - `Failure[E]`: An expected business/domain failure with a meaningful error value of type E
  *   - `Panic(ex: Throwable)`: An unexpected exception, similar to unchecked exceptions
  *
  * Type unions allow expressing multiple possible error types: `Result < Abort[NetworkError | ValidationError]` indicates the operation
  * might fail with either a network error or validation error. Abort supports handling specific failure types within unions, allowing
  * selective recovery with methods like `run` and `recover` that can target precise error subtypes while preserving the rest of the union.
  * This enables granular error handling without losing type information.
  *
  * Abort is designed for modeling domain-specific errors as values rather than exceptions, making error handling explicit in your function
  * signatures. It's particularly valuable for validation scenarios, creating maintainable error recovery flows, and for integrating with
  * exception-based code via the `catching` methods that bridge traditional exception handling and Abort's functional approach.
  *
  * @tparam E
  *   The type of failure values that can be produced
  *
  * @see
  *   [[kyo.Abort.fail]], [[kyo.Abort.panic]], [[kyo.Abort.ensuring]] for creating failures
  * @see
  *   [[kyo.Abort.get]] for lifting Either, Option, Try and other types into Abort
  * @see
  *   [[kyo.Abort.run]], [[kyo.Abort.recover]], [[kyo.Abort.fold]] for handling failures
  * @see
  *   [[kyo.Abort.catching]] for integrating with exception-based code
  */
sealed trait Abort[-E] extends ArrowEffect[Const[Error[E]], Const[Unit]]

object Abort:

    import internal.*

    given eliminateAbort: Reducible.Eliminable[Abort[Nothing]] with {}
    private inline def erasedTag[E]: Tag[Abort[E]] = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    /** Fails the computation with the given value.
      *
      * @param value
      *   The failure value
      * @return
      *   A computation that immediately fails with the given value
      */
    inline def fail[E](inline value: E)(using inline frame: Frame): Nothing < Abort[E] =
        error(Failure(value))

    /** Fails the computation with a panic value (unchecked exception).
      *
      * @param ex
      *   The exception to use as a panic value
      * @return
      *   A computation that immediately fails with the given exception
      */
    inline def panic[E](inline ex: Throwable)(using inline frame: Frame): Nothing < Abort[E] =
        error(Panic(ex))

    inline def error[E](inline error: Error[E])(using inline frame: Frame): Nothing < Abort[E] =
        ArrowEffect.suspendWith[Any](erasedTag[E], error)(_ => ???)

    /** Fails the computation if the condition is true.
      *
      * @param b
      *   The condition to check
      * @param value
      *   The failure value to use if the condition is true
      * @return
      *   A unit computation that may fail if the condition is true
      */
    inline def when[E](b: Boolean)(inline value: => E)(using inline frame: Frame): Unit < Abort[E] =
        ensuring(!b, ())(value)

    /** Ensures a condition is met before returning the provided result.
      *
      * @param cond
      *   The condition to check
      * @param result
      *   The result to return if the condition is true
      * @param value
      *   The failure value to use if the condition is false
      * @tparam A
      *   The type of the result
      * @tparam E
      *   The type of the failure value
      * @return
      *   A computation that succeeds with the result if the condition is true, or fails with the given value if it's false
      */
    inline def ensuring[A, E](cond: Boolean, result: => A)(inline value: => E)(using inline frame: Frame): A < Abort[E] =
        if !cond then fail(value)
        else result

    final class GetOps[E >: Nothing](dummy: Unit) extends AnyVal:

        /** Lifts an Either into the Abort effect.
          *
          * @param either
          *   The Either to lift
          * @return
          *   A computation that succeeds with the Right value or fails with the Left value
          */
        inline def apply[A](either: Either[E, A])(using inline frame: Frame): A < Abort[E] =
            either match
                case Right(value) => value
                case Left(value)  => fail(value)

        /** Lifts an Option into the Abort effect with Absent as the failure value.
          *
          * @param opt
          *   The Option to lift
          * @return
          *   A computation that succeeds with the Some value or fails with Absent
          */
        inline def apply[A](opt: Option[A])(using inline frame: Frame): A < Abort[Absent] =
            opt match
                case None    => fail(Absent)
                case Some(v) => v

        /** Lifts a scala.util.Try into the Abort effect.
          *
          * @param e
          *   The Try to lift
          * @return
          *   A computation that succeeds with the Success value or fails with the Failure exception
          */
        inline def apply[A](e: scala.util.Try[A])(using inline frame: Frame): A < Abort[Throwable] =
            e match
                case scala.util.Success(t) => t
                case scala.util.Failure(v) => fail(v)

        /** Lifts a Result into the Abort effect.
          *
          * @param r
          *   The Result to lift
          * @return
          *   A computation that succeeds with the Success value or fails with the Failure value
          */
        inline def apply[E, A](r: Result[E, A])(using inline frame: Frame): A < Abort[E] =
            r.foldError(identity, Abort.error)

        /** Lifts a Maybe into the Abort effect.
          *
          * @param m
          *   The Maybe to lift
          * @return
          *   A computation that succeeds with the Present value or fails with Absent
          */
        @targetName("maybe")
        inline def apply[A](m: Maybe[A])(using inline frame: Frame): A < Abort[Absent] =
            m.fold(fail(Absent))(identity)
    end GetOps

    /** Operations for lifting various types into the Abort effect.
      *
      * @tparam E
      *   The failure type of the Abort effect being run
      */
    inline def get[E >: Nothing]: GetOps[E] = GetOps(())

    final private class RunWithOps[E >: Nothing](dummy: Unit) extends AnyVal:
        /** Runs an Abort effect, converting it to a Result.
          *
          * @param v
          *   The computation to run
          * @tparam A
          *   The success type of the computation
          * @tparam S
          *   The effect type of the computation
          * @tparam ER
          *   Any remaining Abort effects after running this one
          * @return
          *   A Result containing either the success value or the failure value, wrapped in the remaining effects
          */
        inline def apply[A: Flat, S, ER, B, S2](
            v: => A < (Abort[E | ER] & S)
        )(
            continue: Result[E, A] => B < S2
        )(
            using
            frame: Frame,
            ct: SafeClassTag[E],
            reduce: Reducible[Abort[ER]]
        ): B < (S & reduce.SReduced & S2) =
            reduce {
                ArrowEffect.handleCatching[
                    Const[Error[E]],
                    Const[Unit],
                    Abort[E],
                    Result[E, A],
                    B,
                    Abort[ER] & S,
                    Abort[ER] & S,
                    S2
                ](
                    erasedTag[E],
                    v.map(Result.succeed[E, A](_))
                )(
                    accept = [C] =>
                        input =>
                            input.isPanic ||
                                input.asInstanceOf[Error[Any]].failure.exists(ct.accepts),
                    handle = [C] => (input, _) => input,
                    recover =
                        case ct(fail) if ct <:< SafeClassTag[Throwable] =>
                            continue(Result.Failure(fail))
                        case fail =>
                            continue(Result.Panic(fail)),
                    done = continue(_)
                )
            }
    end RunWithOps

    /** Runs an Abort effect. This operation handles the Abort effect, converting it into a Result type.
      */
    private inline def runWith[E]: RunWithOps[E] = RunWithOps(())

    final class RunOps[E >: Nothing](dummy: Unit) extends AnyVal:
        /** Runs an Abort effect, converting it to a Result.
          *
          * @param v
          *   The computation to run
          * @tparam A
          *   The success type of the computation
          * @tparam S
          *   The effect type of the computation
          * @tparam ER
          *   Any remaining Abort effects after running this one
          * @return
          *   A Result containing either the success value or the failure value, wrapped in the remaining effects
          */
        def apply[A: Flat, S, ER](v: => A < (Abort[E | ER] & S))(
            using
            frame: Frame,
            ct: SafeClassTag[E],
            reduce: Reducible[Abort[ER]]
        ): Result[E, A] < (S & reduce.SReduced) =
            runWith[E](v)(identity)
        end apply
    end RunOps

    /** Runs an Abort effect. This operation handles the Abort effect, converting it into a Result type.
      */
    inline def run[E]: RunOps[E] = RunOps(())

    final class RunPartialOps[E >: Nothing](dummy: Unit) extends AnyVal:
        /** Runs an Abort effect, converting it to a partial Result and leaving panic cases.
          *
          * @param v
          *   The computation to run
          * @tparam A
          *   The success type of the computation
          * @tparam S
          *   The effect type of the computation
          * @tparam ER
          *   Any remaining Abort effects after running this one
          * @return
          *   A Result containing either the success value or the failure value, wrapped in the remaining effects
          */
        def apply[A: Flat, S, ER](v: => A < (Abort[E | ER] & S))(
            using
            frame: Frame,
            ct: SafeClassTag[E],
            f2: Flat[Result.Partial[E, A]]
        ): Result.Partial[E, A] < (S & Abort[ER]) =
            Abort.runWith[E](v):
                case panic: Panic                    => Abort.error(panic)
                case other: Partial[E, A] @unchecked => other

    end RunPartialOps

    /** Runs an Abort effect, handling only Success and Fail cases. This operation handles the Abort effect, converting it into Success or
      * Fail.
      */
    inline def runPartial[E]: RunPartialOps[E] = RunPartialOps(())

    /** Completely handles an Abort effect, converting it to a partial Result and throwing any Panic exceptions.
      *
      * @param v
      *   The computation to run
      * @tparam A
      *   The success type of the computation
      * @tparam S
      *   The effect type of the computation
      * @tparam ER
      *   Any remaining Abort effects after running this one
      * @return
      *   A Result containing either the success value or the failure value, wrapped in the remaining effects
      */
    def runPartialOrThrow[E, A, S](v: => A < (Abort[E] & S))(
        using
        fl: Flat[A],
        ct: SafeClassTag[E],
        f2: Flat[Result.Partial[E, A]],
        frame: Frame
    ): Result.Partial[E, A] < S =
        Abort.runWith[E](v):
            case Panic(thr)                      => throw thr
            case other: Partial[E, A] @unchecked => other

    final class RecoverOps[E](dummy: Unit) extends AnyVal:

        /** Recovers from an Abort failure by applying the provided function.
          *
          * This method allows you to handle failures in an Abort effect and potentially continue the computation with a new value. It only
          * handles failures of type E and leaves panics unhandled (Abort[Nothing]).
          *
          * @param onFail
          *   A function that takes the failure value of type E and returns a new computation
          * @param v
          *   The original computation that may fail
          * @return
          *   A computation that either succeeds with the original value or the recovered value
          */
        def apply[A: Flat, B: Flat, S, ER](onFail: E => B < S)(v: => A < (Abort[E | ER] & S))(
            using
            frame: Frame,
            ct: SafeClassTag[E],
            reduce: Reducible[Abort[ER]]
        ): (A | B) < (S & reduce.SReduced & Abort[Nothing]) =
            runWith[E](v):
                case Success(a)   => a
                case Failure(e)   => onFail(e)
                case panic: Panic => Abort.error(panic)

        /** Recovers from an Abort failure or panic by applying the provided functions.
          *
          * This method allows you to handle both failures and panics in an Abort effect. It provides separate handlers for failures of type
          * E and for panics (Throwables).
          *
          * @param onFail
          *   A function that takes the failure value of type E and returns a new computation
          * @param onPanic
          *   A function that takes a Throwable and returns a new computation
          * @param v
          *   The original computation that may fail or panic
          * @return
          *   A computation that either succeeds with the original value or the recovered value
          */
        def apply[A: Flat, B: Flat, S, ER](onFail: E => B < S, onPanic: Throwable => B < S)(v: => A < (Abort[E | ER] & S))(
            using
            frame: Frame,
            ct: SafeClassTag[E],
            reduce: Reducible[Abort[ER]]
        ): (A | B) < (S & reduce.SReduced) =
            runWith[E](v):
                case Success(a) => a
                case Failure(e) => onFail(e)
                case Panic(thr) => onPanic(thr)
        end apply
    end RecoverOps

    /** Provides recovery operations for Abort effects.
      */
    inline def recover[E]: RecoverOps[E] = RecoverOps(())

    /** Recovers from an Abort failure by handling Failure cases with a provided function. Does not handle Panic cases, but throws
      * underlying exceptions.
      *
      * @param onFail
      *   A function that takes the failure value of type E and returns a new computation
      * @param v
      *   The original computation that may fail
      * @return
      *   A computation that either succeeds with the original value or the recovered value
      */
    def recoverOrThrow[A, E, B, S](onFail: E => B < S)(v: => A < (Abort[E] & S))(
        using
        fl: Flat[A],
        frame: Frame,
        ct: SafeClassTag[E]
    ): (A | B) < S =
        runWith[E](v):
            case Success(a) => a
            case Failure(e) => onFail(e)
            case Panic(thr) => throw thr

    final case class FoldOps[E](dummy: Unit) extends AnyVal:
        /** Recovers from an Abort failure by applying the provided function.
          *
          * This method allows you to handle failures in an Abort effect and potentially continue the computation with a new value. It only
          * handles failures of type E and leaves panics unhandled (Abort[Nothing]).
          *
          * @param onSuccess
          *   A function that takes the success value of type A and returns a new computation
          * @param onFail
          *   A function that takes the failure value of type E and returns a new computation
          * @param v
          *   The original computation that may fail
          * @return
          *   A computation that either succeeds with the original value or the recovered value
          */
        def apply[A: Flat, B: Flat, S, ER](onSuccess: A => B < S, onFail: E => B < S)(v: => A < (Abort[E | ER] & S))(
            using
            frame: Frame,
            ct: SafeClassTag[E]
        ): B < (S & Abort[ER]) =
            runWith[E](v):
                case Success(a)   => onSuccess(a)
                case Failure(e)   => onFail(e)
                case panic: Panic => Abort.error(panic)

        /** Recovers from an Abort failure by applying the provided function.
          *
          * This method allows you to handle failures and panics in an Abort effect and potentially continue the computation with a new
          * value.
          *
          * @param onSuccess
          *   A function that takes the success value of type A and returns a new computation
          * @param onFail
          *   A function that takes the failure value of type E and returns a new computation
          * @param onPanic
          *   A function that takes the throwable panic value and returns a new computation
          * @param v
          *   The original computation that may fail
          * @return
          *   A computation that either succeeds with the original value or the recovered value
          */
        def apply[A: Flat, B: Flat, S, ER](
            onSuccess: A => B < S,
            onFail: E => B < S,
            onPanic: Throwable => B < S
        )(v: => A < (Abort[E | ER] & S))(
            using
            frame: Frame,
            ct: SafeClassTag[E],
            reduce: Reducible[Abort[ER]]
        ): B < (S & reduce.SReduced) =
            runWith[E](v):
                case Success(a) => onSuccess(a)
                case Failure(e) => onFail(e)
                case Panic(thr) => onPanic(thr)
    end FoldOps

    /** Provides fold operations for Abort effects.
      */
    inline def fold[E]: FoldOps[E] = FoldOps(())

    /** Recovers from an Abort failure by applying the provided function.
      *
      * This method allows you to handle failures in an Abort effect and potentially continue the computation with a new value. It only
      * handles failures of type E and throws any panic exceptions.
      *
      * @param onSuccess
      *   A function that takes the success value of type A and returns a new computation
      * @param onFail
      *   A function that takes the failure value of type E and returns a new computation
      * @param v
      *   The original computation that may fail
      * @return
      *   A computation that either succeeds with the original value or the recovered value
      */
    def foldOrThrow[A, B, E, S](onSuccess: A => B < S, onFail: E => B < S)(v: => A < (Abort[E] & S))(
        using
        fl: Flat[A],
        frame: Frame,
        ct: SafeClassTag[E]
    ): B < S =
        runWith[E](v):
            case Success(a) => onSuccess(a)
            case Failure(e) => onFail(e)
            case Panic(thr) => throw thr

    final class CatchingOps[E <: Throwable](dummy: Unit) extends AnyVal:
        /** Catches exceptions of type E and converts them to Abort failures.
          *
          * @param v
          *   The computation to run and catch exceptions from
          * @tparam A
          *   The return type of the computation
          * @tparam S
          *   The effect type of the computation
          * @return
          *   A computation that may fail with an Abort[E] if an exception of type E is caught
          */
        def apply[A, S](v: => A < S)(
            using
            ct: SafeClassTag[E],
            frame: Frame
        ): A < (Abort[E] & S) =
            Effect.catching(v) {
                case ct(ex) => Abort.fail(ex)
                case ex     => Abort.panic(ex)
            }

        /** Catches exceptions of type E, transforms and converts them to Abort failures.
          *
          * @param v
          *   The computation to run and catch exceptions from
          * @tparam A
          *   The return type of the computation
          * @tparam S
          *   The effect type of the computation
          * @return
          *   A computation that may fail with an Abort[E1] if an exception of type E is caught
          */
        def apply[A, S, E1](f: E => E1)(v: => A < S)(
            using
            ct: SafeClassTag[E],
            frame: Frame
        ): A < (Abort[E1] & S) =
            Effect.catching(v) {
                case ct(ex) => Abort.fail(f(ex))
                case ex     => Abort.panic(ex)
            }
    end CatchingOps

    /** Catches exceptions and converts them to Abort failures. This is useful for integrating exception-based code with the Abort effect.
      */
    inline def catching[E <: Throwable]: CatchingOps[E] = CatchingOps(())

end Abort
