package kyo

import kyo.debug.Debug
import kyo.kernel.ArrowEffect
import kyo.kernel.Boundary
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

sealed case class PanicException[A] private[kyo] (val error: A) extends Exception(s"Uncaught error: $error")

extension [A, S](effect: A < S)

    /** Performs this computation and then the next one, discarding the result of this computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of `next`
      */
    @targetName("zipRight")
    def *>[A1, S1](next: => A1 < S1)(using Frame): A1 < (S & S1) =
        effect.map(_ => next)

    /** Performs this computation and then the next one, discarding the result of the next computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of this computation
      */
    @targetName("zipLeft")
    def <*[A1, S1](next: => A1 < S1)(using Frame): A < (S & S1) =
        effect.map(e => next.andThen(e))

    /** Performs this computation and then the next one, returning both results as a tuple.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces a tuple of both results
      */
    @targetName("zip")
    def <*>[A1, S1](next: => A1 < S1)(using Frame): (A, A1) < (S & S1) =
        effect.map(e => next.map(n => (e, n)))

    /** Performs this computation and prints its result to the console.
      *
      * @return
      *   A computation that produces the result of this computation
      */
    def debugValue(using Frame): A < S = Debug(effect)

    /** Performs this computation and prints its result to the console with a detailed execution trace.
      *
      * @return
      *   A computation that produces the result of this computation
      */
    def debugTrace(using Frame): A < S = Debug.trace(effect)

    /** Performs this computation after a delay.
      *
      * @param duration
      *   The duration to delay for
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def delayed(duration: Duration)(using Frame): A < (S & Async) =
        Async.delay(duration)(effect)

    /** Performs this computation repeatedly with a backoff policy.
      *
      * @param policy
      *   The backoff policy to use
      * @return
      *   A computation that produces the result of the last execution
      */
    def repeat(schedule: Schedule)(using Flat[A], Frame): A < (S & Async) =
        Clock.use { clock =>
            Loop(schedule) { schedule =>
                clock.now.map { now =>
                    schedule.next(now).map { (delay, nextSchedule) =>
                        effect.delayed(delay).andThen(Loop.continue(nextSchedule))
                    }.getOrElse {
                        effect.map(Loop.done)
                    }
                }
            }
        }

    /** Performs this computation repeatedly with a limit.
      *
      * @param limit
      *   The maximum number of times to repeat the computation
      * @return
      *   A computation that produces the result of the last execution
      */
    def repeat(limit: Int)(using Flat[A], Frame): A < S =
        Loop.indexed { i =>
            if i >= limit then effect.map(Loop.done)
            else effect.andThen(Loop.continue)
        }

    /** Performs this computation repeatedly with a backoff policy and a limit.
      *
      * @param backoff
      *   The backoff policy to use
      * @param limit
      *   The limit to use
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def repeat(backoff: Int => Duration, limit: Int)(using Flat[A], Frame): A < (S & Async) =
        Loop.indexed { i =>
            if i >= limit then effect.map(Loop.done)
            else effect.delayed(backoff(i)).andThen(Loop.continue)
        }

    /** Performs this computation repeatedly while the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration
      * @return
      *   A computation that produces the result of the last successful execution before the condition becomes false
      */
    def repeatWhile[S1](fn: A => Boolean < S1)(using Flat[A], Frame): A < (S & S1 & Async) =
        def loop(last: A): A < (S & S1 & Async) =
            fn(last).map { cont =>
                if cont then effect.map(v => loop(v))
                else last
            }

        effect.map(v => loop(v))
    end repeatWhile

    /** Performs this computation repeatedly while the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration taking the current value and the number of iterations so far, and returning a tuple
      *   with the condition and the duration to sleep between iterations
      * @return
      *   A computation that produces the result of the last successful execution before the condition becomes false
      */
    def repeatWhile[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using Flat[A], Frame): A < (S & S1 & Async) =
        def loop(last: A, i: Int): A < (S & S1 & Async) =
            fn(last, i).map { case (cont, duration) =>
                if cont then effect.delayed(duration).map(v => loop(v, i + 1))
                else last
            }

        effect.map(v => loop(v, 0))
    end repeatWhile

    /** Performs this computation repeatedly until the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration
      * @return
      *   A computation that produces the result of the first execution where the condition becomes true
      */
    def repeatUntil[S1](fn: A => Boolean < S1)(using Flat[A], Frame): A < (S & S1 & Async) =
        def loop(last: A): A < (S & S1 & Async) =
            fn(last).map { cond =>
                if cond then last
                else effect.map(loop)
            }

        effect.map(v => loop(v))
    end repeatUntil

    /** Performs this computation repeatedly until the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration taking the current value and the number of iterations so far, and returning a tuple
      *   with the condition and the duration to sleep between iterations
      * @return
      *   A computation that produces the result of the first execution where the condition becomes true
      */
    def repeatUntil[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using
        Flat[A],
        Frame
    ): A < (S & S1 & Async) =
        def loop(last: A, i: Int): A < (S & S1 & Async) =
            fn(last, i).map { case (cont, duration) =>
                if cont then last
                else Kyo.sleep(duration) *> effect.map(v => loop(v, i + 1))
            }

        effect.map(v => loop(v, 0))
    end repeatUntil

    /** Performs this computation repeatedly with a retry policy in case of failures.
      *
      * @param policy
      *   The retry policy to use
      * @return
      *   A computation that produces the result of this computation with Async and Abort[Throwable] effects
      */
    def retry(schedule: Schedule)(using Flat[A], Frame): A < (S & Async & Abort[Throwable]) =
        Retry[Throwable](schedule)(effect)

    /** Performs this computation repeatedly with a limit in case of failures.
      *
      * @param limit
      *   The limit to use
      * @return
      *   A computation that produces the result of this computation with Async and Abort[Throwable] effects
      */
    def retry(n: Int)(using Flat[A], Frame): A < (S & Async & Abort[Throwable]) =
        Retry[Throwable](Schedule.repeat(n))(effect)

    /** Performs this computation indefinitely.
      *
      * @return
      *   A computation that never completes
      * @note
      *   This method is typically used for long-running processes or servers that should run continuously
      */
    def forever(using Frame): Nothing < S =
        (effect *> effect.forever) *> (throw new IllegalStateException("infinite loop ended"))

    /** Performs this computation when the given condition holds.
      *
      * @param condition
      *   The condition to check
      * @return
      *   A computation that produces the result of this computation wrapped in Present if the condition is satisfied, or Absent if not
      */
    def when[S1](condition: => Boolean < S1)(using Frame): Maybe[A] < (S & S1) =
        condition.map(c => if c then effect.map(Present.apply) else Absent)

    /** Performs this computation catching any Throwable in an Abort[Throwable] effect.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[Throwable] effect
      */
    def unpanic(using Flat[A], Frame): A < (S & Abort[Throwable]) =
        Abort.run[Throwable](effect).map:
            case Result.Success(v) => v
            case Result.Error(ex)  => Abort.fail(ex)

    /** Performs this computation and applies an effectful function to its result.
      *
      * @return
      *   A computation that produces the result of this computation
      */
    def tap[S1](f: A => Any < S1)(using Frame): A < (S & S1) =
        effect.map(a => f(a).andThen(a))

    /** Performs this computation unless the given condition holds, in which case it returns an Abort[Absent] effect.
      *
      * @param condition
      *   The condition to check
      * @return
      *   A computation that produces the result of this computation with Abort[Absent] effect
      */
    def unless[S1](condition: Boolean < S1)(using Frame): A < (S & S1 & Abort[Absent]) =
        condition.map(c => if c then Abort.fail(Absent) else effect)

    /** Ensures that the specified finalizer is executed after this effect, whether it succeeds or fails. The finalizer will execute when
      * the Resource effect is handled.
      *
      * @param finalizer
      *   The finalizer to execute after this effect
      * @return
      *   An effect that executes the finalizer after this effect
      */
    def ensuring(finalizer: => Unit < Async)(using Frame): A < (S & Resource & IO) =
        Resource.ensure(finalizer).andThen(effect)

