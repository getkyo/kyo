package kyo

import java.util.concurrent.*
import kyo.scheduler.util.Threads

/** A timer for scheduling tasks to run after a delay or periodically. */
abstract class Timer:

    def schedule(delay: Duration)(f: => Unit < Async)(using Frame): TimerTask < IO

    def scheduleAtFixedRate(
        initalDelay: Duration,
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO

    def scheduleWithFixedDelay(
        initalDelay: Duration,
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO

end Timer

/** Companion object for Timer, providing factory methods and utility functions. */
object Timer:

    /** A default Timer implementation using a thread pool. */
    val live: Timer =
        Timer(Executors.newScheduledThreadPool(2, Threads("kyo-timer-default")))

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
      * @param initalDelay
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
      * @param initalDelay
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
    def apply(exec: ScheduledExecutorService): Timer =
        new Timer:

            final private class Task(task: ScheduledFuture[?]) extends TimerTask:
                def cancel(using Frame): Boolean < IO    = IO(task.cancel(false))
                def cancelled(using Frame): Boolean < IO = IO(task.isCancelled())
                def done(using Frame): Boolean < IO      = IO(task.isDone())
            end Task

            private def eval(f: => Unit < Async)(using Frame): Unit =
                discard(IO.run(Async.run(f)).eval)

            def schedule(delay: Duration)(f: => Unit < Async)(using Frame) =
                if delay.isFinite then
                    val call = new Callable[Unit]:
                        def call: Unit =
                            eval(f)
                    IO(new Task(exec.schedule(call, delay.toNanos, TimeUnit.NANOSECONDS)))
                else
                    TimerTask.noop

            def scheduleAtFixedRate(
                initalDelay: Duration,
                period: Duration
            )(f: => Unit < Async)(using Frame) =
                if period.isFinite && initalDelay.isFinite then
                    IO(new Task(
                        exec.scheduleAtFixedRate(
                            () => eval(f),
                            initalDelay.toNanos,
                            period.toNanos,
                            TimeUnit.NANOSECONDS
                        )
                    ))
                else
                    TimerTask.noop

            def scheduleWithFixedDelay(
                initalDelay: Duration,
                period: Duration
            )(f: => Unit < Async)(using Frame) =
                if period.isFinite && initalDelay.isFinite then
                    IO(new Task(
                        exec.scheduleWithFixedDelay(
                            () => eval(f),
                            initalDelay.toNanos,
                            period.toNanos,
                            TimeUnit.NANOSECONDS
                        )
                    ))
                else
                    TimerTask.noop
end Timer

/** Represents a scheduled task that can be cancelled. */
abstract class TimerTask:
    /** Attempt to cancel the execution of this task.
      *
      * @return
      *   true if the task was successfully cancelled, false otherwise
      */
    def cancel(using Frame): Boolean < IO

    /** Check if this task has been cancelled.
      *
      * @return
      *   true if the task has been cancelled, false otherwise
      */
    def cancelled(using Frame): Boolean < IO

    /** Check if this task has completed its execution.
      *
      * @return
      *   true if the task has completed, false otherwise
      */
    def done(using Frame): Boolean < IO
end TimerTask

object TimerTask:
    /** A no-op TimerTask that is always considered done and cannot be cancelled. */
    val noop = new TimerTask:
        def cancel(using Frame)    = false
        def cancelled(using Frame) = false
        def done(using Frame)      = true
end TimerTask
