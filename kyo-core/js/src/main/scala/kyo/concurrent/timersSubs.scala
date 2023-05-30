package java.util.concurrent

import scalajs.js.timers
import scala.concurrent.duration._
import scala.scalajs.js.timers.SetTimeoutHandle
import scala.scalajs.js.timers.SetIntervalHandle
import java.util.Timer
import java.util.TimerTask

class ScheduledFuture[T](r: => T) extends TimerTask {
  private var cancelled = false
  private var done      = false
  def cancel(b: Boolean) = {
    cancelled = true
    super.cancel()
  }
  def isCancelled(): Boolean = cancelled
  def run(): Unit =
    done = true
    try r
    catch {
      case e: Throwable =>
        e.printStackTrace()
    }
  def isDone(): Boolean = done
}

class ScheduledExecutorService() {

  val timer = new Timer()

  def schedule[T](r: Callable[T], delay: Long, unit: TimeUnit): ScheduledFuture[T] = {
    val task = new ScheduledFuture(r.call())
    timer.schedule(task, unit.toMillis(delay))
    task
  }

  def scheduleAtFixedRate(
      r: Runnable,
      delay: Long,
      period: Long,
      unit: TimeUnit
  ): ScheduledFuture[_] = {
    val task = new ScheduledFuture(r.run())
    timer.scheduleAtFixedRate(task, unit.toMillis(delay), unit.toMillis(period))
    task
  }

  def scheduleWithFixedDelay(
      r: Runnable,
      delay: Long,
      period: Long,
      unit: TimeUnit
  ): ScheduledFuture[_] = {
    val task = new ScheduledFuture(r.run())
    timer.scheduleAtFixedRate(task, unit.toMillis(delay), unit.toMillis(period))
    task
  }
}

object Executors {
  def newScheduledThreadPool(ign: Int, ign2: ThreadFactory): ScheduledExecutorService =
    new ScheduledExecutorService()
}