end extension

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
        effect.catching(e => fn(e).map(Kyo.fail))

    def forAbort[E1 <: E]: ForAbortOps[A, S, E, E1] = ForAbortOps(effect)

    /** Translates the Abort effect to a Choice effect by handling failures as empty choices.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect translated to an empty Choice
      */
    def abortToEmpty(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        fr: Frame
    ): A < (S & Choice) =
        effect.result.map(e => Choice.get(e.fold(_ => Nil)(List(_))))

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
            case Result.Fail(_)    => Abort.fail(Absent)
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
            case Result.Success(a)           => a
            case Result.Fail(thr: Throwable) => Abort.fail(thr)
            case Result.Fail(err)            => Abort.fail(PanicException(err))
            case p: Result.Panic             => Abort.get(p)
        }

    /** Handles the Abort effect and applies a recovery function to the error.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E] effect handled
      */
    def catching[A1 >: A, S1](fn: E => A1 < S1)(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        fr: Frame
    ): A1 < (S & S1) =
        effect.result.map {
            case Result.Fail(e)    => fn(e)
            case Result.Panic(e)   => throw e
            case Result.Success(v) => v
        }

    /** Handles the Abort effect and applies a partial recovery function to the error.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[E] effect
      */
    def catchingSome[A1 >: A, S1](fn: PartialFunction[E, A1 < S1])(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        frame: Frame
    ): A1 < (S & S1 & Abort[E]) =
        effect.result.map {
            case Result.Fail(e) =>
                if fn.isDefinedAt(e) then fn(e)
                else Abort.fail(e)
            case Result.Panic(e)   => throw e
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

    /** Catches any Aborts and panics instead
      *
      * @return
      *   A computation that panics instead of catching Abort effect failures
      */
    def orPanic(
        using
        ct: SafeClassTag[E],
        fl: Flat[A],
        frame: Frame
    ): A < S =
        val handled: A < (S & Abort[Nothing]) = Abort.run[E](effect).map:
            case Result.Success(v)           => v
            case Result.Fail(thr: Throwable) => Abort.panic(thr)
            case Result.Fail(other)          => Abort.panic(PanicException(other))
            case other: Result.Panic         => Abort.get(other)

        summon[Reducible[Abort[Nothing]] { type SReduced = Any }][A, S](handled)
    end orPanic

end extension

extension [A, S, E](effect: A < (Abort[Absent] & S))

    /** Handles the Abort[Absent] effect and returns its result as a `Maybe[A]`.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[Absent] effect handled
      */
    def maybe(using Flat[A], Frame): Maybe[A] < S =
        Abort.run[Absent](effect).map {
            case Result.Fail(_)    => Absent
            case Result.Panic(e)   => throw e
            case Result.Success(a) => Present(a)
        }

    /** Translates the Abort[Absent] effect to a Choice effect.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[Absent] effect translated to Choice
      */
    def absentToChoice(using Flat[A], Frame): A < (S & Choice) =
        effect.forAbort[Absent].toEmpty

    /** Handles Abort[Absent], aborting in Absent cases with NoSuchElementException exceptions
      *
      * @return
      *   A computation that aborts with Maybe.Empty when its Choice effect is reduced to an empty sequence
      */
    def absentToThrowable(using Flat[A], Frame): A < (S & Abort[NoSuchElementException]) =
        for
            res <- effect.forAbort[Absent].result
        yield res match
            case Result.Fail(_)    => Abort.catching(Absent.get)
            case Result.Success(a) => Abort.get(Result.success(a))
            case res: Result.Panic => Abort.get(res)

    /** Handles the Abort[Absent] effect translating it to an Abort[E] effect.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[Absent] effect translated to Abort[E]
      */
    def absentToFailure(failure: => E)(using Flat[A], Frame): A < (S & Abort[E]) =
        for
            res <- effect.forAbort[Absent].result
        yield res match
            case Result.Fail(_)    => Abort.get(Result.Fail(failure))
            case Result.Success(a) => Abort.get(Result.success(a))
            case res: Result.Panic => Abort.get(res)
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
        catching(e => fn(e).map(Kyo.fail))

    /** Handles the partial Abort[E1] effect and applies a recovery function to the error.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E1] effect handled
      */
    def catching[ER](
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
                    case Result.Fail(e1)      => fn(e1)
                    case Result.Success(v)    => v
                    case ab @ Result.Panic(_) => Abort.get(ab.asInstanceOf[Result[Nothing, Nothing]])
                })

    /** Handles the partial Abort[E1] effect and applies a partial recovery function to the error.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[E1] effect
      */
    def catchingSome[ER](
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        f: Flat[A],
        frame: Frame
    ): [A1 >: A, S1] => PartialFunction[E1, A1 < S1] => A1 < (S & S1 & Abort[E]) =
        [A1 >: A, S1] =>
            (fn: PartialFunction[E1, A1 < S1]) =>
                Abort.run[E1](effect).map {
                    case Result.Fail(e1) if fn.isDefinedAt(e1) => fn(e1)
                    case e1: Result.Error[?]                   => Abort.get(e1)
                    case Result.Success(a)                     => a
            }

    /** Translates the partial Abort[E1] effect to a Choice effect.
      *
      * @return
      *   A computation that produces the result of this computation with the Abort[E1] effect translated to Choice
      */
    def toEmpty[ER](
        using
        ev: E => E1 | ER,
        ct: SafeClassTag[E1],
        reduce: Reducible[Abort[ER]],
        flat: Flat[A],
        frame: Frame
    ): A < (S & reduce.SReduced & Choice) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map(e => Choice.get(e.fold(_ => Nil)(List(_))))

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
            case Result.Fail(_)        => Abort.get(Result.Fail(Absent))
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
            case Result.Success(a)           => a
            case Result.Fail(thr: Throwable) => Abort.fail(thr)
            case Result.Fail(err)            => Abort.fail(PanicException(err))
            case p: Result.Panic             => Abort.get(p)
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
        reduce: Reducible[Abort[ER]],
        ct: SafeClassTag[E1],
        fl: Flat[A],
        frame: Frame
    ): A < (S & reduce.SReduced) =
        Abort.run[E1](effect.asInstanceOf[A < (Abort[E1 | ER] & S)]).map:
            case Result.Success(v)           => v
            case Result.Fail(thr: Throwable) => Abort.panic(thr).asInstanceOf[Nothing < Any]
            case Result.Fail(other)          => Abort.panic(PanicException(other)).asInstanceOf[Nothing < Any]
            case other: Result.Panic         => Abort.get(other).asInstanceOf[Nothing < Any]

    end orPanic
