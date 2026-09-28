package kyo.scheduler.util

import java.util.List as JList
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import kyo.scheduler.InternalClock
import kyo.scheduler.bug
import scala.util.control.NonFatal

/** Executor wrapper that re-submits a runnable the delegate lost or refused while alive.
  *
  * The scheduler treats a dispatch that `execute` returned from as a promise that the runnable will run, and `Worker.wakeup` has no
  * fallback for an `execute` that throws: either failure leaves a worker dispatched with queued work and nothing to mount it. On
  * scala-native 0.5.12 both happen:
  *   - A lost handoff: `ThreadPoolExecutor` can lose a `SynchronousQueue` handoff, so `execute` returns normally yet no pool thread ever
  *     picks the runnable up.
  *   - A refused submission: when no pool thread is parked, `execute` creates a thread on the submitting thread, and that path can throw,
  *     for instance an `InterruptedException` on a carrier whose interrupt flag is set while it settles an interrupted fiber.
  *
  * So `execute` never lets a submission failure reach the caller while the pool is alive. A refused submission is tracked exactly like an
  * accepted one, and an `InterruptedException` has the caller's interrupt re-armed, since the interrupted fiber's own handling reads it.
  * Only a shut-down pool's rejection propagates, because the caller owns the shutdown.
  *
  * Each submission is tracked until it starts. A watchdog re-submits one that has not started within a growing delay, so a wedged pool
  * sees a bounded trickle rather than a stream. Re-submission is safe at any multiplicity: every arrival shares one started flag, so the
  * runnable executes exactly once even when a re-submission races the delayed original.
  *
  * Retries never give up. The pool heals by spawning a thread when no parked one takes the offer, and a re-submission that lands on the
  * same wedged waiter can be lost again, so a capped retry count would strand exactly the case this exists for.
  */
final private[scheduler] class HandoffRetryExecutor(pool: ExecutorService, factory: ThreadFactory) extends AbstractExecutorService {

    import HandoffRetryExecutor.*

    private val pending = new ConcurrentLinkedQueue[Tracked]

    private val refusals                  = new LongAdder
    @volatile private var refusalReported = false

    def execute(command: Runnable): Unit = {
        val tracked = new Tracked(command)
        // Submit before tracking: a shut-down pool's rejection propagates without leaving an entry behind, and an
        // entry added just after a lost handoff or a refusal is still seen unstarted by the watchdog, which acts on
        // age rather than on arrival order.
        try {
            pool.execute(tracked)
            if (refusalReported) refusalReported = false
        } catch {
            case ex: RejectedExecutionException if pool.isShutdown() =>
                throw ex
            case ex: InterruptedException =>
                refused(ex)
                Thread.currentThread().interrupt()
            case ex if NonFatal(ex) =>
                refused(ex)
        }
        val _ = pending.add(tracked)
    }

    private def refused(ex: Throwable): Unit = {
        refusals.increment()
        // Once per streak of refusals, ended by an accepted submission: a pool that keeps refusing would otherwise log
        // on every dispatch.
        if (!refusalReported) {
            refusalReported = true
            bug("Worker dispatch was refused by the pool. The watchdog re-submits it.", ex)
        }
    }

    /** Submissions awaiting their first run. Test-visible so the tracking set can be asserted to drain. */
    private[scheduler] def pendingSize: Int = pending.size()

    /** Submissions the live pool refused, each then left to the watchdog. */
    private[scheduler] def submitFailures: Long = refusals.sum()

    private val watchdog = factory.newThread { () =>
        while (!pool.isShutdown()) {
            try {
                val it = pending.iterator()
                while (it.hasNext) {
                    val tracked = it.next()
                    if (tracked.started.get())
                        it.remove()
                    else if (tracked.elapsedMs() >= tracked.threshold()) {
                        tracked.retried += 1
                        tracked.submittedMs = InternalClock.monotonicMillis()
                        try pool.execute(tracked)
                        catch {
                            case _: RejectedExecutionException if pool.isShutdown() =>
                                // Shutdown owns the runnable's fate from here.
                                it.remove()
                            case ex if NonFatal(ex) =>
                                // Keep the entry: the next scan retries under the grown delay.
                                bug("Worker dispatch retry has failed.", ex)
                        }
                    }
                }
            } catch {
                // The watchdog is the only recovery for a lost handoff, so it must outlive any failure a scan hits.
                case ex if NonFatal(ex) => bug("Worker dispatch watchdog has failed.", ex)
            }
            Sleep(scanPeriodMs)
        }
        pending.clear()
    }
    watchdog.start()

    def shutdown(): Unit = pool.shutdown()

    def shutdownNow(): JList[Runnable] = {
        // Hand back what the caller submitted, not the tracking wrappers.
        val queued = pool.shutdownNow()
        val out    = new java.util.ArrayList[Runnable](queued.size())
        queued.forEach { r =>
            val _ = out.add(r match {
                case t: Tracked => t.command
                case other      => other
            })
        }
        out
    }

    def isShutdown(): Boolean                                    = pool.isShutdown()
    def isTerminated(): Boolean                                  = pool.isTerminated()
    def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = pool.awaitTermination(timeout, unit)
}

private[scheduler] object HandoffRetryExecutor {

    // Scan cadence. Sleep is nanosleep on Unix: unlike Thread.sleep it allocates no pipe descriptors, which a
    // permanent loop at this rate would otherwise churn.
    val scanPeriodMs = 5

    // First retry delay, doubling per attempt up to the cap. The floor clears a legitimate cold thread start,
    // which on an emulated runner serializes behind the shared init gate: retrying under that latency would
    // spawn a permanent extra thread for a dispatch that was merely slow. The cap matches the gate's own bounded
    // wait, so a wedged pool absorbs about one retry per gate wait.
    val firstRetryMs = 10
    val maxRetryMs   = 1000

    final class Tracked(val command: Runnable) extends Runnable {
        val started               = new AtomicBoolean(false)
        var retried               = 0
        @volatile var submittedMs = InternalClock.monotonicMillis()

        def run(): Unit =
            if (started.compareAndSet(false, true))
                command.run()

        def elapsedMs(): Long = InternalClock.monotonicMillis() - submittedMs

        def threshold(): Long = Math.min(firstRetryMs.toLong << retried, maxRetryMs.toLong)
    }
}
