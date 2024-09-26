package kyo

import java.time.Instant
import kyo.Clock.Deadline
import kyo.Clock.Stopwatch

/** A clock that provides time-related operations.
  */
abstract class Clock:
    def unsafe: Clock.Unsafe

    /** Gets the current time as an Instant.
      *
      * @return
      *   The current time
      */
    def now(using Frame): Instant < IO

    /** Creates a new stopwatch.
      *
      * @return
      *   A new Stopwatch instance
      */
    def stopwatch(using Frame): Clock.Stopwatch < IO = IO(unsafe.stopwatch().safe)

    /** Creates a new deadline with the specified duration.
      *
      * @param duration
      *   The duration for the deadline
      * @return
      *   A new Deadline instance
      */
    def deadline(duration: Duration)(using Frame): Clock.Deadline < IO = IO(unsafe.deadline(duration).safe)
end Clock

/** Companion object for creating and managing Clock instances. */
object Clock:

    /** A stopwatch for measuring elapsed time. */
    final case class Stopwatch private[Clock] (unsafe: Stopwatch.Unsafe) extends AnyVal:
        /** Gets the elapsed time since the stopwatch was created.
          *
          * @return
          *   The elapsed time as a Duration
          */
        def elapsed(using Frame): Duration < IO = IO(unsafe.elapsed())
    end Stopwatch

    object Stopwatch:
        class Unsafe(start: Instant, clock: Clock.Unsafe):
            def elapsed()(using AllowUnsafe): Duration =
                Duration.fromJava(java.time.Duration.between(start, clock.now()))
            def safe: Stopwatch = Stopwatch(this)
        end Unsafe
    end Stopwatch

    /** A deadline for checking remaining time or if it's overdue. */
    final case class Deadline private[Clock] (unsafe: Deadline.Unsafe) extends AnyVal:
        /** Gets the time left until the deadline.
          *
          * @return
          *   The remaining time as a Duration
          */
        def timeLeft(using Frame): Duration < IO = IO(unsafe.timeLeft())

        /** Checks if the deadline is overdue.
          *
          * @return
          *   A boolean indicating if the deadline is overdue
          */
        def isOverdue(using Frame): Boolean < IO = IO(unsafe.isOverdue())
    end Deadline

    object Deadline:
        class Unsafe(end: Instant, clock: Clock.Unsafe):
            def timeLeft()(using AllowUnsafe): Duration =
                val remaining = java.time.Duration.between(clock.now(), end)
                if remaining.isNegative then Duration.Zero else Duration.fromJava(remaining)
            def isOverdue()(using AllowUnsafe): Boolean = clock.now().isAfter(end)
            def safe: Deadline                          = Deadline(this)
        end Unsafe
    end Deadline

    /** A live Clock instance using the system clock. */
    val live: Clock =
        Clock(
            new Unsafe:
                def now()(using AllowUnsafe): Instant = Instant.now()
        )

    private val local = Local.init(live)

    /** Runs an effect with a specific Clock instance.
      *
      * @param c
      *   The Clock instance to use
      * @param f
      *   The effect to run
      * @return
      *   The result of running the effect
      */
    def let[A, S](c: Clock)(f: => A < S)(using Frame): A < S =
        local.let(c)(f)

    /** Gets the current time using the local Clock instance.
      *
      * @return
      *   The current time
      */
    def now(using Frame): Instant < IO =
        local.use(_.now)

    /** Creates a new stopwatch using the local Clock instance.
      *
      * @return
      *   A new Stopwatch instance
      */
    def stopwatch(using Frame): Stopwatch < IO =
        local.use(_.stopwatch)

    /** Creates a new deadline with the specified duration using the local Clock instance.
      *
      * @param duration
      *   The duration for the deadline
      * @return
      *   A new Deadline instance
      */
    def deadline(duration: Duration)(using Frame): Deadline < IO =
        local.use(_.deadline(duration))

    /** Creates a new Clock instance from an Unsafe implementation.
      *
      * @param u
      *   The Unsafe implementation
      * @return
      *   A new Clock instance
      */
    def apply(u: Unsafe): Clock =
        new Clock:
            def now(using Frame): Instant < IO =
                IO(u.now())
            def unsafe: Unsafe = u

    abstract class Unsafe:
        def now()(using AllowUnsafe): Instant
        def stopwatch()(using AllowUnsafe): Stopwatch.Unsafe                 = Stopwatch.Unsafe(now(), this)
        def deadline(duration: Duration)(using AllowUnsafe): Deadline.Unsafe = Deadline.Unsafe(now().plus(duration.toJava), this)
        def safe: Clock                                                      = Clock(this)
    end Unsafe
end Clock