end ForAbortOps

extension [A, S, E](effect: A < (S & Env[E]))

    /** Handles the Env[E] efffect with the provided value.
      *
      * @param dependency
      *   The value to provide for the environment
      * @return
      *   A computation that produces the result of this computation with the Env[E] effect handled
      */
    def provideValue[E1 >: E, ER](dependency: E1)(
        using
        ev: E => E1 & ER,
        flat: Flat[A],
        reduce: Reducible[Env[ER]],
        tag: Tag[E1],
        frame: Frame
    ): A < (S & reduce.SReduced) =
        Env.run[E1, A, S, ER](dependency)(effect.asInstanceOf[A < (S & Env[E1 | ER])])

    /** Handles the Env[E] effect with the provided layer.
      *
      * @param layer
      *   The layers to perform this computation with
      * @return
      *   A computation that produces the result of this computation
      */
    inline def provideLayer[S1, E1 >: E, ER](layer: Layer[E1, S1])(
        using
        ev: E => E1 & ER,
        flat: Flat[A],
        reduce: Reducible[Env[ER]],
        tag: Tag[E1],
        frame: Frame
    ): A < (S & S1 & Memo & reduce.SReduced) =
        for
            tm <- layer.run
            e1 = tm.get[E1]
        yield effect.provideValue(e1)

    /** Handles the Env[E] effect with the provided layers via Env.runLayer.
      *
      * @param layers
      *   The layers to handle this computation with
      * @return
      *   A computation that produces the result of this computation
      */
    transparent inline def provide(inline layers: Layer[?, ?]*): A < Nothing =
        Env.runLayer(layers*)(effect)

