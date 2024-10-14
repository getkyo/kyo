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
    final def max(that: Schedule): Schedule =
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
    final def min(that: Schedule): Schedule =
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
    final def take(n: Int): Schedule =
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
    final def andThen(that: Schedule): Schedule =
        this match
            case Never => Never
            case Done  => that
            case _ =>
                that match
                    case Done | Never | Immediate => this
                    case _                        => AndThen(this, that)

    /** Repeats this schedule a specified number of times.
      *
      * @param n
      *   the number of times to repeat
      * @return
      *   a new schedule that repeats this schedule n times
      */
    final def repeat(n: Int): Schedule =
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
    final def maxDuration(maxDuration: Duration): Schedule =
        if !maxDuration.isFinite then this
        else
            this match
                case Never | Done | Immediate => this
                case _                        => MaxDuration(this, maxDuration)

    /** Repeats this schedule indefinitely.
      *
      * @return
      *   a new schedule that repeats this schedule forever
      */
    final def forever: Schedule =
        this match
            case Never | Done => this
            case _: Forever   => this
            case _            => Forever(this)

    /** Adds a fixed delay before each iteration of this schedule.
      *
      * @param duration
      *   the delay to add
      * @return
      *   a new schedule with the added delay
      */
    final def delay(duration: Duration): Schedule =
        if duration == Duration.Zero then this
        else
            this match
                case Never | Done => this
                case _            => Delay(this, duration)

    /** Returns a string representation of the schedule as it would appear in source code.
      *
      * @return
      *   a string representation of the schedule
      */
    def show: String

    override def toString() = show

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
    def linear(base: Duration): Schedule =
        if base == Duration.Zero then immediate.forever
        else Linear(base)

    /** Creates a schedule with intervals following the Fibonacci sequence.
      *
      * @param a
      *   the first interval
      * @param b
      *   the second interval
      * @return
      *   a new schedule with Fibonacci sequence intervals
      */
    def fibonacci(a: Duration, b: Duration): Schedule =
        if a == Duration.Zero && b == Duration.Zero then immediate.forever
        else Fibonacci(a, b)

    /** Creates a schedule with exponentially increasing intervals.
      *
      * @param initial
      *   the initial interval
      * @param factor
      *   the factor by which to increase the interval
      * @return
      *   a new schedule with exponentially increasing intervals
      */
    def exponential(initial: Duration, factor: Double): Schedule =
        if initial == Duration.Zero then immediate
        else if factor == 1.0 then fixed(initial)
        else Exponential(initial, factor)

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
        if initial == Duration.Zero then immediate
        else if factor == 1.0 then fixed(initial)
        else ExponentialBackoff(initial, factor, maxBackoff)

    private[kyo] object internal:

        case object Immediate extends Schedule:
            val next = Maybe((Duration.Zero, Done))
            def show = "Schedule.immediate"

        case object Never extends Schedule:
            def next = Maybe.empty
            def show = "Schedule.never"

        case object Done extends Schedule:
            def next = Maybe.empty
            def show = "Schedule.done"

        final case class Fixed(interval: Duration) extends Schedule:
            val next = Maybe((interval, this))
            def show = s"Schedule.fixed(${interval.show})"

        final case class Exponential(initial: Duration, factor: Double) extends Schedule:
            def next = Maybe((initial, Exponential(initial * factor, factor)))
            def show = s"Schedule.exponential(${initial.show}, ${formatDouble(factor)})"

        final case class Fibonacci(a: Duration, b: Duration) extends Schedule:
            def next = Maybe((a, Fibonacci(b, a + b)))
            def show = s"Schedule.fibonacci(${a.show}, ${b.show})"

        final case class ExponentialBackoff(initial: Duration, factor: Double, maxBackoff: Duration) extends Schedule:
            def next =
                val nextDelay = initial.min(maxBackoff)
                Maybe((nextDelay, exponentialBackoff(nextDelay * factor, factor, maxBackoff)))
            def show = s"Schedule.exponentialBackoff(${initial.show}, ${formatDouble(factor)}, ${maxBackoff.show})"
        end ExponentialBackoff

        final case class Linear(base: Duration) extends Schedule:
            def next = Maybe((base, linear(base + base)))
            def show = s"Schedule.linear(${base.show})"

        final case class Max(a: Schedule, b: Schedule) extends Schedule:
            def next =
                for
                    (d1, s1) <- a.next
                    (d2, s2) <- b.next
                yield (d1.max(d2), s1.max(s2))
            def show = s"(${a.show}).max(${b.show})"
        end Max

        final case class Min(a: Schedule, b: Schedule) extends Schedule:
            def next =
                a.next match
                    case Maybe.Empty => b.next
                    case n @ Maybe.Defined((d1, s1)) =>
                        b.next match
                            case Maybe.Empty => n
                            case Maybe.Defined((d2, s2)) =>
                                Maybe((d1.min(d2), s1.min(s2)))
            def show = s"(${a.show}).min(${b.show})"
        end Min

        final case class Take(schedule: Schedule, remaining: Int) extends Schedule:
            def next =
                schedule.next.map((d, s) => (d, s.take(remaining - 1)))
            def show = s"(${schedule.show}).take($remaining)"
        end Take

        final case class AndThen(a: Schedule, b: Schedule) extends Schedule:
            def next =
                a.next.map((d, s) => (d, s.andThen(b))).orElse(b.next)
            def show = s"(${a.show}).andThen(${b.show})"
        end AndThen

        final case class MaxDuration(schedule: Schedule, duration: Duration) extends Schedule:
            def next =
                schedule.next.flatMap { (d, s) =>
                    if d > duration then Maybe.empty
                    else Maybe((d, s.maxDuration(duration - d)))
                }
            def show = s"(${schedule.show}).maxDuration(${duration.show})"
        end MaxDuration

        final case class Repeat(schedule: Schedule, remaining: Int) extends Schedule:
            def next =
                schedule.next.map((d, s) => (d, s.andThen(schedule.repeat(remaining - 1))))
            def show = s"(${schedule.show}).repeat($remaining)"
        end Repeat

        final case class Forever(schedule: Schedule) extends Schedule:
            def next =
                schedule.next.map((d, s) => (d, s.andThen(this)))
            def show = s"(${schedule.show}).forever"
        end Forever

        final case class Delay(schedule: Schedule, duration: Duration) extends Schedule:
            def next =
                schedule.next.map((d, s) => (duration + d, s.delay(duration)))
            def show = s"(${schedule.show}).delay(${duration.show})"
        end Delay

        private def formatDouble(d: Double): String =
            if d == d.toLong then f"$d%.1f" else d.toString

    end internal
end Schedule
