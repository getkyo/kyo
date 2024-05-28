package kyo

import java.util.concurrent.*
import kyo.internal.Trace
import kyo.scheduler.util.Threads
abstract class Timer:

    def schedule(delay: Duration)(f: => Unit < Fibers)(using Trace): TimerTask < IOs

    def scheduleAtFixedRate(
        initalDelay: Duration,
        period: Duration
    )(f: => Unit < Fibers)(using Trace): TimerTask < IOs

    def scheduleWithFixedDelay(
        initalDelay: Duration,
        period: Duration
    )(f: => Unit < Fibers)(using Trace): TimerTask < IOs

end Timer

object Timer:

    val default: Timer =
        Timer(Executors.newScheduledThreadPool(2, Threads("kyo-timer-default")))

    def apply(exec: ScheduledExecutorService): Timer =
        new Timer:

            final private class Task(task: ScheduledFuture[?]) extends TimerTask:
                def cancel(using Trace): Boolean < IOs      = IOs(task.cancel(false))
                def isCancelled(using Trace): Boolean < IOs = IOs(task.isCancelled())
                def isDone(using Trace): Boolean < IOs      = IOs(task.isDone())
            end Task

            private def eval(f: => Unit < Fibers): Unit =
                discard(IOs.run(Fibers.run(Fibers.init(f))))

            def schedule(delay: Duration)(f: => Unit < Fibers)(using Trace) =
                if delay.isFinite then
                    val call = new Callable[Unit]:
                        def call: Unit = eval(f)
                    IOs(new Task(exec.schedule(call, delay.toNanos, TimeUnit.NANOSECONDS)))
                else
                    TimerTask.noop

            def scheduleAtFixedRate(
                initalDelay: Duration,
                period: Duration
            )(f: => Unit < Fibers)(using Trace) =
                if period.isFinite && initalDelay.isFinite then
                    IOs(new Task(
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
            )(f: => Unit < Fibers)(using Trace) =
                if period.isFinite && initalDelay.isFinite then
                    IOs(new Task(
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
    def cancel(using Trace): Boolean < IOs
    def isCancelled(using Trace): Boolean < IOs
    def isDone(using Trace): Boolean < IOs
end TimerTask

object TimerTask:
    val noop = new TimerTask:
        def cancel(using Trace)      = false
        def isCancelled(using Trace) = false
        def isDone(using Trace)      = true
end TimerTask

object Timers:

    private val local = Locals.init(Timer.default)

    def let[T, S](timer: Timer)(v: T < S)(using Trace): T < (IOs & S) =
        local.let(timer)(v)

    def schedule(delay: Duration)(f: => Unit < Fibers)(using Trace): TimerTask < IOs =
        local.use(_.schedule(delay)(f))

    def scheduleAtFixedRate(
        period: Duration
    )(f: => Unit < Fibers)(using Trace): TimerTask < IOs =
        scheduleAtFixedRate(Duration.Zero, period)(f)

    def scheduleAtFixedRate(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < Fibers)(using Trace): TimerTask < IOs =
        local.use(_.scheduleAtFixedRate(initialDelay, period)(f))

    def scheduleWithFixedDelay(
        period: Duration
    )(f: => Unit < Fibers)(using Trace): TimerTask < IOs =
        scheduleWithFixedDelay(Duration.Zero, period)(f)

    def scheduleWithFixedDelay(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < Fibers)(using Trace): TimerTask < IOs =
        local.use(_.scheduleWithFixedDelay(initialDelay, period)(f))
end Timers
