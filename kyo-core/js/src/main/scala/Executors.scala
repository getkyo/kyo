package java.util.concurrent

import scalajs.js.timers
import scala.concurrent.duration._
import scala.scalajs.js.timers.SetTimeoutHandle
import scala.scalajs.js.timers.SetIntervalHandle

class ScheduledFuture[T](clear: => Unit) {
  var cancelled = false
  def cancel(ign: Boolean): Boolean = {
    cancelled && {
      clear
      cancelled = true
      true
    }
  }
  def isCancelled(): Boolean = cancelled
  def isDone(): Boolean      = false
}

class ScheduledExecutorService() {
  def schedule(r: Callable[_], delay: Long, unit: TimeUnit) =
    val handle =
      timers.setTimeout(delay.nanos) {
        r.call()
      }
    ScheduledFuture(timers.clearTimeout(handle))
  def scheduleAtFixedRate(
      r: Runnable,
      delay: Long,
      period: Long,
      unit: TimeUnit
  ): ScheduledFuture[_] =
    val handle =
      timers.setInterval(period.nanos) {
        r.run()
      }
    ScheduledFuture(timers.clearInterval(handle))

  def scheduleWithFixedDelay(
      r: Runnable,
      delay: Long,
      period: Long,
      unit: TimeUnit
  ): ScheduledFuture[_] =
    val handle =
      timers.setInterval(period.nanos) {
        r.run()
      }
    ScheduledFuture(timers.clearInterval(handle))
}

object Executors {
  def newScheduledThreadPool(ign: Int, ign2: ThreadFactory): ScheduledExecutorService =
    ScheduledExecutorService()
}
