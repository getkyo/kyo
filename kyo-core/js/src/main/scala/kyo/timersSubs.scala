package java.util.concurrent

import java.util.logging.*
import scala.scalajs.js
import scala.scalajs.js.timers

class ScheduledFuture[A](r: => A):
    private var _cancelled              = false
    private var _done                   = false
    private var stopPending: () => Unit = ScheduledFuture.noStop

    /** Remembers how to stop whatever host timer is currently armed for this task, so `cancel` can reach it. */
    private[concurrent] def armedWith(stop: () => Unit): Unit =
        if _cancelled then stop() else stopPending = stop

    def cancel(b: Boolean) =
        _cancelled = true
        val stop = stopPending
        stopPending = ScheduledFuture.noStop
        stop()
    end cancel
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
    private[ScheduledFuture] val log           = Logger.getLogger("java.util.concurrent.ScheduledFuture")
    private[concurrent] val noStop: () => Unit = () => ()

/** The scheduled executor kyo's `Clock` runs on when the platform is Scala.js.
  *
  * Its timers are armed on the host directly rather than through `java.util.Timer`, for one reason: the handle. Node keeps its event loop
  * running while a timer is pending, so a background cadence parked on one holds a program open after the program's own work is done, and
  * the only way to say otherwise is `unref` on the handle the host returns. On the JVM the same cadences run on daemon threads, which hold
  * nothing, so this is what makes the two platforms agree rather than a JS-only concession.
  *
  * What holds a Scala.js program alive, then, is the program's own work: `KyoApp` takes that hold explicitly for as long as its run block is
  * pending (see `KyoAppRunnerPlatform`). A browser has no process to hold and no `unref` on its timer handles, where the call is skipped.
  */
class ScheduledExecutorService():

    import ScheduledExecutorService.MaxDelayMillis

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
      * Each hop re-checks cancellation, since a hop that fires after a cancel must not arm the next one: the hops hold the only reference to
      * the task while they run, so a cancel that arrives mid-park has to stop the chain here.
      */
    private def arm(task: ScheduledFuture[?], delayMillis: Long): Unit =
        if delayMillis <= MaxDelayMillis then
            val handle = timers.setTimeout(math.max(delayMillis, 0L).toDouble)(if !task.isCancelled() then task.run())
            ScheduledExecutorService.daemonize(handle)
            task.armedWith(() => timers.clearTimeout(handle))
        else
            val handle = timers.setTimeout(MaxDelayMillis.toDouble) {
                if !task.isCancelled() then arm(task, delayMillis - MaxDelayMillis)
            }
            ScheduledExecutorService.daemonize(handle)
            task.armedWith(() => timers.clearTimeout(handle))
        end if
    end arm

    /** Arm the first fire of a periodic `task`, then let the host drive the rest at `periodMillis`.
      *
      * An out-of-range initial delay hops the same way a one-shot delay does, and the repeating schedule starts when the hops run out.
      */
    private def armFirstFire(task: ScheduledFuture[?], delayMillis: Long, periodMillis: Long): Unit =
        if delayMillis <= MaxDelayMillis then
            val start = timers.setTimeout(math.max(delayMillis, 0L).toDouble) {
                if !task.isCancelled() then
                    task.run()
                    val repeat = timers.setInterval(periodMillis.toDouble)(if !task.isCancelled() then task.run())
                    ScheduledExecutorService.daemonize(repeat)
                    task.armedWith(() => timers.clearInterval(repeat))
                end if
            }
            ScheduledExecutorService.daemonize(start)
            task.armedWith(() => timers.clearTimeout(start))
        else
            val handle = timers.setTimeout(MaxDelayMillis.toDouble) {
                if !task.isCancelled() then armFirstFire(task, delayMillis - MaxDelayMillis, periodMillis)
            }
            ScheduledExecutorService.daemonize(handle)
            task.armedWith(() => timers.clearTimeout(handle))
        end if
    end armFirstFire

    /** A period past [[MaxDelayMillis]] would be clamped to 1 ms by the host and fire in a tight loop forever, which is the same silent
      * wrongness the initial delay used to have. It cannot be fixed by hopping the way a one-shot delay can, since the host owns the
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

    /** Tells the host this timer is not a reason to stay alive, where the host has an opinion.
      *
      * Node, Bun and Deno return a timer object carrying `unref`; a browser returns a number and has no process to hold, so there the call
      * is skipped and nothing is lost.
      */
    private[concurrent] def daemonize(handle: Any): Unit =
        val dynamic = handle.asInstanceOf[js.Dynamic]
        if js.typeOf(dynamic.unref) == "function" then
            val _ = dynamic.unref()
    end daemonize

end ScheduledExecutorService

object Executors:
    def newSingleThreadScheduledExecutor(): ScheduledExecutorService =
        new ScheduledExecutorService()
    def newScheduledThreadPool(ign: Int, ign2: ThreadFactory): ScheduledExecutorService =
        new ScheduledExecutorService()
end Executors
