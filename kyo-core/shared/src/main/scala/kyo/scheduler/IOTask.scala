package kyo.scheduler

import kyo.*
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Safepoint
import kyo.scheduler.IOTask.*

/** A fiber as the scheduler sees it: the promise it completes, the task it is queued as, and the remainder of
  * its computation between slices.
  *
  * It does not interpret the computation. The boundary `Fiber.init` installs answers everything the fiber
  * performs, so what arrives here is a computation of a result with nothing left pending, and running it to
  * a value is all there is to completing the fiber. What this holds is the remainder, the thread running it,
  * and the obligation a remainder nobody resumes still carries.
  */
sealed private[kyo] class IOTask[E, A] private (private var curr: Result[E, A] < Any)
    extends IOPromise[E, A] with Task:

    import IOTask.frame

    /** The thread running the current slice, or null between them.
      *
      * An interrupt and a preemption end a slice the same way: they stop that thread's safepoint, which the
      * eval polls on its own and parks at the first point where parking is sound. Nothing reaches into the
      * computation, and the fiber's own code checks nothing.
      */
    @volatile private var running: Thread = null

    /** The promise a pending join left this fiber waiting on, or null when the slice is not waiting. */
    private var awaiting: IOPromise[?, ?] = null

    final override def onComplete() =
        doPreempt()
        // The promise just completed (value or interrupt): drop accumulated runtime so a still-queued
        // task runs promptly to observe completion and release what it holds.
        resetRuntime()
    end onComplete

    // Fiber interruption is recorded by IOPromise.interrupt's CAS of the promise state to Error, the
    // single source of truth. Both this and the slice's own stop read it, so an interrupt can never be
    // lost to a racing scheduler-level state update.
    final override def needsInterrupt(): Boolean =
        !isPending()

    final override def doPreempt(): Unit =
        super.doPreempt()
        stopSlice()

    // Bumps the interrupt epoch and wakes the BlockingMonitor AFTER the promise CAS, so the worker
    // rebuild and monitor scan it triggers already see needsInterrupt() and the runtime reset.
    final override def onInterrupted(): Unit =
        stopSlice()
        Scheduler.get.notifyInterrupt()

    /** Ends the running slice, if there is one. */
    private def stopSlice(): Unit =
        val thread = running
        if thread ne null then discard(Safepoint.stop(thread))

    /** Records that the fiber is waiting on a promise, and ends the slice.
      *
      * Stopping is what ends it: the eval parks the remainder with the awaited operation at its head, and
      * the wakeup `run` registers resumes it by rescheduling. The remainder re-enters the boundary with a
      * fresh continuation and asks again, so a promise that completes between these two is seen rather than
      * waited on twice.
      */
    private[kyo] def await(promise: IOPromise[?, ?]): Unit =
        awaiting = promise
        discard(Safepoint.stop(Thread.currentThread()))

    final override def fiberTrace(): String =
        val snapshot = curr
        if isNull(snapshot) then ""
        else
            // Contain ANY throw: a diagnostic cross-thread read of the remainder must never escape to the
            // leak-probe thread; any failure falls back to the JVM stack.
            try snapshot.toString
            catch case _: Throwable => ""
        end if
    end fiberTrace

    final def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        if !isPending() then
            // Completed while queued, by an interrupt or from outside: the remainder is abandoned, so
            // what it holds is released without any of it running.
            finish()
            Task.Done
        else
            awaiting = null
            val thread = Thread.currentThread()
            running = thread
            val previous = current.get()
            current.set(this)
            val next =
                try Eval.partial(curr)
                finally
                    current.set(previous)
                    running = null
            next.evalNow match
                case Present(result) =>
                    curr = nullResult
                    completeDiscard(result)
                    Task.Done
                case Absent =>
                    curr = next
                    if !isPending() then
                        // completed during the slice, so the remainder it stopped at is abandoned
                        finish()
                        Task.Done
                    else
                        val promise = awaiting
                        if isNull(promise) then Task.Preempted
                        else
                            awaiting = null
                            // the slice's last action: once the wakeup can fire another worker may run this
                            // task concurrently with the return, so no task state is written after it
                            promise.onComplete { _ =>
                                this.removeInterrupt(promise)
                                Scheduler.get.schedule(this)
                            }
                            Task.Done
                        end if
                    end if
            end match
        end if
    end run

    /** Gives up the remainder, releasing what it holds.
      *
      * A parked computation carries its outstanding releases rather than running them, because whoever holds
      * it may carry on. This fiber will not: its promise is complete and nothing will resume it, so the
      * releases are owed here.
      */
    private def finish(): Unit =
        val remainder = curr
        curr = nullResult
        if !isNull(remainder) then remainder.finalizeResources
    end finish

    private inline def nullResult = null.asInstanceOf[Result[E, A] < Any]

    override def toString =
        s"IOTask(id = ${hashCode()}, state = ${stateString()}, preempt = ${{ shouldPreempt() }}, curr = ${curr})"

end IOTask

object IOTask:

    private val _frame                = Frame.internal
    private inline given frame: Frame = _frame

    /** The fiber running on this thread, or absent where none is.
      *
      * The other half of the link a running slice makes. A fiber's boundary needs the fiber it belongs to:
      * to register an interrupt cascade on it, and to tell it what it is waiting for. The boundary is built
      * before the fiber exists, so it asks here rather than closing over one.
      */
    private val current: ThreadLocal[IOTask[?, ?]] =
        new ThreadLocal[IOTask[?, ?]]

    private[kyo] def currentTask(): Maybe[IOTask[?, ?]] = Maybe(current.get())

    /** When `parent` is present, it is linked to interrupt the new task BEFORE the task is scheduled. This closes the window where the parent
      * is interrupted after a child starts but before the child is registered for interruption, orphaning the child. Doing it here, before
      * `schedule`, means the child cannot run unlinked. Detached creators (top-level and `Fiber.initUnscoped`, which must not inherit their
      * creator's cancellation) pass `Absent`.
      */
    def apply[E, A](
        v: Result[E, A] < Any,
        parent: Maybe[IOPromise[?, ?]] = Absent,
        runtime: Int = 0
    ): IOTask[E, A] =
        val task = new IOTask[E, A](v)
        task.addRuntime(runtime)
        parent.foreach(p => p.interrupts(task))
        Scheduler.get.schedule(task)
        task
    end apply

end IOTask
