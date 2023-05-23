package kyo.concurrent

import kyo._
import kyo.envs._
import kyo.ios._

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import kyo.concurrent.scheduler.Threads

object timers {

  trait Timer {
    def shutdown: Unit > IOs
    def schedule(delay: Duration)(f: => Unit > IOs): TimerTask > IOs
    def scheduleAtFixedRate(
        initalDelay: Duration,
        period: Duration
    )(f: => Unit > IOs): TimerTask > IOs
    def scheduleWithFixedDelay(
        initalDelay: Duration,
        period: Duration
    )(f: => Unit > IOs): TimerTask > IOs
  }

  object Timer {
    given default: Timer = {
      new Timer {
        private val exec =
          Executors.newScheduledThreadPool(
              Runtime.getRuntime.availableProcessors / 2,
              Threads("kyo-timer-default")
          )

        private final class Task(task: ScheduledFuture[_]) extends TimerTask {
          def cancel: Boolean > IOs      = IOs(task.cancel(false))
          def isCancelled: Boolean > IOs = IOs(task.isCancelled())
          def isDone: Boolean > IOs      = IOs(task.isDone())
        }

        def shutdown: Unit > IOs =
          IOs.unit

        def schedule(delay: Duration)(f: => Unit > IOs): TimerTask > IOs =
          if (delay.isFinite) {
            val call = new Callable[Unit] {
              def call: Unit = IOs.run(f)
            }
            IOs(Task(exec.schedule(call, delay.toNanos, TimeUnit.NANOSECONDS)))
          } else {
            TimerTask.noop
          }

        def scheduleAtFixedRate(
            initalDelay: Duration,
            period: Duration
        )(f: => Unit > IOs): TimerTask > IOs =
          if (period.isFinite && initalDelay.isFinite) {
            IOs(Task {
              exec.scheduleAtFixedRate(
                  () => IOs.run(f),
                  initalDelay.toNanos,
                  period.toNanos,
                  TimeUnit.NANOSECONDS
              )
            })
          } else {
            TimerTask.noop
          }

        def scheduleWithFixedDelay(
            initalDelay: Duration,
            period: Duration
        )(f: => Unit > IOs): TimerTask > IOs =
          if (period.isFinite && initalDelay.isFinite) {
            IOs(Task {
              exec.scheduleWithFixedDelay(
                  () => IOs.run(f),
                  initalDelay.toNanos,
                  period.toNanos,
                  TimeUnit.NANOSECONDS
              )
            })
          } else {
            TimerTask.noop
          }
      }
    }
  }

  trait TimerTask {
    def cancel: Boolean > IOs
    def isCancelled: Boolean > IOs
    def isDone: Boolean > IOs
  }

  object TimerTask {
    val noop = new TimerTask {
      def cancel      = false
      def isCancelled = false
      def isDone      = true
    }
  }

  opaque type Timers = Envs[Timer]

  object Timers {
    def run[T, S1, S2](t: Timer > S1)(f: => T > (S2 & Timers)): T > (S1 & S2) =
      t.map(t => Envs[Timer].let(t)(f))
    def run[T, S](f: => T > (S & Timers))(using t: Timer): T > S =
      Envs[Timer].let(t)(f)
    def shutdown: Unit > (Timers & IOs) =
      Envs[Timer].get.map(_.shutdown)
    def schedule(delay: Duration)(f: => Unit > IOs): TimerTask > (Timers & IOs) =
      Envs[Timer].get.map(_.schedule(delay)(f))
    def scheduleAtFixedRate(
        period: Duration
    )(f: => Unit > IOs): TimerTask > (Timers & IOs) =
      scheduleAtFixedRate(Duration.Zero, period)(f)
    def scheduleAtFixedRate(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit > IOs): TimerTask > (Timers & IOs) =
      Envs[Timer].get.map(_.scheduleAtFixedRate(initialDelay, period)(f))
    def scheduleWithFixedDelay(
        period: Duration
    )(f: => Unit > IOs): TimerTask > (Timers & IOs) =
      scheduleWithFixedDelay(Duration.Zero, period)(f)
    def scheduleWithFixedDelay(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit > IOs): TimerTask > (Timers & IOs) =
      Envs[Timer].get.map(_.scheduleWithFixedDelay(initialDelay, period)(f))
  }
}
