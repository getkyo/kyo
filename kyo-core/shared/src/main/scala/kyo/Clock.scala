package kyo

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kyo.Clock.Deadline
import kyo.Clock.Stopwatch
import kyo.scheduler.IOPromise
import kyo.scheduler.util.Threads
import scala.annotation.tailrec
import scala.collection.mutable.PriorityQueue

/** A clock that provides time-related operations.
  */
final case class Clock(unsafe: Clock.Unsafe):

    /** Gets the current time as an Instant.
      *
      * @return
      *   The current time
      */
    def now(using Frame): Instant < IO = IO.Unsafe(unsafe.now())

    /** Gets the current monotonic time as a Duration. Unlike `now`, this is guaranteed to be strictly monotonic and suitable for measuring
      * elapsed time.
      *
      * Returns a Duration rather than an Instant because monotonic time represents the time elapsed since some arbitrary starting point
      * (usually system boot), not a specific point in calendar time.
      *
      * @return
      *   The current monotonic time as a Duration since system start
      */
    def nowMonotonic(using Frame): Duration < IO = IO.Unsafe(unsafe.nowMonotonic())

    /** Creates a new stopwatch.
      *
      * @return
      *   A new Stopwatch instance
      */
    def stopwatch(using Frame): Clock.Stopwatch < IO = IO.Unsafe(unsafe.stopwatch().safe)

    /** Creates a new deadline with the specified duration.
      *
      * @param duration
      *   The duration for the deadline
      * @return
      *   A new Deadline instance
      */
    def deadline(duration: Duration)(using Frame): Clock.Deadline < IO = IO.Unsafe(unsafe.deadline(duration).safe)

    private[kyo] def sleep(duration: Duration)(using Frame): Fiber[Nothing, Unit] < IO =
        if duration == Duration.Zero then Fiber.unit
        else if !duration.isFinite then Fiber.never
        else IO.Unsafe(unsafe.sleep(duration).safe)
end Clock

