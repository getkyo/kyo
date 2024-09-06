package kyo

import java.time.Instant

abstract class Clock:
    def now(using Frame): Instant < IO
    def stopwatch(using Frame): Clock.Stopwatch < IO
    def deadline(duration: Duration)(using Frame): Clock.Deadline < IO
    def unsafe: Clock.Unsafe
end Clock

object Clock:

    abstract class Unsafe:
        def now: Instant
        def stopwatch: Unsafe.Stopwatch
        def deadline(duration: Duration): Unsafe.Deadline
    end Unsafe

    object Unsafe:
        class Stopwatch(start: Instant, clock: Clock.Unsafe):
            def elapsed: Duration =
                Duration.fromJava(java.time.Duration.between(start, clock.now))

        class Deadline(end: Instant, clock: Clock.Unsafe):
            def timeLeft: Duration =
                val remaining = java.time.Duration.between(clock.now, end)
                if remaining.isNegative then Duration.Zero else Duration.fromJava(remaining)
            def isOverdue: Boolean = clock.now.isAfter(end)
        end Deadline
    end Unsafe

    abstract class Stopwatch:
        def elapsed(using Frame): Duration < IO
        def unsafe: Unsafe.Stopwatch

    abstract class Deadline:
        def timeLeft(using Frame): Duration < IO
        def isOverdue(using Frame): Boolean < IO
        def unsafe: Unsafe.Deadline
    end Deadline

    val live: Clock = new Clock:
        val unsafe: Unsafe = new Unsafe:
            def now: Instant                                  = Instant.now()
            def stopwatch: Unsafe.Stopwatch                   = new Unsafe.Stopwatch(now, this)
            def deadline(duration: Duration): Unsafe.Deadline = new Unsafe.Deadline(now.plus(duration.toJava), this)

        def now(using Frame): Instant < IO = IO(unsafe.now)
        def stopwatch(using Frame): Stopwatch < IO = IO {
            new Stopwatch:
                val unsafe                              = Clock.live.unsafe.stopwatch
                def elapsed(using Frame): Duration < IO = IO(unsafe.elapsed)
        }
        def deadline(duration: Duration)(using Frame): Deadline < IO = IO {
            new Deadline:
                val unsafe                               = Clock.live.unsafe.deadline(duration)
                def timeLeft(using Frame): Duration < IO = IO(unsafe.timeLeft)
                def isOverdue(using Frame): Boolean < IO = IO(unsafe.isOverdue)
        }

    private val local = Local.init(live)

    def let[A, S](c: Clock)(f: => A < S)(using Frame): A < S =
        local.let(c)(f)

    def now(using Frame): Instant < IO =
        local.use(_.now)

    def stopwatch(using Frame): Stopwatch < IO =
        local.use(_.stopwatch)

    def deadline(duration: Duration)(using Frame): Deadline < IO =
        local.use(_.deadline(duration))
end Clock
