package kyo

import kyo.Result.Error
import kyo.debug.Debug
import kyo.internal.Zippable
import kyo.kernel.ArrowEffect
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

sealed case class PanicException[A] private[kyo] (val error: A)(using Frame)
    extends KyoException(s"Uncaught error", error.toString)

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
        effect.andThen(next)

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
    def <*>[A1, S1](next: => A1 < S1)(using frame: Frame, zippable: Zippable[A, A1]): zippable.Out < (S & S1) =
        effect.map(e => next.map(n => zippable.zip(e, n)))

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
    def delay(duration: Duration)(using Frame): A < (S & Async) =
        Async.delay(duration)(effect)

    /** Performs this computation repeatedly with a backoff policy.
      *
      * @param policy
      *   The backoff policy to use
      * @return
      *   A computation that produces the result of the last execution
      */
    def repeat(schedule: Schedule)(using Frame): A < (S & Async) =
        Clock.use { clock =>
            Loop(schedule) { current =>
                clock.now.map { now =>
                    current.next(now).map { (delay, next) =>
                        effect.delay(delay).andThen(Loop.continue(next))
                    }.getOrElse {
                        effect.map(Loop.done)
                    }
                }
            }
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
    def repeatAtInterval(backoff: Int => Duration, limit: Int)(using Frame): A < (S & Async) =
        Loop.indexed { i =>
            if i >= limit then effect.map(Loop.done)
            else effect.delay(backoff(i)).andThen(Loop.continue)
        }

    /** Performs this computation repeatedly with a limit.
      *
      * @param limit
      *   The maximum number of times to repeat the computation
      * @return
      *   A computation that produces the result of the last execution
      */
    def repeat(limit: Int)(using Frame): A < S =
        Loop.indexed { i =>
            if i >= limit then effect.map(Loop.done)
            else effect.andThen(Loop.continue)
        }

    /** Performs this computation repeatedly while the given condition holds.
      *
      * @param predicate
      *   The condition to check after each iteration
      * @return
      *   A computation that produces the result of the last successful execution before the condition becomes false
      */
    def repeatWhile[S1](predicate: A => Boolean < S1)(using Frame): A < (S & S1) =
        Loop.foreach:
            effect.map: a =>
                predicate(a).map:
                    case true  => Loop.continue
                    case false => Loop.done(a)
    end repeatWhile

    /** Performs this computation repeatedly while the given condition holds.
      *
      * @param predicate
      *   The condition to check after each iteration taking the current value and the number of iterations so far, and returning a tuple
      *   with the condition and the duration to sleep between iterations
      * @return
      *   A computation that produces the result of the last successful execution before the condition becomes false
      */
    def repeatWhile[S1](predicate: (A, Int) => (Boolean, Duration) < S1)(using Frame): A < (S & S1 & Async) =
        Loop.indexed: i =>
            effect.map: a =>
                predicate(a, i).map: (test, wait) =>
                    if test then Async.delay(wait)(Loop.continue)
                    else Loop.done(a)
    end repeatWhile

    /** Performs this computation repeatedly until the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration
      * @return
      *   A computation that produces the result of the first execution where the condition becomes true
      */
    def repeatUntil[S1](predicate: A => Boolean < S1)(using Frame): A < (S & S1 & Async) =
        Loop.foreach:
            effect.map: a =>
                predicate(a).map:
                    case true  => Loop.done(a)
                    case false => Loop.continue
    end repeatUntil

    /** Performs this computation repeatedly until the given condition holds.
      *
      * @param predicate
      *   The condition to check after each iteration taking the current value and the number of iterations so far, and returning a tuple
      *   with the condition and the duration to sleep between iterations
      * @return
      *   A computation that produces the result of the first execution where the condition becomes true
      */
    def repeatUntil[S1](predicate: (A, Int) => (Boolean, Duration) < S1)(using Frame): A < (S & S1 & Async) =
        Loop.indexed: i =>
            effect.map: a =>
                predicate(a, i).map: (done, wait) =>
                    if done then Loop.done(a)
                    else Async.delay(wait)(Loop.continue)
    end repeatUntil

    /** Performs this computation indefinitely.
      *
      * @return
      *   A computation that repeats forever and never completes
      * @note
      *   This method is typically used for long-running processes or servers that should run continuously
      */
    def forever(using Frame): Nothing < S =
        Loop.forever(effect)

    /** Performs this computation when the given condition holds.
      *
      * @param condition
      *   The condition to check
      * @return
      *   A computation that produces the result of this computation wrapped in Present if the condition is satisfied, or Absent if not
      */
    def when[S1](condition: Boolean < S1)(using Frame): Maybe[A] < (S & S1) =
        Kyo.when(condition)(effect)

    /** Performs this computation catching any Throwable in an Abort[Throwable] effect.
      *
      * @return
      *   A computation that produces the result of this computation with Abort[Throwable] effect
      */
    def unpanic(using Frame): A < (S & Abort[Throwable]) =
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
    def unless[S1](condition: Boolean < S1)(using Frame): Maybe[A] < (S & S1) =
        Kyo.unless(condition)(effect)

    /** Ensures that the specified finalizer is executed after this effect, whether it succeeds or fails. The finalizer will execute when
      * the Resource effect is handled.
      *
      * @param finalizer
      *   The finalizer to execute after this effect
      * @return
      *   An effect that executes the finalizer after this effect
      */
    def ensuring(finalizer: => Any < (Async & Abort[Throwable]))(using Frame): A < (S & Scope & Sync) =
        Scope.ensure(finalizer).andThen(effect)

    /** Ensures that the specified finalizer is executed after this effect, whether it succeeds or fails. The finalizer will execute when
      * the Resource effect is handled.
      *
      * If the effect fails, the error is propagated as a `Present`. If the effect succeeds, the 'error' will be `Absent`.
      *
      * @param finalizer
      *   The finalizer to execute after this effect
      * @return
      *   An effect that executes the finalizer after this effect
      */
    def ensuringError(finalizer: Maybe[Error[Any]] => Any < (Async & Abort[Throwable]))(using Frame): A < (S & Scope & Sync) =
        Scope.ensure(finalizer).andThen(effect)

end extension
