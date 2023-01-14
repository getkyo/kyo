package kyo.concurrent

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import kyo.core._
import kyo.ios._
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Callable

object timers {

  opaque type Timer     = ScheduledExecutorService
  opaque type TimerTask = ScheduledFuture[Unit]

  extension (t: TimerTask) {

    def cancel: Boolean > IOs =
      IOs(t.cancel(false))

    def isCancelled: Boolean > IOs =
      IOs(t.isCancelled)

    def isDone: Boolean > IOs =
      IOs(t.isDone)
  }

  extension (t: Timer) {

    def schedule(f: => Unit > IOs, delay: Duration): TimerTask > IOs =
      IOs(t.schedule[Unit](() => IOs.run(f), delay.toNanos, TimeUnit.NANOSECONDS))

    def schedule(f: => Unit > IOs, delay: Duration, period: Duration): TimerTask > IOs =
      IOs {
        t.scheduleAtFixedRate(
            () => f,
            delay.toNanos,
            period.toNanos,
            TimeUnit.NANOSECONDS
        ).asInstanceOf[TimerTask]
      }

    def schedule(f: => Unit > IOs, delay: Duration, period: Duration, times: Int): TimerTask > IOs =
      IOs {
        t.scheduleWithFixedDelay(
            () => f,
            delay.toNanos,
            period.toNanos,
            TimeUnit.NANOSECONDS
        ).asInstanceOf[TimerTask]
      }
  }

}
