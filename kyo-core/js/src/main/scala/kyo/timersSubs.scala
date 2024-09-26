package java.util.concurrent

import java.util.Timer
import java.util.TimerTask

class ScheduledFuture[A](r: => A) extends TimerTask:
    private var _cancelled = false
    private var _done      = false
    def cancel(b: Boolean) =
        _cancelled = true
        super.cancel()
    def isCancelled(): Boolean = _cancelled
    def run(): Unit =
        _done = true
        try
            val _ = r
            ()
        catch
            case e: Throwable =>
                e.printStackTrace()
        end try
    end run
    def isDone(): Boolean = _done
end ScheduledFuture

class ScheduledExecutorService():

    val timer = new Timer()

    def schedule[A](r: Callable[A], delay: Long, unit: TimeUnit): ScheduledFuture[A] =
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
