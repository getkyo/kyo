package kyo

import kyo.debug.Debug
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
    def delay(duration: Duration)(using Frame): A < (S & Async) =
        Async.delay(duration)(effect)

    /** Performs this computation repeatedly with a backoff policy.
      *
      * @param policy
      *   The backoff policy to use
      * @return
      *   A computation that produces the result of the last execution
      */
    def repeatAtInterval(intervalSchedule: Schedule)(using Frame): A < (S & Async) =
        Clock.use { clock =>
            Loop(intervalSchedule) { schedule =>
                clock.now.map { now =>
                    schedule.next(now).map { (delay, nextSchedule) =>
                        effect.delay(delay).andThen(Loop.continue(nextSchedule))
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
      * @param fn
      *   The condition to check after each iteration
      * @return
      *   A computation that produces the result of the last successful execution before the condition becomes false
      */
    def repeatWhile[S1](fn: A => Boolean < S1)(using Frame): A < (S & S1) =
        Loop.foreach:
            effect.map: a =>
                fn(a).map: test =>
                    if test then Loop.continue
                    else Loop.done(a)
    end repeatWhile

    /** Performs this computation repeatedly while the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration taking the current value and the number of iterations so far, and returning a tuple
      *   with the condition and the duration to sleep between iterations
      * @return
      *   A computation that produces the result of the last successful execution before the condition becomes false
      */
    def repeatWhile[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using Frame): A < (S & S1 & Async) =
        Loop.indexed: i =>
            effect.map: a =>
                fn(a, i).map:
                    case (test, wait) =>
                        if test then Kyo.sleep(wait).andThen(Loop.continue)
                        else Loop.done(a)
    end repeatWhile

    /** Performs this computation repeatedly until the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration
      * @return
      *   A computation that produces the result of the first execution where the condition becomes true
      */
    def repeatUntil[S1](fn: A => Boolean < S1)(using Frame): A < (S & S1 & Async) =
        Loop.foreach:
            effect.map: a =>
                fn(a).map: test =>
                    if test then Loop.done(a)
                    else Loop.continue
    end repeatUntil

    /** Performs this computation repeatedly until the given condition holds.
      *
      * @param fn
      *   The condition to check after each iteration taking the current value and the number of iterations so far, and returning a tuple
      *   with the condition and the duration to sleep between iterations
      * @return
      *   A computation that produces the result of the first execution where the condition becomes true
      */
    def repeatUntil[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using Frame): A < (S & S1 & Async) =
        Loop.indexed: i =>
            effect.map: a =>
                fn(a, i).map:
                    case (test, wait) =>
                        if test then Loop.done(a)
                        else Kyo.sleep(wait).andThen(Loop.continue)
    end repeatUntil

    /** Performs this computation indefinitely.
      *
      * @return
      *   A computation that repeats forever and never completes
      * @note
      *   This method is typically used for long-running processes or servers that should run continuously
      */
    def forever(using Frame): Nothing < S =
        Loop.forever(effect).andThen:
            bug(s"Loop.forever completed successfully")

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
        condition.map(c => if c then Absent else effect.map(Present(_)))

    /** Ensures that the specified finalizer is executed after this effect, whether it succeeds or fails. The finalizer will execute when
      * the Resource effect is handled.
      *
      * @param finalizer
      *   The finalizer to execute after this effect
      * @return
      *   An effect that executes the finalizer after this effect
      */
    def ensuring(finalizer: => Any < Async)(using Frame): A < (S & Resource & Sync) =
        Resource.ensure(finalizer).andThen(effect)

end extension
