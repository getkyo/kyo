package kyo.concurrent

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import kyo.core._
import kyo.ios._
import kyo.envs._
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import scheduler.ThreadFactory

object timers {

  trait TimerTask {
    def cancel: Boolean > IOs
    def isCancelled: Boolean > IOs
    def isDone: Boolean > IOs
  }

  trait Timer {
    def shutdown: Unit > IOs
    def schedule(f: => Unit > IOs, delay: Duration): TimerTask > IOs
    def scheduleAtFixedRate(f: => Unit > IOs, delay: Duration, period: Duration): TimerTask > IOs
    def scheduleWithFixedDelay(f: => Unit > IOs, delay: Duration, period: Duration): TimerTask > IOs
  }

  object Timer {
    given default: Timer = {
      new Timer {
        private val exec =
          Executors.newScheduledThreadPool(
              Runtime.getRuntime.availableProcessors / 2,
              ThreadFactory("kyo-timer-default")
          )

        private class Task[T](task: ScheduledFuture[T]) extends TimerTask {
          def cancel: Boolean > IOs      = IOs(task.cancel(false))
          def isCancelled: Boolean > IOs = IOs(task.isCancelled())
          def isDone: Boolean > IOs      = IOs(task.isDone())
        }

        def shutdown: Unit > IOs =
          IOs.unit

        def schedule(f: => Unit > IOs, delay: Duration): TimerTask > IOs =
          IOs(Task(exec.schedule(() => IOs.run(f), delay.toNanos, TimeUnit.NANOSECONDS)))

        def scheduleAtFixedRate(
            f: => Unit > IOs,
            delay: Duration,
            period: Duration
        ): TimerTask > IOs =
          IOs(Task {
            exec.scheduleAtFixedRate(
                () => IOs.run(f),
                delay.toNanos,
                period.toNanos,
                TimeUnit.NANOSECONDS
            )
          })

        def scheduleWithFixedDelay(
            f: => Unit > IOs,
            delay: Duration,
            period: Duration
        ): TimerTask > IOs =
          IOs(Task {
            exec.scheduleWithFixedDelay(
                () => IOs.run(f),
                delay.toNanos,
                period.toNanos,
                TimeUnit.NANOSECONDS
            )
          })
      }
    }
  }

  opaque type Timers = Envs[Timer]

  object Timers {
    def run[T, S](t: Timer)(f: => T > (S | Timers)): T > S =
      Envs.let(t)(f)
    def run[T, S](f: => T > (S | Timers))(using t: Timer): T > S =
      Envs.let(t)(f)
    def shutdown: Unit > (Timers | IOs) =
      Envs[Timer](_.shutdown)
    def schedule(f: => Unit > IOs, delay: Duration): TimerTask > (Timers | IOs) =
      Envs[Timer](_.schedule(f, delay))
    def scheduleAtFixedRate(
        f: => Unit > IOs,
        delay: Duration,
        period: Duration
    ): TimerTask > (Timers | IOs) =
      Envs[Timer](_.scheduleAtFixedRate(f, delay, period))
    def scheduleWithFixedDelay(
        f: => Unit > IOs,
        delay: Duration,
        period: Duration
    ): TimerTask > (Timers | IOs) =
      Envs[Timer](_.scheduleWithFixedDelay(f, delay, period))
  }
}
