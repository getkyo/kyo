package java.util.concurrent

import java.util.Timer
import java.util.TimerTask
import scala.concurrent.duration.*

class ScheduledFuture[T](r: => T) extends TimerTask:
    private var cancelled = false
    private var done      = false
    def cancel(b: Boolean) =
        cancelled = true
        super.cancel()
    def isCancelled(): Boolean = cancelled
    def run(): Unit =
        done = true
        try
            r
            ()
        catch
            case e: Throwable =>
                e.printStackTrace()
        end try
    end run
    def isDone(): Boolean = done
end ScheduledFuture

class ScheduledExecutorService():

    val timer = new Timer()

    def schedule[T](r: Callable[T], delay: Long, unit: TimeUnit): ScheduledFuture[T] =
        val task = new ScheduledFuture(r.call())
        timer.schedule(task, unit.toMillis(delay))
        task
    end schedule

    def scheduleAtFixedRate(
        r: Runnable,
        delay: Long,
        period: Long,
        unit: TimeUnit
    ): ScheduledFuture[?] =
        val task = new ScheduledFuture(r.run())
        timer.scheduleAtFixedRate(task, unit.toMillis(delay), unit.toMillis(period))
        task
    end scheduleAtFixedRate

    def scheduleWithFixedDelay(
        r: Runnable,
        delay: Long,
        period: Long,
        unit: TimeUnit
    ): ScheduledFuture[?] =
        val task = new ScheduledFuture(r.run())
        timer.scheduleAtFixedRate(task, unit.toMillis(delay), unit.toMillis(period))
        task
    end scheduleWithFixedDelay
end ScheduledExecutorService

object Executors:
    def newSingleThreadScheduledExecutor(): ScheduledExecutorService =
        new ScheduledExecutorService()
    def newScheduledThreadPool(ign: Int, ign2: ThreadFactory): ScheduledExecutorService =
        new ScheduledExecutorService()
end Executors