/** Companion object for creating and managing Clock instances. */
object Clock:

    /** A stopwatch for measuring elapsed time. */
    final case class Stopwatch private[Clock] (unsafe: Stopwatch.Unsafe):
        /** Gets the elapsed time since the stopwatch was created.
          *
          * @return
          *   The elapsed time as a Duration
          */
        def elapsed(using Frame): Duration < IO = IO.Unsafe(unsafe.elapsed())
    end Stopwatch

    object Stopwatch:
        /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
        final class Unsafe(start: Duration, clock: Clock.Unsafe):
            def elapsed()(using AllowUnsafe): Duration = clock.nowMonotonic() - start
            def safe: Stopwatch                        = Stopwatch(this)
        end Unsafe
    end Stopwatch

    /** A deadline for checking remaining time or if it's overdue. */
    final case class Deadline private[Clock] (unsafe: Deadline.Unsafe):
        /** Gets the time left until the deadline.
          *
          * @return
          *   The remaining time as a Duration
          */
        def timeLeft(using Frame): Duration < IO = IO.Unsafe(unsafe.timeLeft())

        /** Checks if the deadline is overdue.
          *
          * @return
          *   A boolean indicating if the deadline is overdue
          */
        def isOverdue(using Frame): Boolean < IO = IO.Unsafe(unsafe.isOverdue())
    end Deadline

    object Deadline:
        /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
        final class Unsafe(endInstant: Maybe[Instant], clock: Clock.Unsafe):

            def timeLeft()(using AllowUnsafe): Duration =
                endInstant.map(_ - clock.now()).getOrElse(Duration.Infinity)

            def isOverdue()(using AllowUnsafe): Boolean = endInstant.exists(_ < clock.now())

            def safe: Deadline = Deadline(this)
        end Unsafe
    end Deadline

    /** A live Clock instance using the system clock. */
    val live: Clock =
        import AllowUnsafe.embrace.danger
        Clock(Unsafe(Executors.newScheduledThreadPool(2, Threads("kyo-core-clock-executor"))))

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

    /** Gets the current Clock instance from the local context.
      *
      * This is the primary way to access the current Clock instance when you only need to use it directly.
      *
      * @return
      *   The current Clock instance
      */
    def get(using Frame): Clock < Any =
        local.get

    /** Uses the current Clock instance from the local context to perform an operation.
      *
      * This is useful when you want to perform multiple operations with the same Clock instance without having to repeatedly access it.
      *
      * @param f
      *   A function that takes a Clock and returns an effect
      * @return
      *   The result of applying the function to the current Clock
      */
    def use[A, S](f: Clock => A < S)(using Frame): A < S =
        local.use(f)

    /** Runs an effect with a time-shifted Clock where time appears to pass faster or slower. This is particularly useful for testing
      * time-dependent behavior without waiting for real time to pass.
      *
      * @param factor
      *   The time scaling factor. Values > 1 speed up time, values < 1 slow down time
      * @param v
      *   The effect to run with scaled time
      * @return
      *   The result of running the effect with scaled time
      */
    def withTimeShift[A, S](factor: Double)(v: => A < S)(using Frame): A < (IO & S) =
        println(1)
        if factor == 1 then v
        else
            IO.Unsafe.withLocal(local) { clock =>
                val shifted =
                    new Unsafe:
                        val underlying  = clock.unsafe
                        val start       = underlying.now()
                        val sleepFactor = (1.toDouble / factor)
                        def nowMonotonic()(using AllowUnsafe) =
                            now().toDuration
                        def now()(using AllowUnsafe) =
                            val diff = underlying.now() - start
                            start + (diff * factor)
                        end now
                        override def sleep(duration: Duration) =
                            underlying.sleep(duration * sleepFactor)
                let(Clock(shifted))(v)
            }
        end if
    end withTimeShift

    /** Interface for controlling time in a test environment.
      *
      * WARNING: TimeControl is not thread-safe. All operations should be performed sequentially to avoid race conditions and unexpected
      * behavior.
      */
    trait TimeControl:
        /** Sets the current time to a specific instant.
          *
          * @param now
          *   The instant to set the current time to
          * @return
          *   Unit effect that updates the current time
          */
        def set(now: Instant): Unit < IO

        /** Advances the current time by the specified duration.
          *
          * @param duration
          *   The duration to advance time by
          * @return
          *   Unit effect that advances the current time
          */
        def advance(duration: Duration): Unit < IO
    end TimeControl

    /** Runs an effect with a controlled Clock that allows manual time manipulation. This is primarily intended for testing scenarios where
      * precise control over time progression is needed.
      *
      * Note: TimeControl is not thread-safe. Operations on TimeControl should be performed sequentially within the same fiber.
      *
      * @param f
      *   A function that takes a TimeControl and returns an effect
      * @return
      *   The result of running the effect with controlled time
      */
    def withTimeControl[A, S](f: TimeControl => A < S)(using Frame): A < (IO & S) =
        IO.Unsafe {
            val controlled =
                new Unsafe with TimeControl:
                    @volatile var current = Instant.Epoch

                    case class Task(deadline: Instant) extends IOPromise[Nothing, Unit]
                    val queue = new PriorityQueue[Task](using Ordering.fromLessThan((a, b) => b.deadline < a.deadline))

                    def now()(using AllowUnsafe) = current

                    def nowMonotonic()(using AllowUnsafe) = current.toDuration

                    def sleep(duration: Duration): Fiber.Unsafe[Nothing, Unit] =
                        val task = new Task(current + duration)
                        queue.synchronized {
                            queue.enqueue(task)
                        }
                        Promise.Unsafe.fromIOPromise(task)
                    end sleep

                    def set(now: Instant) =
                        IO {
                            current = now
                            tick()
                        }

                    def advance(duration: Duration) =
                        IO {
                            current = current + duration
                            tick()
                        }

                    def tick(): Unit =
                        queue.synchronized {
                            queue.headOption match
                                case Some(task) if task.deadline <= current =>
                                    Maybe(queue.dequeue())
                                case Some(task) if task.done() =>
                                    discard(queue.dequeue())
                                    Maybe.empty
                                case _ =>
                                    Maybe.empty
                        } match
                            case Present(task) =>
                                task.completeDiscard(Result.unit)
                                tick()
                            case Absent =>
                                ()
            let(Clock(controlled))(f(controlled))
        }
    end withTimeControl

    /** Gets the current time using the local Clock instance.
      *
      * @return
      *   The current time
      */
    def now(using Frame): Instant < IO =
        IO.Unsafe.withLocal(local)(_.unsafe.now())

    /** Gets the current monotonic time using the local Clock instance. Unlike `now`, this is guaranteed to be strictly monotonic and
      * suitable for measuring elapsed time.
      *
      * Returns a Duration rather than an Instant because monotonic time represents the time elapsed since some arbitrary starting point
      * (usually system boot), not a specific point in calendar time.
      *
      * @return
      *   The current monotonic time as a Duration since system start
      */
    def nowMonotonic(using Frame): Duration < IO =
        IO.Unsafe.withLocal(local)(_.unsafe.nowMonotonic())

    private[kyo] def sleep(duration: Duration)(using Frame): Fiber[Nothing, Unit] < IO =
        IO.Unsafe.withLocal(local)(_.unsafe.sleep(duration).safe)

    /** Creates a new stopwatch using the local Clock instance.
      *
      * @return
      *   A new Stopwatch instance
      */
    def stopwatch(using Frame): Stopwatch < IO =
        IO.Unsafe.withLocal(local)(_.unsafe.stopwatch().safe)

    /** Creates a new deadline with the specified duration using the local Clock instance.
      *
      * @param duration
      *   The duration for the deadline
      * @return
      *   A new Deadline instance
      */
    def deadline(duration: Duration)(using Frame): Deadline < IO =
        IO.Unsafe.withLocal(local)(_.unsafe.deadline(duration).safe)

    /** Repeatedly executes a task with a fixed delay between completions.
      *
      * The delay timer starts after each task completion, making this suitable for tasks that should maintain a minimum gap between
      * executions.
      *
      * @param delay
      *   The duration to wait after each task completion before starting the next execution
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatWithDelay[E, S](delay: Duration)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatWithDelay(Duration.Zero, delay)(f)

    /** Repeatedly executes a task with a fixed delay between completions, starting after an initial delay.
      *
      * @param startAfter
      *   The duration to wait before the first execution
      * @param delay
      *   The duration to wait after each task completion before starting the next execution
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatWithDelay[E, S](
        startAfter: Duration,
        delay: Duration
    )(
        f: => Unit < (Async & Abort[E])
    )(using Frame): Fiber[E, Unit] < IO =
        repeatWithDelay(startAfter, delay, ())(_ => f)

    /** Repeatedly executes a task with a fixed delay between completions, maintaining state between executions.
      *
      * @param startAfter
      *   The duration to wait before the first execution
      * @param delay
      *   The duration to wait after each task completion before starting the next execution
      * @param state
      *   The initial state value
      * @param f
      *   A function that takes the current state and returns the next state
      * @tparam A
      *   The type of the state value
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task and access the final state
      */
    def repeatWithDelay[E, A: Flat, S](
        startAfter: Duration,
        delay: Duration,
        state: A
    )(
        f: A => A < (Async & Abort[E])
    )(using Frame): Fiber[E, A] < IO =
        repeatWithDelay(Schedule.delay(startAfter).andThen(Schedule.fixed(delay)), state)(f)

    /** Repeatedly executes a task with delays determined by a custom schedule.
      *
      * @param delaySchedule
      *   A schedule that determines the timing between executions
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatWithDelay[E, S](delaySchedule: Schedule)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatWithDelay(delaySchedule, ())(_ => f)

    /** Repeatedly executes a task with delays determined by a custom schedule, maintaining state between executions.
      *
      * @param delaySchedule
      *   A schedule that determines the timing between executions
      * @param state
      *   The initial state value
      * @param f
      *   A function that takes the current state and returns the next state
      * @tparam A
      *   The type of the state value
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task and access the final state
      */
    def repeatWithDelay[E, A: Flat, S](
        delaySchedule: Schedule,
        state: A
    )(
        f: A => A < (Async & Abort[E])
    )(using Frame): Fiber[E, A] < IO =
        Async.run {
            Clock.use { clock =>
                Loop(state, delaySchedule) { (state, schedule) =>
                    clock.now.map { now =>
                        schedule.next(now) match
                            case Absent => Loop.done(state)
                            case Present((duration, nextSchedule)) =>
                                clock.sleep(duration).map(_.use(_ => f(state).map(Loop.continue(_, nextSchedule))))
                    }
                }
            }
        }

    /** Repeatedly executes a task at fixed time intervals.
      *
      * Unlike repeatWithDelay, this ensures consistent execution intervals regardless of task duration. If a task takes longer than the
      * interval, the next execution will start immediately after completion.
      *
      * @param interval
      *   The fixed time interval between task starts
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatAtInterval[E, S](interval: Duration)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatAtInterval(Duration.Zero, interval)(f)

    /** Repeatedly executes a task at fixed time intervals, starting after an initial delay.
      *
      * @param startAfter
      *   The duration to wait before the first execution
      * @param interval
      *   The fixed time interval between task starts
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatAtInterval[E, S](
        startAfter: Duration,
        interval: Duration
    )(
        f: => Unit < (Async & Abort[E])
    )(using Frame): Fiber[E, Unit] < IO =
        repeatAtInterval(startAfter, interval, ())(_ => f)

    /** Repeatedly executes a task at fixed time intervals, maintaining state between executions.
      *
      * @param startAfter
      *   The duration to wait before the first execution
      * @param interval
      *   The fixed time interval between task starts
      * @param state
      *   The initial state value
      * @param f
      *   A function that takes the current state and returns the next state
      * @tparam A
      *   The type of the state value
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task and access the final state
      */
    def repeatAtInterval[E, A: Flat, S](
        startAfter: Duration,
        interval: Duration,
        state: A
    )(
        f: A => A < (Async & Abort[E])
    )(using Frame): Fiber[E, A] < IO =
        repeatAtInterval(Schedule.delay(startAfter).andThen(Schedule.fixed(interval)), state)(f)

    /** Repeatedly executes a task with intervals determined by a custom schedule.
      *
      * @param intervalSchedule
      *   A schedule that determines the timing between executions
      * @param f
      *   The task to execute
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task
      */
    def repeatAtInterval[E, S](intervalSchedule: Schedule)(f: => Unit < (Async & Abort[E]))(using Frame): Fiber[E, Unit] < IO =
        repeatAtInterval(intervalSchedule, ())(_ => f)

    /** Repeatedly executes a task with intervals determined by a custom schedule, maintaining state between executions.
      *
      * @param intervalSchedule
      *   A schedule that determines the timing between executions
      * @param state
      *   The initial state value
      * @param f
      *   A function that takes the current state and returns the next state
      * @tparam A
      *   The type of the state value
      * @return
      *   A Fiber that can be used to control or interrupt the recurring task and access the final state
      */
    def repeatAtInterval[E, A: Flat, S](
        intervalSchedule: Schedule,
        state: A
    )(
        f: A => A < (Async & Abort[E])
    )(using Frame): Fiber[E, A] < IO =
        Async.run {
            Clock.use { clock =>
                clock.now.map { now =>
                    Loop(now, state, intervalSchedule) { (lastExecution, state, period) =>
                        clock.now.map { now =>
                            period.next(now) match
                                case Absent => Loop.done(state)
                                case Present((duration, nextSchedule)) =>
                                    val nextExecution = lastExecution + duration
                                    clock.sleep(duration).map(_.use(_ => f(state).map(Loop.continue(nextExecution, _, nextSchedule))))
                        }
                    }
                }
            }
        }

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe:

        def now()(using AllowUnsafe): Instant

        def nowMonotonic()(using AllowUnsafe): Duration

        private[kyo] def sleep(duration: Duration): Fiber.Unsafe[Nothing, Unit]

        final def stopwatch()(using AllowUnsafe): Stopwatch.Unsafe = Stopwatch.Unsafe(nowMonotonic(), this)

        final def deadline(duration: Duration)(using AllowUnsafe): Deadline.Unsafe =
            if !duration.isFinite then Deadline.Unsafe(Maybe.empty, this)
            else Deadline.Unsafe(Maybe(now() + duration), this)

        final def safe: Clock = Clock(this)
    end Unsafe

    object Unsafe:
        def apply(executor: ScheduledExecutorService)(using AllowUnsafe): Unsafe =
            new Unsafe:
                def now()(using AllowUnsafe)          = Instant.fromJava(java.time.Instant.now())
                def nowMonotonic()(using AllowUnsafe) = java.lang.System.nanoTime().nanos
                def sleep(duration: Duration) =
                    Promise.Unsafe.fromIOPromise {
                        new IOPromise[Nothing, Unit] with Callable[Unit]:
                            val task = executor.schedule(this, duration.toNanos, TimeUnit.NANOSECONDS)
                            override def interrupt[E2 >: Nothing](error: Result.Error[E2]): Boolean =
                                discard(task.cancel(true))
                                super.interrupt(error)
                            def call(): Unit = completeDiscard(Result.unit)
                    }
                end sleep
    end Unsafe

end Clock