end extension

extension [A, S](effect: A < (S & Choice))

    /** Filters the result of this computation and performs a `Choice` effect if the condition does not hold.
      *
      * @return
      *   A computation that produces the result of this computation with Choice effect
      */
    def filterChoice[S1](fn: A => Boolean < S1)(using Frame): A < (S & S1 & Choice) =
        effect.map(a => fn(a).map(b => Choice.dropIf(!b)).andThen(a))

    /** Handles the Choice effect and returns its result as a `Seq[A]`.
      *
      * @return
      *   A computation that produces a sequence of results from this computation with the Choice effect handled
      */
    def handleChoice(using Flat[A], Frame): Seq[A] < S = Choice.run(effect)

    /** Handles empty cases of Choice effects as Abort[Absent]
      *
      * @return
      *   A computation that aborts with Maybe.Empty when its Choice effect is reduced to an empty sequence
      */
    def emptyToAbsent(using Flat[A], Frame): A < (Choice & Abort[Absent] & S) =
        Choice.run(effect).map:
            case seq if seq.isEmpty => Abort.fail(Absent)
            case other              => Choice.get(other)

    /** Handles empty cases of Choice effects as NoSuchElementException aborts
      *
      * @return
      *   A computation that aborts with Maybe.Empty when its Choice effect is reduced to an empty sequence
      */
    def emptyToThrowable(using Flat[A], Frame): A < (Choice & Abort[NoSuchElementException] & S) =
        Choice.run(effect).map:
            case seq if seq.isEmpty => Abort.catching(Chunk.empty.head)
            case other              => Choice.get(other)

    /** Handles empty cases of Choice effects as Aborts of a specified instance of E
      *
      * @param error
      *   Error that the resulting effect will abort with if Choice is reduced to empty
      * @return
      *   A computation that produces the result of this computation with empty Choice translated to Abort[E]
      */
    def emptyToFailure[E](error: => E)(using Flat[A], Frame): A < (Choice & Abort[E] & S) =
        Choice.run(effect).map:
            case seq if seq.isEmpty => Abort.fail(error)
            case other              => Choice.get(other)

