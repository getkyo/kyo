package kyo.scheduler

import InternalTimer.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

abstract private[kyo] class InternalTimer {
    def schedule(interval: Duration)(f: => Unit): TimerTask
    def scheduleOnce(delay: Duration)(f: => Unit): TimerTask
}

private[kyo] object InternalTimer {

    abstract class TimerTask {
        def cancel(): Boolean
    }

    def apply(executor: ScheduledExecutorService): InternalTimer =
        new InternalTimer {
            def schedule(interval: Duration)(f: => Unit): TimerTask = {
                val future = executor.scheduleWithFixedDelay(() => f, interval.toNanos, interval.toNanos, TimeUnit.NANOSECONDS)
                () => future.cancel(true)
            }

            def scheduleOnce(delay: Duration)(f: => Unit): TimerTask = {
                val future = executor.schedule((() => f): Runnable, delay.toNanos, TimeUnit.NANOSECONDS)
                () => future.cancel(true)
            }
        }
}
