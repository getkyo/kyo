package kyo.scheduler

import kyo.*
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Safepoint
import kyo.scheduler.IOTask.*

sealed private[kyo] class IOTask[E, A] private (
    private var curr: Result[E, A] < Any
) extends IOPromise[E, A] with Task:

    import IOTask.frame

    // The thread running the current slice, or null between them. A preemption and an interrupt both
    // end a slice by stopping this thread's safepoint, which the eval polls on its own and parks at
    // the first point where parking is sound.
    @volatile private var running: Thread = null

    // The promise a pending join left this fiber waiting on, read once by run after the remainder is
    // stored, so nothing can resume this task before there is something to resume.
    private var awaiting: IOPromise[?, ?] = null

    final override def onComplete() =
        doPreempt()
        // The promise just completed (value or interrupt): drop accumulated runtime so a
        // still-queued task runs promptly to observe completion and run finalizers. Benign on
        // value-completion; a priority boost on interrupt.
        resetRuntime()
    end onComplete

    // Fiber interruption is recorded by IOPromise.interrupt's CAS of the promise state to
    // Error, the single source of truth. needsInterrupt and the slice's own stop both read
    // it, so an interrupt can never be lost to a racing scheduler-level state update.
    final override def needsInterrupt(): Boolean =
        !isPending()

    final override def fiberTrace(): String =
        val snapshot = curr
        if isNull(snapshot) then ""
        else
            try snapshot.toString
            // Contain ANY throw (not just NonFatal): a diagnostic cross-thread read of the remainder
            // must never escape to the leak-probe thread; any failure falls back to the JVM stack.
            catch case _: Throwable => ""
        end if
    end fiberTrace

    // Preemption reaches a running slice the same way an interrupt does: by stopping the thread's
    // safepoint. The scheduler's flag alone would not be seen, since nothing polls it now.
    final override def doPreempt(): Unit =
        super.doPreempt()
        stopSlice()

    // Bumps the interrupt epoch and wakes the BlockingMonitor AFTER the promise CAS, so the
    // worker rebuild and monitor scan it triggers already see needsInterrupt() and the runtime
    // reset. The pre-CAS preInterrupt hook would let a worker spend its one gated rebuild
    // before the reset exists, stranding the task at its stale key.
    final override def onInterrupted(): Unit =
        stopSlice()
        Scheduler.get.notifyInterrupt()

    private def stopSlice(): Unit =
        val thread = running
        if thread ne null then discard(Safepoint.stop(thread))

    // Called by the fiber boundary when the join it dispatched has not completed. Stopping is what ends
    // the slice: the eval parks the remainder with the join at its head, and the wakeup run registers
    // resumes it into the same clause, which polls again with a fresh continuation.
    private[kyo] def await(promise: IOPromise[?, ?]): Unit =
        awaiting = promise
        discard(Safepoint.stop(Thread.currentThread()))

    // The clock and deadline the scheduler passes are the JS preemption mechanism, which a
    // JS-specific Safepoint will carry; on this path a slice ends because its thread was stopped.
    final def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        if !isPending() then
            // Completed while queued, by an interrupt or from outside. The remainder is abandoned, and
            // nothing of it runs.
            abandon()
            Task.Done
        else
            awaiting = null
            running = Thread.currentThread()
            val previous = IOTask.current.get()
            IOTask.current.set(this)
            val next =
                try Eval.partial(curr)
                finally
                    IOTask.current.set(previous)
                    running = null
            next.evalNow match
                case Present(result) =>
                    // The boundary answered everything the fiber performed and the computation reached
                    // its result, which is the fiber's.
                    curr = nullResult
                    completeDiscard(result)
                    Task.Done
                case Absent =>
                    curr = next
                    if !isPending() then
                        // Interrupted or completed mid-slice: the remainder the eval stopped at is the
                        // accurate one, and nobody will resume it.
                        abandon()
                        Task.Done
                    else
                        val promise = awaiting
                        if isNull(promise) then Task.Preempted
                        else
                            awaiting = null
                            // The slice's last action: once the wakeup can fire another worker may run
                            // this task concurrently with this frame's return, so no task state is
                            // written after it.
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

    // A parked computation carries its outstanding releases rather than running them, because whoever
    // holds it may carry on. This fiber will not: its promise is complete and nothing will resume it,
    // so what the remainder holds is released here.
    private def abandon(): Unit =
        val remainder = curr
        curr = nullResult
        if !isNull(remainder) then remainder.finalizeResources
    end abandon

    private inline def nullResult = null.asInstanceOf[Result[E, A] < Any]

    override def toString =
        s"IOTask(id = ${hashCode()}, state = ${stateString()}, preempt = ${{ shouldPreempt() }}, curr = ${curr})"

end IOTask

object IOTask:

    private val _frame                = Frame.internal
    private inline given frame: Frame = _frame

    /** The fiber running on this thread, or null where none is.
      *
      * The fiber boundary needs the fiber whose slice it is running in: to register an interrupt cascade on
      * it, and to tell it what it is waiting for. The boundary is built before the fiber exists, so it asks
      * here rather than closing over one. It is also what a spawning fiber reads to link its children.
      */
    private[kyo] val current: ThreadLocal[IOTask[?, ?]] = new ThreadLocal[IOTask[?, ?]]

    private[kyo] def currentTask(): Maybe[IOTask[?, ?]] = Maybe(current.get())

    /** When `parent` is present, it is linked to interrupt the new task BEFORE the task is scheduled. This closes the window where the parent
      * is interrupted after a child starts but before the child is registered for interruption, orphaning the child. Doing it here, before
      * `schedule`, means the child cannot run unlinked. The caller reads the parent once and passes it, so this does not read the thread
      * local per child. Detached creators (top-level and `Fiber.initUnscoped`, which must not inherit their creator's cancellation) pass
      * `Absent`.
      */
    def apply[E, A](
        curr: Result[E, A] < Any,
        parent: Maybe[IOPromise[?, ?]] = Absent,
        runtime: Int = 0
    ): IOTask[E, A] =
        val task = new IOTask(curr)
        task.addRuntime(runtime)
        // Link the parent to interrupt this task BEFORE it is scheduled, so a parent interrupt that lands
        // while children are still launching cannot orphan a child that started but was not yet registered.
        // The caller reads the parent once and passes it, instead of this reading the Safepoint per task.
        parent.foreach(p => p.interrupts(task))
        Scheduler.get.schedule(task)
        task
    end apply

end IOTask