end extension

extension [A, E, Ctx](effect: A < (Abort[E] & Async & Ctx))

    /** Forks this computation using the Async effect and returns its result as a `Fiber[E, A]`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    inline def fork(
        using
        flat: Flat[A],
        frame: Frame
    ): Fiber[E, A] < (IO & Ctx) =
        Async.run(effect)

    /** Forks this computation using the Async effect and returns its result as a `Fiber[E, A]`, managed by the Resource effect. Unlike
      * `fork`, which creates an unmanaged fiber, `forkScoped` ensures that the fiber is properly cleaned up when the enclosing scope is
      * closed, preventing resource leaks.
      *
      * @return
      *   A computation that produces the result of this computation with Async and Resource effects
      */
    inline def forkScoped(
        using
        flat: Flat[A],
        frame: Frame
    ): Fiber[E, A] < (IO & Ctx & Resource) =
        Kyo.acquireRelease(Async.run(effect))(_.interrupt.unit)

end extension

extension [A, E, S](fiber: Fiber[E, A] < S)

    /** Joins the fiber and returns its result as a `A`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def join(using reduce: Reducible[Abort[E]], frame: Frame): A < (S & reduce.SReduced & Async) =
        fiber.map(_.get)

    /** Awaits the completion of the fiber and returns its result as a `Unit`.
      *
      * @return
      *   A computation that produces the result of this computation with Async effect
      */
    def awaitCompletion(using Flat[A], Frame): Unit < (S & Async) =
        fiber.map(_.getResult.unit)
