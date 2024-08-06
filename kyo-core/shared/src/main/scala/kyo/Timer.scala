package kyo

import java.util.concurrent.*
import kyo.scheduler.util.Threads

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

object Timer:

    val default: Timer =
        Timer(Executors.newScheduledThreadPool(2, Threads("kyo-timer-default")))

    private val local = Local.init(Timer.default)

    def let[T, S](timer: Timer)(v: T < S)(using Frame): T < (IO & S) =
        local.let(timer)(v)

    def schedule(delay: Duration)(f: => Unit < Async)(using Frame): TimerTask < IO =
        local.use(_.schedule(delay)(f))

    def scheduleAtFixedRate(
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO =
        scheduleAtFixedRate(Duration.Zero, period)(f)

    def scheduleAtFixedRate(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO =
        local.use(_.scheduleAtFixedRate(initialDelay, period)(f))

    def scheduleWithFixedDelay(
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO =
        scheduleWithFixedDelay(Duration.Zero, period)(f)

    def scheduleWithFixedDelay(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < Async)(using Frame): TimerTask < IO =
        local.use(_.scheduleWithFixedDelay(initialDelay, period)(f))

    def apply(exec: ScheduledExecutorService): Timer =
        new Timer:

            final private class Task(task: ScheduledFuture[?]) extends TimerTask:
                def cancel(using Frame): Boolean < IO      = IO(task.cancel(false))
                def isCancelled(using Frame): Boolean < IO = IO(task.isCancelled())
                def isDone(using Frame): Boolean < IO      = IO(task.isDone())
            end Task

            private def eval(f: => Unit < Async): Unit =
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

abstract class TimerTask:
    def cancel(using Frame): Boolean < IO
    def isCancelled(using Frame): Boolean < IO
    def isDone(using Frame): Boolean < IO
end TimerTask

object TimerTask:
    val noop = new TimerTask:
        def cancel(using Frame)      = false
        def isCancelled(using Frame) = false
        def isDone(using Frame)      = true
end TimerTask
