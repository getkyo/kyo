package kyo.scheduler

import InternalTimer.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

trait InternalTimer:
    def schedule(interval: Duration)(f: => Unit): TimerTask
    def scheduleOnce(delay: Duration)(f: => Unit): TimerTask

object InternalTimer:

    trait TimerTask:
        def cancel(): Boolean

    def apply(executor: ScheduledExecutorService): InternalTimer =
        new InternalTimer:
            def schedule(interval: Duration)(f: => Unit): TimerTask =
                val future = executor.scheduleWithFixedDelay(() => f, interval.toNanos, interval.toNanos, TimeUnit.NANOSECONDS)
                new TimerTask:
                    def cancel() = future.cancel(true)
            end schedule

            def scheduleOnce(delay: Duration)(f: => Unit): TimerTask =
                val future = executor.schedule((() => f): Runnable, delay.toNanos, TimeUnit.NANOSECONDS)
                new TimerTask:
                    def cancel() = future.cancel(true)
            end scheduleOnce

end InternalTimer