end extension

extension [A, E, Ctx](effect: A < (Abort[E] & Async & Ctx))

    /** Performs this computation and then the next one in parallel, discarding the result of this computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of `next`
      */
    @targetName("zipRightPar")
    inline def &>[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        f: Flat[A],
        f1: Flat[A1],
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): A1 < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        _zipRightPar(next)

    private def _zipRightPar[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        f: Flat[A],
        f1: Flat[A1],
        b: Boundary[Ctx, IO & Abort[E]],
        b1: Boundary[Ctx1, IO & Abort[E1]],
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): A1 < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        for
            fiberA  <- Async._run(effect)
            fiberA1 <- Async._run(next)
            _       <- fiberA.awaitCompletion
            a1      <- fiberA1.join
        yield a1

    /** Performs this computation and then the next one in parallel, discarding the result of the next computation.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces the result of this computation
      */
    @targetName("zipLeftPar")
    inline def <&[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        f: Flat[A],
        f1: Flat[A1],
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): A < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        _zipLeftPar(next)

    private def _zipLeftPar[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        f: Flat[A],
        f1: Flat[A1],
        b: Boundary[Ctx, IO & Abort[E]],
        b1: Boundary[Ctx1, IO & Abort[E1]],
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): A < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        for
            fiberA  <- Async._run(effect)
            fiberA1 <- Async._run(next)
            a       <- fiberA.join
            _       <- fiberA1.awaitCompletion
        yield a

    /** Performs this computation and then the next one in parallel, returning both results as a tuple.
      *
      * @param next
      *   The computation to perform after this one
      * @return
      *   A computation that produces a tuple of both results
      */
    @targetName("zipPar")
    inline def <&>[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        f: Flat[A],
        f1: Flat[A1],
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): (A, A1) < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        _zipPar(next)

    private def _zipPar[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(
        using
        f: Flat[A],
        f1: Flat[A1],
        b: Boundary[Ctx, IO & Abort[E]],
        b1: Boundary[Ctx1, IO & Abort[E1]],
        r: Reducible[Abort[E]],
        r1: Reducible[Abort[E1]],
        fr: Frame
    ): (A, A1) < (r.SReduced & r1.SReduced & Async & Ctx & Ctx1) =
        for
            fiberA  <- Async._run(effect)
            fiberA1 <- Async._run(next)
            a       <- fiberA.join
            a1      <- fiberA1.join
        yield (a, a1)
end extension

extension [A, S](effect: Unit < (Emit[Chunk[A]] & S))
    /** Convert streaming Emit effect to a [[Stream[A, S]]]
      *
      * @return
      *   Stream representation of original effect
      */
    def emitToStream: Stream[A, S] = Stream(effect)

private case object FinalEmit

extension [A, B, S](effect: B < (Emit[A] & S))
    /** Handle Emit[A], returning all emitted values along with the original effect's result value.
      *
      * @return
      *   A computation with handled Emit yielding a tuple of emitted values along with the original computation's result
      */
    def handleEmit(using Tag[A], Flat[B], Frame): (Chunk[A], B) < S = Emit.run[A](effect)

    /** Handle Emit[A], returning only emitted values, discarding the original effect's result
      *
      * @return
      *   A computation with handled Emit yielding emitted values only
      */
    def handleEmitDiscarding(using Tag[A], Flat[B], Frame): Chunk[A] < S = Emit.run[A](effect).map(_._1)

    /** Handle Emit[A], executing function [[fn]] on each emitted value
      *
      * @param fn
      *   Function to handle each emitted value
      * @return
      *   A computation with handled Emit
      */
    def foreachEmit[S1](
        fn: A => Unit < S1
    )(
        using
        tag: Tag[Emit[A]],
        fl: Flat[B],
        f: Frame
    ): B < (S & S1) =
        ArrowEffect.handle(tag, effect):
            [C] =>
                (a, cont) =>
                    fn(a).map(_ => cont(()))

    /** Handle Emit[A] by passing emitted values to [[channel]]. Fails with Abort[Closed] on channel closure
      *
      * @param channel
      *   Channel in which to put emitted values
      * @return
      *   Asynchronous computation with handled Emit that can fail with Abort[Closed]
      */
    def emitToChannel(channel: Channel[A])(using Tag[A], Flat[B], Frame): B < (S & Async & Abort[Closed]) =
        effect.foreachEmit(a => channel.put(a))

    /** Handle Emit[A], re-emitting in chunks according to [[chunkSize]]
      *
      * @param chunkSize
      *   maximum size of emitted chunks
      *
      * @return
      */
    def emitChunked(
        chunkSize: Int
    )(
        using
        tag: Tag[Emit[A]],
        fr: Frame,
        at: Tag[A],
        fl: Flat[B]
    ): B < (Emit[Chunk[A]] & S) =
        ArrowEffect.handleState(tag, Chunk.empty[A], effect)(
            [C] =>
                (v, buffer, cont) =>
                    val b2 = buffer.append(v)
                    if b2.size >= chunkSize then
                        Emit.andMap(b2): ack =>
                            (Chunk.empty, cont(ack))
                    else
                        (b2, cont(()))
                    end if
            ,
            (buffer, v) =>
                if buffer.isEmpty then v
                else Emit.andMap(buffer)(_ => v)
        )

    /** Convert emitting effect to stream, chunking Emitted values in [[chunkSize]], and discarding result.
      *
      * @param chunkSize
      *   size of chunks to stream
      * @return
      *   Stream of emitted values
      */
    def emitChunkedToStreamDiscarding(
        chunkSize: Int
    )(
        using
        NotGiven[B =:= Unit],
        Tag[A],
        Flat[B],
        Frame
    ): Stream[A, S] =
        effect.emitChunked(chunkSize).emitToStreamDiscarding

    /** Convert an effect that emits values of type [[A]] while computing a result of type [[B]] to an asynchronous stream of the emission
      * type [[A]] along with a separate asynchronous effect that yields the result of the original effect after the stream has been
      * handled.
      *
      * @param chunkSize
      *   Size of chunks to stream
      * @return
      *   Tuple of async stream of type [[A]] and async effect yielding result [[B]]
      */
    def emitChunkedToStreamAndResult(
        using
        Tag[A],
        Flat[B],
        Frame
    )(chunkSize: Int): (Stream[A, S & Async], B < Async) < Async =
        effect.emitChunked(chunkSize).emitToStreamAndResult
end extension

extension [A, B, S](effect: B < (Emit[Chunk[A]] & S))
    /** Convert emitting effect to stream and discarding result.
      *
      * @return
      *   Stream of emitted values
      */
    def emitToStreamDiscarding(
        using
        NotGiven[B =:= Unit],
        Frame
    ): Stream[A, S] =
        Stream(effect.unit)

    /** Convert an effect that emits chunks of type [[A]] while computing a result of type [[B]] to an asynchronous stream of the emission
      * type [[A]] and a separate asynchronous effect that yields the result of the original effect after the stream has been handled.
      *
      * @return
      *   tuple of async stream of type [[A]] and async effect yielding result [[B]]
      */
    def emitToStreamAndResult(
        using
        Flat[B],
        Frame
    ): (Stream[A, S & Async], B < Async) < Async =
        for
            p <- Promise.init[Nothing, B]
            streamEmit = effect.map: b =>
                p.complete(Result.success(b)).unit
        yield (Stream(streamEmit), p.join)
end extension

extension [A, B, S](effect: Unit < (Emit[A] & S))
    /** Convert emitting effect to stream, chunking Emitted values in [[chunkSize]].
      *
      * @param chunkSize
      *   Size of chunks to stream
      * @return
      *   Stream of emitted values
      */
    def emitChunkedToStream(
        chunkSize: Int
    )(
        using
        Tag[A],
        Frame
    ): Stream[A, S] =
        effect.emitChunked(chunkSize).emitToStream
end extension
