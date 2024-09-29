package kyo

import kernel.ArrowEffect
import kernel.Const
import kernel.Frame
import kyo.Result.*
import kyo.Tag
import kyo.kernel.Effect
import kyo.kernel.Reducible
import scala.annotation.targetName
import scala.reflect.ClassTag

/** The Abort effect allows for short-circuiting computations with failure values. This effect is used for handling errors and early
  * termination scenarios in a functional manner and handle thrown exceptions.
  *
  * @tparam E
  *   The type of the failure value
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
    inline def fail[E](inline value: E)(using inline frame: Frame): Nothing < Abort[E] = error(Fail(value))

    /** Fails the computation with a panic value (unchecked exception).
      *
      * @param ex
      *   The exception to use as a panic value
      * @return
      *   A computation that immediately fails with the given exception
      */
    inline def panic[E](inline ex: Throwable)(using inline frame: Frame): Nothing < Abort[E] = error(Panic(ex))

    inline def error[E](inline e: Error[E])(using inline frame: Frame): Nothing < Abort[E] =
        ArrowEffect.suspendMap[Any](erasedTag[E], e)(_ => ???)

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

        /** Lifts an Option into the Abort effect with Maybe.Empty as the failure value.
          *
          * @param opt
          *   The Option to lift
          * @return
          *   A computation that succeeds with the Some value or fails with Maybe.Empty
          */
        inline def apply[A](opt: Option[A])(using inline frame: Frame): A < Abort[Maybe.Empty] =
            opt match
                case None    => fail(Maybe.Empty)
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
            r.fold {
                case e: Fail[E] => fail(e.error)
                case Panic(ex)  => Abort.panic(ex)
            }(identity)

        /** Lifts a Maybe into the Abort effect.
          *
          * @param m
          *   The Maybe to lift
          * @return
          *   A computation that succeeds with the Defined value or fails with Maybe.Empty
          */
        @targetName("maybe")
        inline def apply[A](m: Maybe[A])(using inline frame: Frame): A < Abort[Maybe.Empty] =
            m.fold(fail(Maybe.Empty))(identity)
    end GetOps

    /** Operations for lifting various types into the Abort effect.
      *
      * @tparam E
      *   The failure type of the Abort effect being run
      */
    inline def get[E >: Nothing]: GetOps[E] = GetOps(())

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
            reduce {
                ArrowEffect.handle.catching[
                    Const[Error[E]],
                    Const[Unit],
                    Abort[E],
                    Result[E, A],
                    Result[E, A],
                    Abort[ER] & S,
                    Abort[ER] & S,
                    Any
                ](
                    erasedTag[E],
                    v.map(Result.success[E, A](_))
                )(
                    accept = [C] =>
                        input =>
                            input.isPanic ||
                                input.asInstanceOf[Error[Any]].failure.exists(ct.accepts),
                    handle = [C] => (input, _) => input,
                    recover =
                        case ct(fail) if ct <:< SafeClassTag[Throwable] =>
                            Result.fail(fail)
                        case fail =>
                            Result.panic(fail),
                    done =
                        case Result.Panic(ct(e)) if ct <:< SafeClassTag[Throwable] =>
                            Result.Fail(e)
                        case r => r
                )
            }
        end apply
    end RunOps

    /** Runs an Abort effect. This operation handles the Abort effect, converting it into a Result type.
      */
    inline def run[E]: RunOps[E] = RunOps(())

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
    end CatchingOps

    /** Catches exceptions and converts them to Abort failures. This is useful for integrating exception-based code with the Abort effect.
      */
    inline def catching[E <: Throwable]: CatchingOps[E] = CatchingOps(())

end Abort
