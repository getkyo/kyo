package kyo

import Schedule.internal.*
import kyo.Duration
import kyo.Instant

/** An immutable, composable scheduling policy.
  *
  * Schedule provides various combinators for creating complex scheduling policies. It can be used to define retry policies, periodic tasks,
  * or any other time-based scheduling logic.
  */
sealed abstract class Schedule derives CanEqual:

    /** Returns the next delay and the updated schedule.
      *
      * @return
      *   a tuple containing the next delay duration and the updated schedule
      */
    def next: Maybe[(Duration, Schedule)]

    /** Combines this schedule with another, taking the maximum delay of both.
      *
      * @param that
      *   the schedule to combine with
      * @return
      *   a new schedule that produces the maximum delay of both schedules
      */
    def max(that: Schedule): Schedule =
        this match
            case Never            => this
            case Done | Immediate => that
            case _ =>
                that match
                    case Never            => that
                    case Done | Immediate => this
                    case _                => Max(this, that)

    /** Combines this schedule with another, taking the minimum delay of both.
      *
      * @param that
      *   the schedule to combine with
      * @return
      *   a new schedule that produces the minimum delay of both schedules
      */
    def min(that: Schedule): Schedule =
        this match
            case Never            => that
            case Done | Immediate => this
            case _ =>
                that match
                    case Never            => this
                    case Done | Immediate => that
                    case _                => Min(this, that)

    /** Limits the number of repetitions of this schedule.
      *
      * @param n
      *   the maximum number of repetitions
      * @return
      *   a new schedule that stops after n repetitions
      */
    def take(n: Int): Schedule =
        if n <= 0 then Schedule.done
        else
            this match
                case Never | Done => this
                case _            => Take(this, n)

    /** Chains this schedule with another, running the second after the first completes.
      *
      * @param that
      *   the schedule to run after this one
      * @return
      *   a new schedule that runs this schedule followed by the other
      */
    def andThen(that: Schedule): Schedule =
        this match
            case Never => Never
            case Done  => that
            case _     => AndThen(this, that)

    /** Repeats this schedule a specified number of times.
      *
      * @param n
      *   the number of times to repeat
      * @return
      *   a new schedule that repeats this schedule n times
      */
    def repeat(n: Int): Schedule =
        if n <= 0 then Schedule.done
        else if n == 1 then this
        else
            this match
                case Never | Done => this
                case _            => Repeat(this, n)

    /** Limits the total duration of this schedule.
      *
      * @param maxDuration
      *   the maximum total duration
      * @return
      *   a new schedule that stops after the specified duration
      */
    def maxDuration(maxDuration: Duration): Schedule =
        this match
            case Never | Done => this
            case _            => MaxDuration(this, maxDuration)

    /** Repeats this schedule indefinitely.
      *
      * @return
      *   a new schedule that repeats this schedule forever
      */
    def forever: Schedule =
        this match
            case Never | Done => this
            case _            => Forever(this)

    /** Adds a fixed delay before each iteration of this schedule.
      *
      * @param duration
      *   the delay to add
      * @return
      *   a new schedule with the added delay
      */
    def delay(duration: Duration): Schedule =
        this match
            case Never | Done => this
            case _            => Delay(this, duration)

end Schedule

