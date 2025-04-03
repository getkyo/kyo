package kyo

import Schedule.internal.*
import kyo.Duration

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
    def next(now: Instant): Maybe[(Duration, Schedule)]

    /** Combines this schedule with another, taking the maximum delay of both.
      *
      * @param that
      *   the schedule to combine with
      * @return
      *   a new schedule that produces the maximum delay of both schedules
      */
    final infix def max(that: Schedule): Schedule =
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
    final infix def min(that: Schedule): Schedule =
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

    /** Adds variation to the delays in a schedule.
      *
      * Returns a new schedule that adds jitter to the delays of the original schedule. The actual delay will be the original delay
      * multiplied by (1 + r * factor), where r is a value between -1 and 1. Note: The current implementation derives r using XOR shift
      * operations on a hash of the current Instant and duration. This makes the jitter deterministic rather than truly random.
      *
      * Example: With factor 0.5, delays may be adjusted by up to ±50%
      *
      * @param factor
      *   The maximum proportion of the delay to add or subtract as jitter (e.g. 0.5 means ±50% variation)
      * @return
      *   A new Schedule with jittered delays. If factor is 0, returns the original schedule unchanged
      */
    final def jitter(factor: Double): Schedule =
        if factor == 0d then this
        else
            this match
                case Never | Done => this
                case _            => Jitter(this, factor)

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

    /** Creates a schedule that executes at fixed points in time, aligned to a base period and offset.
      *
      * Anchored schedules maintain alignment with specific points in time, regardless of when executions complete. This means:
      *   - Executions align to consistent time boundaries (e.g., every hour on the hour)
      *   - If an execution is delayed, the next execution time adjusts to maintain alignment
      *   - Multiple missed periods will be caught up with a single execution
      *
      * Examples:
      *   - Schedule.anchored(1.hour) // Hourly on the hour
      *   - Schedule.anchored(1.day, 2.hours) // Daily at 2am
      *   - Schedule.anchored(15.minutes, 5.minutes) // Every 15 minutes, offset by 5 minutes
      *
      * @param period
      *   the regular interval between executions
      * @param offset
      *   optional offset from period boundaries
      * @return
      *   a new schedule that executes at fixed time points
      */
    def anchored(period: Duration, offset: Duration = Duration.Zero): Schedule =
        if period == Duration.Zero then immediate
        else Anchored(period, offset, Maybe.empty)

    object internal:

        case object Immediate extends Schedule:
            val _next              = Maybe((Duration.Zero, Done))
            def next(now: Instant) = _next
            def show               = "Schedule.immediate"
        end Immediate

        case object Never extends Schedule:
            def next(now: Instant) = Maybe.empty
            def show               = "Schedule.never"

        case object Done extends Schedule:
            def next(now: Instant) = Maybe.empty
            def show               = "Schedule.done"

        final case class Fixed(interval: Duration) extends Schedule:
            val _next              = Maybe((interval, this))
            def next(now: Instant) = _next
            def show               = s"Schedule.fixed(${interval.show})"
        end Fixed

        final case class Exponential(initial: Duration, factor: Double) extends Schedule:
            def next(now: Instant) = Maybe((initial, Exponential(initial * factor, factor)))
            def show               = s"Schedule.exponential(${initial.show}, ${formatDouble(factor)})"

        final case class Fibonacci(a: Duration, b: Duration) extends Schedule:
            def next(now: Instant) = Maybe((a, Fibonacci(b, a + b)))
            def show               = s"Schedule.fibonacci(${a.show}, ${b.show})"

        final case class ExponentialBackoff(initial: Duration, factor: Double, maxBackoff: Duration) extends Schedule:
            def next(now: Instant) =
                val nextDelay = initial.min(maxBackoff)
                Maybe((nextDelay, exponentialBackoff(nextDelay * factor, factor, maxBackoff)))
            def show = s"Schedule.exponentialBackoff(${initial.show}, ${formatDouble(factor)}, ${maxBackoff.show})"
        end ExponentialBackoff

        final case class Linear(base: Duration) extends Schedule:
            def next(now: Instant) = Maybe((base, linear(base + base)))
            def show               = s"Schedule.linear(${base.show})"

        final case class Anchored(period: Duration, offset: Duration, last: Maybe[Instant]) extends Schedule:
            def next(now: Instant): Maybe[(Duration, Schedule)] =
                val reference   = last.getOrElse(now)
                val periodNanos = period.toNanos.max(1)

                val elapsed  = (reference - Instant.Epoch).toNanos % periodNanos
                val nextTime = reference - elapsed.nanos + offset

                val finalTime =
                    if nextTime <= now then
                        val periodsToSkip = ((now - nextTime).toNanos / periodNanos) + 1
                        nextTime + (periodsToSkip * periodNanos).nanos
                    else nextTime

                Maybe((finalTime - now, Anchored(period, offset, Present(finalTime))))
            end next

            def show =
                val offsetStr = if offset == Duration.Zero then "" else s", ${offset.show}"
                s"Schedule.anchored(${period.show}$offsetStr)"
        end Anchored

        final case class Max(a: Schedule, b: Schedule) extends Schedule:
            def next(now: Instant) =
                for
                    (d1, s1) <- a.next(now)
                    (d2, s2) <- b.next(now)
                yield (d1.max(d2), s1.max(s2))
            def show = s"(${a.show}).max(${b.show})"
        end Max

        final case class Min(a: Schedule, b: Schedule) extends Schedule:
            def next(now: Instant) =
                a.next(now) match
                    case Absent => b.next(now)
                    case n @ Present((d1, s1)) =>
                        b.next(now) match
                            case Absent => n
                            case Present((d2, s2)) =>
                                Maybe((d1.min(d2), s1.min(s2)))
            def show = s"(${a.show}).min(${b.show})"
        end Min

        final case class Take(schedule: Schedule, remaining: Int) extends Schedule:
            def next(now: Instant) =
                schedule.next(now).map((d, s) => (d, s.take(remaining - 1)))
            def show = s"(${schedule.show}).take($remaining)"
        end Take

        final case class AndThen(a: Schedule, b: Schedule) extends Schedule:
            def next(now: Instant) =
                a.next(now).map((d, s) => (d, s.andThen(b))).orElse(b.next(now))
            def show = s"(${a.show}).andThen(${b.show})"
        end AndThen

        final case class MaxDuration(schedule: Schedule, duration: Duration) extends Schedule:
            def next(now: Instant) =
                schedule.next(now).flatMap { (d, s) =>
                    if d > duration then Maybe.empty
                    else Maybe((d, s.maxDuration(duration - d)))
                }
            def show = s"(${schedule.show}).maxDuration(${duration.show})"
        end MaxDuration

        final case class Repeat(schedule: Schedule, remaining: Int) extends Schedule:
            def next(now: Instant) =
                schedule.next(now).map((d, s) => (d, s.andThen(schedule.repeat(remaining - 1))))
            def show = s"(${schedule.show}).repeat($remaining)"
        end Repeat

        final case class Forever(schedule: Schedule) extends Schedule:
            def next(now: Instant) =
                schedule.next(now).map((d, s) => (d, s.andThen(this)))
            def show = s"(${schedule.show}).forever"
        end Forever

        final case class Delay(schedule: Schedule, duration: Duration) extends Schedule:
            def next(now: Instant) =
                schedule.next(now).map((d, s) => (duration + d, s.delay(duration)))
            def show = s"(${schedule.show}).delay(${duration.show})"
        end Delay

        final case class Jitter(schedule: Schedule, factor: Double) extends Schedule:
            def next(now: Instant): Maybe[(Duration, Schedule)] =
                schedule.next(now).map { (duration, nextSchedule) =>
                    val x    = now.hashCode() + (31 * duration.hashCode())
                    val y    = x ^ (x << 13)
                    val z    = y ^ (y >> 7)
                    val hash = z ^ (z << 17)

                    val jitterFactor = (hash.toDouble / Int.MaxValue) * factor
                    val jittered     = duration * (1.0 + jitterFactor)
                    (jittered, Jitter(nextSchedule, factor))
                }

            def show: String = s"(${schedule.show}).jitter(${formatDouble(factor)})"
        end Jitter

        private def formatDouble(d: Double): String =
            if d == d.toLong then f"$d%.1f" else d.toString

    end internal
end Schedule
