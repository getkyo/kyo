package kyo

import kyo.debug.Debug
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

extension [A, S, E](effect: A < (Abort[E] & S))

    /** Handles the Abort effect and returns its result as a `Result[E, A]`.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect handled
      */
    def result(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        fr: Frame
    ): Result[E, A] < S =
        Abort.run[E](effect)

    /** Handles the Abort effect and returns its result as a `Result.Partial[E, A]`, not handling Panic exceptions.
      *
      * @return
      *   A computation that produces a partial result of this computation with the Abort[E] effect handled
      */
    def resultPartial(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        fr: Frame
    ): Result.Partial[E, A] < (Abort[Nothing] & S) =
        Abort.runPartial(effect)

    /** Handles the Abort effect and returns its result as a `Result.Partial[E, A]`, throwing Panic exceptions
      *
      * @return
      *   A computation that produces a partial result of this computation with the Abort[E] effect handled
      */
    def resultPartialOrThrow(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        fr: Frame
    ): Result.Partial[E, A] < S =
        Abort.runPartialOrThrow(effect)

    /** Handles the Abort effect, transforming caught errors into a new error as determined by mapping function
      *
      * @return
      *   A computation that fails with Abort[E1], where E1 is an error type mapped from E
      */
    def mapAbort[E1, S1](
        fn: E => E1 < S1
    )(
        using
        ct: SafeClassTag[E],
        ct1: SafeClassTag[E1],
        fl: Flat[A],
        fr: Frame
    ): A < (Abort[E1] & S & S1) =
        effect.recover(e => fn(e).map(Kyo.fail))

    def forAbort[E1 <: E]: ForAbortOps[A, S, E, E1] = ForAbortOps(effect)

    /** Translates the Abort effect to a Choice effect by handling failures as dropped choice.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect translated to a dropped Choice
      */
    def abortToChoiceDrop(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        fr: Frame
    ): A < (S & Choice) =
        effect.result.map(e => Choice.get(e.foldError(List(_), _ => Nil)))

    /** Translates the Abort[E] effect to an Abort[Absent] effect in case of failure.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect translated to Abort[Absent]
      */
    def abortToAbsent(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        fr: Frame
    ): A < (S & Abort[Absent]) =
        effect.result.map {
            case Result.Failure(_) => Abort.fail(Absent)
            case Result.Panic(e)   => throw e
            case Result.Success(a) => a
        }

    /** Translates the Abort[E] effect to an Abort[Throwable] effect in case of failure, by converting non-throwable errors to
      * PanicException
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect translated to Abort[Absent]
      */
    def abortToThrowable(
        using
        ng: NotGiven[E <:< Throwable],
        ct: SafeClassTag[E],
        fl: Flat[A],
        fr: Frame
    ): A < (S & Abort[Throwable]) =
        effect.result.map {
            case Result.Success(a)              => a
            case Result.Failure(thr: Throwable) => Abort.fail(thr)
            case Result.Failure(err)            => Abort.fail(PanicException(err))
            case p: Result.Panic                => Abort.get(p)
        }

    /** Handles the Abort effect and applies a recovery function to the error.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect handled
      */
    def recover[A1 >: A, S1](fn: E => A1 < S1)(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        fr: Frame
    ): A1 < (S & S1 & Abort[Nothing]) =
        effect.result.map {
            case Result.Failure(e) => fn(e)
            case Result.Panic(e)   => Abort.panic(e)
            case Result.Success(v) => v
        }

    /** Recovers from an Abort failure by applying the provided function, leaving Panic exceptions unhandled.
      *
      * This method allows you to handle failures in an Abort effect and potentially continue the computation with a new value. It only
      * handles failures of type E and throws panic exceptions.
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
    def foldAbort[B, S1](
        onSuccess: A => B < S1,
        onFail: E => B < S1
    )(
        using
        ct: SafeClassTag[E],
        fl1: Flat[A],
        fl2: Flat[B],
        fr: Frame
    ): B < (Abort[Nothing] & S & S1) =
        Abort.fold(onSuccess, onFail)(effect)

    /** Recovers from an Abort failure by applying the provided function.
      *
      * This method allows you to handle failures in an Abort effect and potentially continue the computation with a new value. It only
      * handles failures of type E and leaves panics unhandled (Abort[Nothing]).
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
    def foldAbort[B, S1](
        onSuccess: A => B < S1,
        onFail: E => B < S1,
        onPanic: Throwable => B < S1
    )(
        using
        ct: SafeClassTag[E],
        fl1: Flat[A],
        fl2: Flat[B],
        fr: Frame
    ): B < (S & S1) =
        Abort.fold[E](onSuccess, onFail, onPanic)(effect)

    /** Recovers from an Abort failure by applying the provided function, throwing Panic exceptions.
      *
      * This method allows you to handle failures in an Abort effect and potentially continue the computation with a new value. It only
      * handles failures of type E and throws panic exceptions.
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
    def foldAbortOrThrow[B, S1](
        onSuccess: A => B < S1,
        onFail: E => B < S1
    )(
        using
        ct: SafeClassTag[E],
        fl1: Flat[A],
        fl2: Flat[B],
        fr: Frame
    ): B < (S & S1) =
        Abort.foldOrThrow(onSuccess, onFail)(effect)

    /** Handles the Abort effect and applies a partial recovery function to the error.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[E] effect
      */
    def recoverSome[A1 >: A, S1](fn: PartialFunction[E, A1 < S1])(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        frame: Frame
    ): A1 < (S & S1 & Abort[E]) =
        effect.result.map {
            case Result.Failure(e) =>
                if fn.isDefinedAt(e) then fn(e)
                else Abort.fail(e)
            case Result.Panic(e)   => Abort.panic(e)
            case Result.Success(v) => v
        }

    /** Translates the Abort effect by swapping the error and success types.
      *
      * @return
      *   A computation that produces the failure E as result of this computation and the success A as Abort[A] effect
      */
    def swapAbort(
        using
        cta: SafeClassTag[A],
        cte: SafeClassTag[E],
        fl: Flat[A],
        frame: Frame
    ): E < (S & Abort[A]) =
        val handled: Result[E, A] < S = effect.result
        handled.map((v: Result[E, A]) => Abort.get(v.swap))
    end swapAbort

    /** Converts all Aborts to Panic, wrapping non-Throwable Failures in PanicException
      *
      * @return
      *   A computation that panics instead of catching Abort effect failures
      */
    def orPanic(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        frame: Frame
    ): A < (Abort[Nothing] & S) =
        Abort.run[E](effect).map:
            case Result.Success(v)              => v
            case Result.Failure(thr: Throwable) => Abort.panic(thr)
            case Result.Failure(other)          => Abort.panic(PanicException(other))
            case panic: Result.Panic            => Abort.error(panic)
    end orPanic

    /** Catches and throws any Abort, wrapping non-Throwable Failures in PanicException
      *
      * @return
      *   A computation that panics instead of catching Abort effect failures
      */
    def orThrow(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        frame: Frame
    ): A < S =
        Abort.run[E](effect).map:
            case Result.Success(v)              => v
            case Result.Failure(thr: Throwable) => throw thr
            case Result.Failure(other)          => throw PanicException(other)
            case Result.Panic(thr)              => throw thr
    end orThrow

    /** Performs this computation repeatedly with a retry policy in case of failures.
      *
      * @param policy
      *   The retry policy to use
      * @return
      *   A computation that produces the result of this computation with Async and Abort[E] effects
      */
    def retry(schedule: Schedule)(using Flat[A], SafeClassTag[E], Frame): A < (S & Async & Abort[E]) =
        Retry[E](schedule)(effect)

    /** Performs this computation repeatedly with a limit in case of failures.
      *
      * @param limit
      *   The limit to use
      * @return
      *   A computation that produces the result of this computation with Async and Abort[E] effects
      */
    def retry(n: Int)(using Flat[A], SafeClassTag[E], Frame): A < (S & Abort[E]) =
        Loop(n): i =>
            Abort.fold[E](
                (result: A) => Loop.done[Int, A](result),
                err =>
                    if i == 0 then Abort.fail(err)
                    else Loop.continue(i - 1),
                thr =>
                    if i == 0 then Abort.panic(thr)
                    else Loop.continue(i - 1)
            )(effect)

    /** Performs this computation repeatedly until it completes successfully.
      *
      * @param limit
      *   The limit to use
      * @return
      *   A computation that produces the result of this computation with Async and no Abort[E]
      */
    def retryForever(using Flat[A], SafeClassTag[E], Frame): A < S =
        Loop(()): _ =>
            Abort.fold[E](
                (result: A) => Loop.done[Unit, A](result),
                _ => Loop.continue,
                _ => Loop.continue
            )(effect)

