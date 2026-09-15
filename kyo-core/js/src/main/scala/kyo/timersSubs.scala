package java.util.concurrent

import java.util.Timer
import java.util.TimerTask
import java.util.logging.*

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
                ScheduledFuture.log.log(Level.SEVERE, "Bug in ScheduledFuture", e)
        end try
    end run
    def isDone(): Boolean = _done
end ScheduledFuture

private object ScheduledFuture:
    private[ScheduledFuture] val log = Logger.getLogger("java.util.concurrent.ScheduledFuture")

class ScheduledExecutorService():

    import ScheduledExecutorService.MaxDelayMillis

    val timer = new Timer()

    def schedule[A](r: Callable[A], delay: Long, unit: TimeUnit): ScheduledFuture[A] =
        val task = new ScheduledFuture(r.call())
        arm(task, unit.toMillis(delay))
        task
    end schedule

    def scheduleAtFixedRate(
        r: Runnable,
        delay: Long,
        period: Long,
        unit: TimeUnit
    ): ScheduledFuture[?] =
        val task         = new ScheduledFuture(r.run())
        val periodMillis = requirePeriodInRange(unit.toMillis(period))
        armFirstFire(task, unit.toMillis(delay), periodMillis)
        task
    end scheduleAtFixedRate

    def scheduleWithFixedDelay(
        r: Runnable,
        delay: Long,
        period: Long,
        unit: TimeUnit
    ): ScheduledFuture[?] =
        val task         = new ScheduledFuture(r.run())
        val periodMillis = requirePeriodInRange(unit.toMillis(period))
        armFirstFire(task, unit.toMillis(delay), periodMillis)
        task
    end scheduleWithFixedDelay

    /** Arm `task` to fire once, `delayMillis` from now.
      *
      * A delay past [[MaxDelayMillis]] is armed in hops of that size until the remainder fits, rather than handed to the timer whole. The
      * host's timers take a 32-bit signed millisecond delay, and a larger one does not park longer, it OVERFLOWS: Node prints
      * `TimeoutOverflowWarning` and clamps the delay to 1 ms. `Async.sleep(Duration.Infinity)` is 9223372036854 ms, so it returned almost
      * immediately, the run block completed, the application's `Scope` closed, and every `Scope`-managed resource shut down, while unscoped
      * fibers kept the process alive and logging so that every liveness signal an operator would check still said the application was
      * healthy. The JVM executor parks for the whole duration; hopping is what makes this one do the same.
      *
      * Each hop re-checks cancellation, since a cancelled `TimerTask` cannot be scheduled: the hops hold the only reference to the task
      * while they run, so a cancel that arrives mid-park has to stop the chain here or the last hop would fail scheduling it.
      */
    private def arm(task: ScheduledFuture[?], delayMillis: Long): Unit =
        if delayMillis <= MaxDelayMillis then timer.schedule(task, math.max(delayMillis, 0L))
        else
            timer.schedule(
                new TimerTask:
                    def run(): Unit =
                        if !task.isCancelled() then arm(task, delayMillis - MaxDelayMillis)
                ,
                MaxDelayMillis
            )
    end arm

    /** Arm the first fire of a periodic `task`, then let the timer drive the rest at `periodMillis`.
      *
      * A `TimerTask` can be scheduled only once, so an out-of-range initial delay cannot hop with the task itself; the hops carry a
      * placeholder and the real periodic schedule starts when they run out.
      */
    private def armFirstFire(task: ScheduledFuture[?], delayMillis: Long, periodMillis: Long): Unit =
        if delayMillis <= MaxDelayMillis then
            timer.scheduleAtFixedRate(task, math.max(delayMillis, 0L), periodMillis)
        else
            timer.schedule(
                new TimerTask:
                    def run(): Unit =
                        if !task.isCancelled() then armFirstFire(task, delayMillis - MaxDelayMillis, periodMillis)
                ,
                MaxDelayMillis
            )
    end armFirstFire

    /** A period past [[MaxDelayMillis]] would be clamped to 1 ms by the host and fire in a tight loop forever, which is the same silent
      * wrongness the initial delay used to have. It cannot be fixed by hopping the way a one-shot delay can, since the timer owns the
      * repeat, so it is refused by name instead of being honoured as something it is not.
      */
    private def requirePeriodInRange(periodMillis: Long): Long =
        if periodMillis > MaxDelayMillis then
            throw new IllegalArgumentException(
                s"period of $periodMillis ms exceeds the host timer's ${MaxDelayMillis} ms ceiling; " +
                    "a periodic task cannot be scheduled beyond it on this platform."
            )
        else math.max(periodMillis, 0L)

end ScheduledExecutorService

object ScheduledExecutorService:

    /** The largest delay the host's timers accept. `setTimeout` takes a 32-bit signed millisecond argument; anything larger overflows and is
      * clamped to 1 ms rather than rejected. About 24.8 days.
      */
    private val MaxDelayMillis: Long = Int.MaxValue.toLong

end ScheduledExecutorService

object Executors:
    def newSingleThreadScheduledExecutor(): ScheduledExecutorService =
        new ScheduledExecutorService()
    def newScheduledThreadPool(ign: Int, ign2: ThreadFactory): ScheduledExecutorService =
        new ScheduledExecutorService()
end Executors
