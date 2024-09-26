package kyo

import java.util.concurrent.*
import kyo.scheduler.util.Threads

/** A timer for scheduling tasks to run after a delay or periodically. */
abstract class Timer:
    def unsafe: Timer.Unsafe

    /** Schedule a task to run after a specified delay.
      *
      * @param delay
      *   The time to wait before executing the task
      * @param f
      *   The task to execute
      * @return
      *   A TimerTask that can be used to cancel the scheduled task
      */
    def schedule(delay: Duration)(f: => Unit < Async)(using Frame): TimerTask < IO

    /** Schedule a task to run periodically at a fixed rate.
      *
      * @param initialDelay
      *   The time to wait before the first execution
      * @param period
      *   The time between successive executions
      * @param f
      *   The task to execute
      * @return
      *   A TimerTask that can be used to cancel the scheduled task
      */
    def scheduleAtFixedRate(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO

    /** Schedule a task to run periodically with a fixed delay between executions.
      *
      * @param initialDelay
      *   The time to wait before the first execution
      * @param period
      *   The time to wait between the end of one execution and the start of the next
      * @param f
      *   The task to execute
      * @return
      *   A TimerTask that can be used to cancel the scheduled task
      */
    def scheduleWithFixedDelay(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO

end Timer

object Timer:

    /** A default Timer implementation using a scheduled thread pool. */
    val live: Timer =
        import AllowUnsafe.embrace.danger
        Timer(Unsafe(Executors.newScheduledThreadPool(2, Threads("kyo-timer-default"))))

    private val local = Local.init(Timer.live)

    /** Get the current Timer from the local context.
      *
      * @param timer
      *   The Timer to use in the given context
      * @param v
      *   The computation to run with the specified Timer
      * @return
      *   The result of the computation with the Timer in context
      */
    def let[A, S](timer: Timer)(v: A < S)(using Frame): A < (IO & S) =
        local.let(timer)(v)

    /** Schedule a task to run after a specified delay.
      *
      * @param delay
      *   The time to wait before executing the task
      * @param f
      *   The task to execute
      * @return
      *   A TimerTask that can be used to cancel the scheduled task
      */
    def schedule(delay: Duration)(f: => Unit < Async)(using Frame): TimerTask < IO =
        local.use(_.schedule(delay)(f))

    /** Schedule a task to run periodically at a fixed rate, starting immediately.
      *
      * @param period
      *   The time between successive executions
      * @param f
      *   The task to execute
      * @return
      *   A TimerTask that can be used to cancel the scheduled task
      */
    def scheduleAtFixedRate(
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO =
        scheduleAtFixedRate(Duration.Zero, period)(f)

    /** Schedule a task to run periodically at a fixed rate.
      *
      * @param initialDelay
      *   The time to wait before the first execution
      * @param period
      *   The time between successive executions
      * @param f
      *   The task to execute
      * @return
      *   A TimerTask that can be used to cancel the scheduled task
      */
    def scheduleAtFixedRate(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO =
        local.use(_.scheduleAtFixedRate(initialDelay, period)(f))

    /** Schedule a task to run periodically with a fixed delay between executions, starting immediately.
      *
      * @param period
      *   The time to wait between the end of one execution and the start of the next
      * @param f
      *   The task to execute
      * @return
      *   A TimerTask that can be used to cancel the scheduled task
      */
    def scheduleWithFixedDelay(
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO =
        scheduleWithFixedDelay(Duration.Zero, period)(f)

    /** Schedule a task to run periodically with a fixed delay between executions.
      *
      * @param initialDelay
      *   The time to wait before the first execution
      * @param period
      *   The time to wait between the end of one execution and the start of the next
      * @param f
      *   The task to execute
      * @return
      *   A TimerTask that can be used to cancel the scheduled task
      */
    def scheduleWithFixedDelay(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO =
        local.use(_.scheduleWithFixedDelay(initialDelay, period)(f))

    /** Create a new Timer using the provided ScheduledExecutorService.
      *
      * @param exec
      *   The ScheduledExecutorService to use for scheduling tasks
      * @return
      *   A new Timer instance
      */
    def apply(u: Unsafe): Timer =
        new Timer:
            def unsafe: Unsafe = u

            private def eval(f: => Unit < Async)(using Frame): Unit =
                import AllowUnsafe.embrace.danger
                discard(IO.run(Async.run(f)).eval)

            def schedule(delay: Duration)(f: => Unit < Async)(using Frame): TimerTask < IO =
                IO(unsafe.schedule(delay)(eval(f)).safe)

            def scheduleAtFixedRate(
                initialDelay: Duration,
                period: Duration
            )(f: => Unit < Async)(using Frame): TimerTask < IO =
                IO(unsafe.scheduleAtFixedRate(initialDelay, period)(eval(f)).safe)

            def scheduleWithFixedDelay(
                initialDelay: Duration,
                period: Duration
            )(f: => Unit < Async)(using Frame): TimerTask < IO =
                IO(unsafe.scheduleWithFixedDelay(initialDelay, period)(eval(f)).safe)

    abstract class Unsafe:
        def schedule(delay: Duration)(f: => Unit)(using AllowUnsafe): TimerTask.Unsafe
        def scheduleAtFixedRate(
            initialDelay: Duration,
            period: Duration
        )(f: => Unit)(using AllowUnsafe): TimerTask.Unsafe
        def scheduleWithFixedDelay(
            initialDelay: Duration,
            period: Duration
        )(f: => Unit)(using AllowUnsafe): TimerTask.Unsafe
        def safe: Timer = Timer(this)
    end Unsafe

    object Unsafe:
        def apply(exec: ScheduledExecutorService)(using AllowUnsafe): Unsafe = new Unsafe:
            final private class FutureTimerTask(task: ScheduledFuture[?]) extends TimerTask.Unsafe:
                def cancel()(using AllowUnsafe): Boolean    = task.cancel(false)
                def cancelled()(using AllowUnsafe): Boolean = task.isCancelled()
                def done()(using AllowUnsafe): Boolean      = task.isDone()
            end FutureTimerTask

            def schedule(delay: Duration)(f: => Unit)(using AllowUnsafe): TimerTask.Unsafe =
                if delay.isFinite then
                    val call = new Callable[Unit]:
                        def call: Unit = f
                    new FutureTimerTask(exec.schedule(call, delay.toNanos, TimeUnit.NANOSECONDS))
                else
                    TimerTask.Unsafe.noop

            def scheduleAtFixedRate(
                initialDelay: Duration,
                period: Duration
            )(f: => Unit)(using AllowUnsafe): TimerTask.Unsafe =
                if period.isFinite && initialDelay.isFinite then
                    new FutureTimerTask(
                        exec.scheduleAtFixedRate(
                            () => f,
                            initialDelay.toNanos,
                            period.toNanos,
                            TimeUnit.NANOSECONDS
                        )
                    )
                else
                    TimerTask.Unsafe.noop

            def scheduleWithFixedDelay(
                initialDelay: Duration,
                period: Duration
            )(f: => Unit)(using AllowUnsafe): TimerTask.Unsafe =
                if period.isFinite && initialDelay.isFinite then
                    new FutureTimerTask(
                        exec.scheduleWithFixedDelay(
                            () => f,
                            initialDelay.toNanos,
                            period.toNanos,
                            TimeUnit.NANOSECONDS
                        )
                    )
                else
                    TimerTask.Unsafe.noop
    end Unsafe
end Timer

/** Represents a scheduled task that can be cancelled. */
final case class TimerTask private (unsafe: TimerTask.Unsafe) extends AnyVal:
    /** Attempt to cancel the execution of this task.
      *
      * @return
      *   true if the task was successfully cancelled, false otherwise
      */
    def cancel(using Frame): Boolean < IO = IO(unsafe.cancel())

    /** Check if this task has been cancelled.
      *
      * @return
      *   true if the task has been cancelled, false otherwise
      */
    def cancelled(using Frame): Boolean < IO = IO(unsafe.cancelled())

    /** Check if this task has completed its execution.
      *
      * @return
      *   true if the task has completed, false otherwise
      */
    def done(using Frame): Boolean < IO = IO(unsafe.done())
end TimerTask

object TimerTask:
    /** A no-op TimerTask that is always considered done and cannot be cancelled. */
    val noop = TimerTask(Unsafe.noop)

    abstract class Unsafe:
        def cancel()(using AllowUnsafe): Boolean
        def cancelled()(using AllowUnsafe): Boolean
        def done()(using AllowUnsafe): Boolean
        def safe: TimerTask = TimerTask(this)
    end Unsafe

    object Unsafe:
        val noop = new Unsafe:
            def cancel()(using AllowUnsafe)    = false
            def cancelled()(using AllowUnsafe) = false
            def done()(using AllowUnsafe)      = true
    end Unsafe
end TimerTask