end extension

class ForAbortOps[A, S, E, E1 <: E](effect: A < (Abort[E] & S)) extends AnyVal:
    /** Handles the partial Abort[E1] effect and returns its result as a `Result[E1, A]`.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E1] effect handled
      */
    def result[ER](
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A],
        frame: Frame
    ): Result[E1, A] < (S & reduce.SReduced) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)])

    /** Handles the partial Abort[E1] effect and returns its result as a `Result.Partial[E1, A]`.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E1] effect handled
      */
    def resultPartial[ER](
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        fl: Flat[A],
        frame: Frame
    ): Result.Partial[E1, A] < (S & Abort[ER]) =
        Abort.runPartial[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)])

    /** Handles a partial Abort[E1] effect, transforming caught errors into a new error as determined by mapping function
      *
      * @return
      *   A computation that fails with Abort[E2], where E2 is an error type mapped from E1
      */
    def mapAbort[ER, E2, S1](
        fn: E1 => E2 < S1
    )(
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        ct1: SafeClassTag[E2],
        reduce: Reducible[Abort[ER]],
        fl: Flat[A],
        fr: Frame
    ): A < (Abort[E2] & reduce.SReduced & S & S1) =
        recover(e => fn(e).map(Kyo.fail))

    /** Handles the partial Abort[E1] effect and applies a recovery function to the error.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E1] effect handled
      */
    def recover[ER](
        using
        ev: E => E1 | ER,
        reduce: Reducible[Abort[ER]],
        ct: SafeClassTag[E1],
        f: Flat[A],
        frame: Frame
    ): [A1 >: A, S1] => (E1 => A1 < S1) => A1 < (S & S1 & reduce.SReduced) =
        [A1 >: A, S1] =>
            (fn: E1 => A1 < S1) =>
                reduce(Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map {
                    case Result.Failure(e1)   => fn(e1)
                    case Result.Success(v)    => v
                    case ab @ Result.Panic(_) => Abort.get(ab.asInstanceOf[Result[Nothing, Nothing]])
                })

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
    def fold[B, S1, ER](
        onSuccess: A => B < S1,
        onFail: E1 => B < S1
    )(
        using
        ct: SafeClassTag[E1],
        ev: E => E1 | ER,
        fl1: Flat[A],
        fl2: Flat[B],
        fr: Frame
    ): B < (S & S1 & Abort[ER]) =
        Abort.fold[E1](onSuccess, onFail)(effect.asInstanceOf[A < (Abort[E1 | ER] & S)])

    /** Recovers from an Abort failure by applying the provided function.
      *
      * This method allows you to handle failures in an Abort effect and potentially continue the computation with a new value. It only
      * handles failures of type E and leaves panics unhandled (Abort[Nothing]).
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
    def fold[B, S1, ER](
        onSuccess: A => B < S1,
        onFail: E1 => B < S1,
        onPanic: Throwable => B < S1
    )(
        using
        ct: SafeClassTag[E1],
        ev: E => E1 | ER,
        reduce: Reducible[Abort[ER]],
        fl1: Flat[A],
        fl2: Flat[B],
        fr: Frame
    ): B < (S & S1 & reduce.SReduced) =
        Abort.fold[E1](onSuccess, onFail, onPanic)(effect.asInstanceOf[A < (Abort[E1 | ER] & S)])

    /** Handles the partial Abort[E1] effect and applies a partial recovery function to the error.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[E1] effect
      */
    def recoverSome[ER](
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        f: Flat[A],
        frame: Frame
    ): [A1 >: A, S1] => PartialFunction[E1, A1 < S1] => A1 < (S & S1 & Abort[E]) =
        [A1 >: A, S1] =>
            (fn: PartialFunction[E1, A1 < S1]) =>
                Abort.run[E1](effect).map {
                    case Result.Failure(e1) if fn.isDefinedAt(e1) => fn(e1)
                    case e1: Result.Error[?]                      => Abort.get(e1)
                    case Result.Success(a)                        => a
            }

    /** Translates the partial Abort[E1] effect to a Choice effect.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E1] effect translated to Choice
      */
    def toChoiceDrop[ER](
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A],
        frame: Frame
    ): A < (S & reduce.SReduced & Choice) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map(e => Choice.get(e.foldError(List(_), _ => Nil)))

    /** Translates the partial Abort[E1] effect to an Abort[Absent] effect in case of failure.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E1] effect translated to Abort[Absent]
      */
    def toAbsent[ER](
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A],
        frame: Frame
    ): A < (S & reduce.SReduced & Abort[Absent]) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map {
            case Result.Failure(_)     => Abort.get(Result.Failure(Absent))
            case p @ Result.Panic(_)   => Abort.get(p.asInstanceOf[Result[Nothing, Nothing]])
            case s @ Result.Success(_) => Abort.get(s.asInstanceOf[Result[Nothing, A]])
        }

    /** Translates the partial Abort[E1] effect to an Abort[Throwable] effect in case of failure, by converting non-throwable errors to
      * PanicException
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect translated to Abort[Absent]
      */
    def toThrowable[ER](
        using
        ng: NotGiven[E1 <:< Throwable],
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        reduce: Reducible[Abort[ER]],
        fl: Flat[A],
        fr: Frame
    ): A < (S & Abort[Throwable] & reduce.SReduced) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map {
            case Result.Success(a)              => a
            case Result.Failure(thr: Throwable) => Abort.fail(thr)
            case Result.Failure(err)            => Abort.fail(PanicException(err))
            case p: Result.Panic                => Abort.get(p)
        }

    /** Translates the partial Abort[E1] effect by swapping the error and success types.
      *
      * @return
      *   A computation that produces the failure E1 as result of this computation and the success A as Abort[A] effect
      */
    def swap[ER](
        using
        ev: E => E1 | ER,
        reduce: Reducible[Abort[ER]],
        ct: SafeClassTag[E1],
        f: Flat[A],
        frame: Frame
    ): E1 < (S & reduce.SReduced & Abort[A]) =
        val handled = Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)])
        handled.map((v: Result[E1, A]) => Abort.get(v.swap))
    end swap

    /** Catches partial Abort and panics instead
      *
      * @return
      *   A computation that panics instead of catching Abort effect failures
      */
    def orPanic[ER](
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        fl: Flat[A],
        frame: Frame
    ): A < (S & Abort[ER]) =
        Abort.runPartial[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map:
            case Result.Success(v)              => v
            case Result.Failure(thr: Throwable) => Abort.panic(thr)
            case Result.Failure(other)          => Abort.panic(PanicException(other))
    end orPanic

    /** Performs this computation repeatedly with a retry policy in case of failures.
      *
      * @param policy
      *   The retry policy to use
      * @return
      *   A computation that produces the result of this computation with Async and Abort[E] effects
      */
    def retry[ER](schedule: Schedule)(
        using
        E => E1 | ER,
        Flat[A],
        SafeClassTag[E1],
        Frame
    ): A < (S & Async & Abort[E1 | ER]) =
        Retry[E1](schedule)(effect.asInstanceOf[A < (S & Abort[E1 | ER])])

    /** Performs this computation repeatedly with a limit in case of failures.
      *
      * @param limit
      *   The limit to use
      * @return
      *   A computation that produces the result of this computation with Async and Abort[E] effects
      */
    def retry[ER](n: Int)(
        using
        E => E1 | ER,
        Flat[A],
        SafeClassTag[E1],
        Frame
    ): A < (S & Abort[E1 | ER]) =
        val retypedEffect = effect.asInstanceOf[A < (S & Abort[E1 | ER])]
        Loop(n): i =>
            Abort.fold[E1](
                (result: A) => Loop.done[Int, A](result),
                err =>
                    if i == 0 then Abort.fail(err)
                    else Loop.continue(i - 1),
                thr =>
                    if i == 0 then Abort.panic(thr)
                    else Loop.continue(i - 1)
            )(retypedEffect)
    end retry

    /** Performs this computation repeatedly until it completes successfully.
      *
      * @param limit
      *   The limit to use
      * @return
      *   A computation that produces the result of this computation with Async and no Abort[E]
      */
    def retryForever[ER](
        using
        E => E1 | ER,
        Flat[A],
        SafeClassTag[E1],
        SafeClassTag[E1 | ER],
        Frame
    ): A < (S & Abort[ER]) =
        val retypedEffect = effect.asInstanceOf[A < (S & Abort[E1 | ER])]
        Loop(()): _ =>
            Abort.fold[E1](
                (result: A) => Loop.done[Unit, A](result),
                _ => Loop.continue,
                _ => Loop.continue
            )(retypedEffect)
    end retryForever
end ForAbortOps
