package kyo

import java.util.concurrent.*
import kyo.scheduler.util.Threads
import scala.concurrent.duration.*

abstract class Timer:

    def schedule(delay: Duration)(f: => Unit < Fibers): TimerTask < IOs

    def scheduleAtFixedRate(
        initalDelay: Duration,
        period: Duration
    )(f: => Unit < Fibers): TimerTask < IOs

    def scheduleWithFixedDelay(
        initalDelay: Duration,
        period: Duration
    )(f: => Unit < Fibers): TimerTask < IOs

end Timer

object Timer:

    val default: Timer =
        Timer(Executors.newScheduledThreadPool(2, Threads("kyo-timer-default")))

    def apply(exec: ScheduledExecutorService): Timer =
        new Timer:

            final private class Task(task: ScheduledFuture[?]) extends TimerTask:
                def cancel: Boolean < IOs      = IOs(task.cancel(false))
                def isCancelled: Boolean < IOs = IOs(task.isCancelled())
                def isDone: Boolean < IOs      = IOs(task.isDone())
            end Task

            private def eval(f: => Unit < Fibers): Unit =
                discard(IOs.run(Fibers.run(Fibers.init(f))))

            def schedule(delay: Duration)(f: => Unit < Fibers) =
                if delay.isFinite then
                    val call = new Callable[Unit]:
                        def call: Unit = eval(f)
                    IOs(new Task(exec.schedule(call, delay.toNanos, TimeUnit.NANOSECONDS)))
                else
                    TimerTask.noop

            def scheduleAtFixedRate(
                initalDelay: Duration,
                period: Duration
            )(f: => Unit < Fibers) =
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
            )(f: => Unit < Fibers) =
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
    def cancel: Boolean < IOs
    def isCancelled: Boolean < IOs
    def isDone: Boolean < IOs
end TimerTask

object TimerTask:
    val noop = new TimerTask:
        def cancel      = false
        def isCancelled = false
        def isDone      = true
end TimerTask

object Timers:

    private val local = Locals.init(Timer.default)

    def let[T, S](timer: Timer)(v: T < S): T < (IOs & S) =
        local.let(timer)(v)

    def schedule(delay: Duration)(f: => Unit < Fibers): TimerTask < IOs =
        local.get.map(_.schedule(delay)(f))

    def scheduleAtFixedRate(
        period: Duration
    )(f: => Unit < Fibers): TimerTask < IOs =
        scheduleAtFixedRate(Duration.Zero, period)(f)

    def scheduleAtFixedRate(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < Fibers): TimerTask < IOs =
        local.get.map(_.scheduleAtFixedRate(initialDelay, period)(f))

    def scheduleWithFixedDelay(
        period: Duration
    )(f: => Unit < Fibers): TimerTask < IOs =
        scheduleWithFixedDelay(Duration.Zero, period)(f)

    def scheduleWithFixedDelay(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < Fibers): TimerTask < IOs =
        local.get.map(_.scheduleWithFixedDelay(initialDelay, period)(f))
end Timers
