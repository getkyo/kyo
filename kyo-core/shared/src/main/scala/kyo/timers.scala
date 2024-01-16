package kyo

import kyo._
import kyo.scheduler.Threads

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import kyo.locals.Locals

object timers {

  abstract class Timer {

    def schedule(delay: Duration)(f: => Unit < IOs): TimerTask < IOs

    def scheduleAtFixedRate(
        initalDelay: Duration,
        period: Duration
    )(f: => Unit < IOs): TimerTask < IOs

    def scheduleWithFixedDelay(
        initalDelay: Duration,
        period: Duration
    )(f: => Unit < IOs): TimerTask < IOs
  }

  object Timer {

    val default: Timer =
      new Timer {

        private val exec =
          Executors.newScheduledThreadPool(
              Runtime.getRuntime.availableProcessors / 2,
              Threads("kyo-timer-default")
          )

        private final class Task(task: ScheduledFuture[_]) extends TimerTask {
          def cancel: Boolean < IOs      = IOs(task.cancel(false))
          def isCancelled: Boolean < IOs = IOs(task.isCancelled())
          def isDone: Boolean < IOs      = IOs(task.isDone())
        }

        def schedule(delay: Duration)(f: => Unit < IOs) =
          if (delay.isFinite) {
            val call = new Callable[Unit] {
              def call: Unit = IOs.run(f)
            }
            IOs(new Task(exec.schedule(call, delay.toNanos, TimeUnit.NANOSECONDS)))
          } else {
            TimerTask.noop
          }

        def scheduleAtFixedRate(
            initalDelay: Duration,
            period: Duration
        )(f: => Unit < IOs) =
          if (period.isFinite && initalDelay.isFinite) {
            IOs(new Task(
                exec.scheduleAtFixedRate(
                    () => IOs.run(f),
                    initalDelay.toNanos,
                    period.toNanos,
                    TimeUnit.NANOSECONDS
                )
            ))
          } else {
            TimerTask.noop
          }

        def scheduleWithFixedDelay(
            initalDelay: Duration,
            period: Duration
        )(f: => Unit < IOs) =
          if (period.isFinite && initalDelay.isFinite) {
            IOs(new Task(
                exec.scheduleWithFixedDelay(
                    () => IOs.run(f),
                    initalDelay.toNanos,
                    period.toNanos,
                    TimeUnit.NANOSECONDS
                )
            ))
          } else {
            TimerTask.noop
          }
      }
  }

  abstract class TimerTask {
    def cancel: Boolean < IOs
    def isCancelled: Boolean < IOs
    def isDone: Boolean < IOs
  }

  object TimerTask {
    val noop = new TimerTask {
      def cancel      = false
      def isCancelled = false
      def isDone      = true
    }
  }

  object Timers {

    private val local = Locals.init(Timer.default)

    def let[T, S](timer: Timer)(v: T < S): T < (IOs with S) =
      local.let(timer)(v)

    def schedule(delay: Duration)(f: => Unit < IOs): TimerTask < IOs =
      local.get.map(_.schedule(delay)(f))

    def scheduleAtFixedRate(
        period: Duration
    )(f: => Unit < IOs): TimerTask < IOs =
      scheduleAtFixedRate(Duration.Zero, period)(f)

    def scheduleAtFixedRate(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < IOs): TimerTask < IOs =
      local.get.map(_.scheduleAtFixedRate(initialDelay, period)(f))

    def scheduleWithFixedDelay(
        period: Duration
    )(f: => Unit < IOs): TimerTask < IOs =
      scheduleWithFixedDelay(Duration.Zero, period)(f)

    def scheduleWithFixedDelay(
        initialDelay: Duration,
        period: Duration
    )(f: => Unit < IOs): TimerTask < IOs =
      local.get.map(_.scheduleWithFixedDelay(initialDelay, period)(f))
  }
}