object Schedule:

    /** A schedule that completes once immediately. */
    val immediate: Schedule = Immediate

    /** A schedule that never completes. */
    val never: Schedule = Never

    /** A schedule that is already done. */
    val done: Schedule = Done

    /** A schedule that forever repeats immediately. */
    val forever: Schedule = immediate.forever

    /** Creates a schedule that executes once after a fixed duration.
      *
      * @param duration
      *   the delay duration
      * @return
      *   a new schedule with the specified delay
      */
    def delay(duration: Duration): Schedule =
        immediate.delay(duration)

    /** Creates a schedule that immediately repeats a specified number of times.
      *
      * @param n
      *   the number of repetitions
      * @return
      *   a new schedule that repeats n times
      */
    def repeat(n: Int): Schedule =
        immediate.repeat(n)

    /** Creates a schedule with a fixed interval between iterations.
      *
      * @param interval
      *   the fixed interval
      * @return
      *   a new schedule with the specified fixed interval
      */
    def fixed(interval: Duration): Schedule = Fixed(interval)

    /** Creates a schedule with linearly increasing intervals.
      *
      * @param base
      *   the initial interval
      * @return
      *   a new schedule with linearly increasing intervals
      */
    def linear(base: Duration): Schedule = Linear(base)

    /** Creates a schedule with intervals following the Fibonacci sequence.
      *
      * @param a
      *   the first interval
      * @param b
      *   the second interval
      * @return
      *   a new schedule with Fibonacci sequence intervals
      */
    def fibonacci(a: Duration, b: Duration): Schedule = Fibonacci(a, b)

    /** Creates a schedule with exponentially increasing intervals.
      *
      * @param initial
      *   the initial interval
      * @param factor
      *   the factor by which to increase the interval
      * @return
      *   a new schedule with exponentially increasing intervals
      */
    def exponential(initial: Duration, factor: Double): Schedule = Exponential(initial, factor)

    /** Creates a schedule with exponential backoff and a maximum delay.
      *
      * @param initial
      *   the initial interval
      * @param factor
      *   the factor by which to increase the interval
      * @param maxBackoff
      *   the maximum delay allowed
      * @return
      *   a new schedule with exponential backoff and a maximum delay
      */
    def exponentialBackoff(initial: Duration, factor: Double, maxBackoff: Duration): Schedule =
        ExponentialBackoff(initial, factor, maxBackoff)

    private[kyo] object internal:

        case object Immediate extends Schedule:
            val next = Maybe((Duration.Zero, Done))

        case object Never extends Schedule:
            def next = Maybe.empty

        case object Done extends Schedule:
            def next = Maybe.empty

        case class Fixed(interval: Duration) extends Schedule:
            val next = Maybe((interval, this))

        case class Exponential(initial: Duration, factor: Double) extends Schedule:
            def next = Maybe((initial, Exponential(initial * factor, factor)))

        case class Fibonacci(a: Duration, b: Duration) extends Schedule:
            def next = Maybe((a, Fibonacci(b, a + b)))

        case class ExponentialBackoff(initial: Duration, factor: Double, maxBackoff: Duration) extends Schedule:
            def next =
                val nextDelay = initial.min(maxBackoff)
                Maybe((nextDelay, exponentialBackoff(nextDelay * factor, factor, maxBackoff)))
        end ExponentialBackoff

        case class Linear(base: Duration) extends Schedule:
            def next = Maybe((base, linear(base + base)))

        case class Max(a: Schedule, b: Schedule) extends Schedule:
            def next =
                for
                    (d1, s1) <- a.next
                    (d2, s2) <- b.next
                yield (d1.max(d2), s1.max(s2))
        end Max

        case class Min(a: Schedule, b: Schedule) extends Schedule:
            def next =
                a.next match
                    case Maybe.Empty => b.next
                    case n @ Maybe.Defined((d1, s1)) =>
                        b.next match
                            case Maybe.Empty => n
                            case Maybe.Defined((d2, s2)) =>
                                Maybe((d1.min(d2), s1.min(s2)))
        end Min

        case class Take(schedule: Schedule, remaining: Int) extends Schedule:
            def next =
                schedule.next.map((d, s) => (d, s.take(remaining - 1)))

        case class AndThen(a: Schedule, b: Schedule) extends Schedule:
            def next =
                a.next.map((d, s) => (d, s.andThen(b))).orElse(b.next)

        case class MaxDuration(schedule: Schedule, duration: Duration) extends Schedule:
            def next =
                schedule.next.flatMap { (d, s) =>
                    if d > duration then Maybe.empty
                    else Maybe((d, s.maxDuration(duration - d)))
                }
        end MaxDuration

        case class Repeat(schedule: Schedule, remaining: Int) extends Schedule:
            def next =
                schedule.next.map((d, s) => (d, s.andThen(schedule.repeat(remaining - 1))))

        case class Forever(schedule: Schedule) extends Schedule:
            def next =
                schedule.next.map((d, s) => (d, s.andThen(this)))

        case class Delay(schedule: Schedule, duration: Duration) extends Schedule:
            def next =
                schedule.next.map((d, s) => (duration + d, s.delay(duration)))

    end internal
end Schedule
