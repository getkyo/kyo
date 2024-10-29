package kyo

import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.DelayQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kyo.Clock.Deadline
import kyo.Clock.Stopwatch
import kyo.Result.Panic
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
    final case class Stopwatch private[Clock] (unsafe: Stopwatch.Unsafe) extends AnyVal:
        /** Gets the elapsed time since the stopwatch was created.
          *
          * @return
          *   The elapsed time as a Duration
          */
        def elapsed(using Frame): Duration < IO = IO.Unsafe(unsafe.elapsed())
    end Stopwatch

    object Stopwatch:
        /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
        final class Unsafe(start: Instant, clock: Clock.Unsafe):
            def elapsed()(using AllowUnsafe): Duration = clock.now() - start
            def safe: Stopwatch                        = Stopwatch(this)
        end Unsafe
    end Stopwatch

    /** A deadline for checking remaining time or if it's overdue. */
    final case class Deadline private[Clock] (unsafe: Deadline.Unsafe) extends AnyVal:
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
        Clock(
            new Unsafe:
                val executor                 = Executors.newScheduledThreadPool(2, Threads("kyo-core-clock-executor"))
                def now()(using AllowUnsafe) = Instant.fromJava(java.time.Instant.now())
                def sleep(duration: Duration) =
                    Promise.Unsafe.fromIOPromise {
                        new IOPromise[Nothing, Unit] with Callable[Unit]:
                            val task = executor.schedule(this, duration.toNanos, TimeUnit.NANOSECONDS)
                            override def interrupt(error: Panic): Boolean =
                                discard(task.cancel(true))
                                super.interrupt(error)
                            def call(): Unit = completeDiscard(Result.unit)
                    }
                end sleep
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

    /** Gets the current Clock instance from the local context.
      *
      * This is the primary way to access the current Clock instance when you only need to use it directly.
      *
      * @return
      *   The current Clock instance
      */
    def get(using Frame): Clock < Any =
        use(identity)

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
        if factor == 1 then v
        else
            use { clock =>
                IO.Unsafe {
                    val shifted =
                        new Unsafe:
                            val underlying  = clock.unsafe
                            val start       = underlying.now()
                            val sleepFactor = (1.toDouble / factor)
                            def now()(using AllowUnsafe) =
                                val diff = underlying.now() - start
                                start + (diff * factor)
                            end now
                            override def sleep(duration: Duration) =
                                underlying.sleep(duration * sleepFactor)
                    let(Clock(shifted))(v)
                }
            }
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
        use(_.now)

    private[kyo] def sleep(duration: Duration)(using Frame): Fiber[Nothing, Unit] < IO =
        use(_.sleep(duration))

    /** Creates a new stopwatch using the local Clock instance.
      *
      * @return
      *   A new Stopwatch instance
      */
    def stopwatch(using Frame): Stopwatch < IO =
        use(_.stopwatch)

    /** Creates a new deadline with the specified duration using the local Clock instance.
      *
      * @param duration
      *   The duration for the deadline
      * @return
      *   A new Deadline instance
      */
    def deadline(duration: Duration)(using Frame): Deadline < IO =
        use(_.deadline(duration))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe:
        import AllowUnsafe.embrace.danger

        def now()(using AllowUnsafe): Instant

        private[kyo] def sleep(duration: Duration): Fiber.Unsafe[Nothing, Unit]

        final def stopwatch()(using AllowUnsafe): Stopwatch.Unsafe = Stopwatch.Unsafe(now(), this)

        final def deadline(duration: Duration)(using AllowUnsafe): Deadline.Unsafe =
            if !duration.isFinite then Deadline.Unsafe(Maybe.empty, this)
            else Deadline.Unsafe(Maybe(now() + duration), this)

        final def safe: Clock = Clock(this)
    end Unsafe

end Clock
