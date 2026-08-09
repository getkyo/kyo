package kyo.scheduler

import kyo.*
import kyo.kernel.*
import kyo.kernel.ArrowEffect
import kyo.kernel.internal.*
import kyo.scheduler.IOTask.*
import scala.util.control.NonFatal

sealed private[kyo] class IOTask[Ctx, E, A] private (
    private var curr: A < (Ctx & Async & Abort[E])
) extends IOPromise[E, A] with Task:

    import IOTask.frame

    def context: Context = Context.empty

    // The safepoint of the thread currently running this task's slice, published so completion
    // from another thread (an interrupt or an external value) can preempt the running drive;
    // null between slices.
    @volatile private var running: Safepoint = null

    // Set by the join handler when it parks the slice on a pending promise: distinguishes a
    // parked return (wait for the completion callback to reschedule) from a preempted one
    // (the scheduler reschedules now). Slice-local, reset at each run.
    private var parked = false

    final override def onComplete() =
        doPreempt()
        // The promise just completed (value or interrupt): drop accumulated runtime so a
        // still-queued task runs promptly to observe completion and run finalizers. Benign on
        // value-completion; a priority boost on interrupt.
        resetRuntime()
        // Stop an in-flight slice: the drive polls its safepoint every bounce, so the request
        // parks it at the next step and run() observes the completed promise.
        val sp = running
        if sp ne null then sp.preempt()
    end onComplete

    // Fiber interruption is recorded by IOPromise.interrupt's CAS of the promise state to
    // Error, the single source of truth, so an interrupt can never be lost to a racing
    // scheduler-level state update.
    final override def needsInterrupt(): Boolean =
        !isPending()

    final override def fiberTrace(): String =
        // Best effort: the chain's structural render, depth bounded. A diagnostic cross-thread
        // read of the computation must never escape to the leak-probe thread; any failure
        // falls back to the JVM stack.
        val snapshot = curr
        if isNull(snapshot) then ""
        else
            try snapshot.toString
            catch case _: Throwable => ""
        end if
    end fiberTrace

    // Wakes the BlockingMonitor AFTER the promise CAS, so the worker rebuild and monitor scan
    // it triggers already see needsInterrupt() and the runtime reset.
    final override def onInterrupted(): Unit =
        Scheduler.get.notifyInterrupt()

    private inline def erasedAbortTag = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    final private def eval(): A < (Ctx & Async & Abort[E]) =
        try
            ArrowEffect.handlePartial(Tag[Async.Join], curr, context)(
                [C] =>
                    (joinInput, cont) =>
                        // Invoking joinInput registers the interrupt cascade link on THIS IOTask
                        // before we read the promise's state (see Async.useResult).
                        val input = joinInput(this)
                        input.poll() match
                            case Present(r) =>
                                // Promise was already complete when the thunk ran, so drop the
                                // cascade link the thunk pre-registered so it doesn't accumulate.
                                this.removeInterrupt(input)
                                Maybe(cont(r.asInstanceOf[Result[Nothing, C]]))
                            case Absent =>
                                // Park: handlePartial returns the remainder with the join still
                                // pending, and the next slice re-enters this handler with a fresh
                                // continuation and polls again, so the callback only reschedules.
                                // The link registered above stays for the cascade while parked;
                                // dropping it here balances the re-registration at re-entry.
                                parked = true
                                input.onComplete { _ =>
                                    this.removeInterrupt(input)
                                    Scheduler.get.schedule(this)
                                }
                                Maybe.Absent
                        end match
            )
        catch
            case ex =>
                completeDiscard(new Result.Panic(ex))
                if !NonFatal(ex) then throw ex
                nullResult
    end eval

    final def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        if !isPending() then
            // Completed while queued (interrupt or external completion): finalize the retained
            // remainder without driving any user code.
            finish(curr)
            Task.Done
        else
            parked = false
            val sp = Safepoint.beginSlice(deadline)
            running = sp
            val next =
                try eval()
                finally
                    running = null
                    sp.endSlice()
            if !isPending() then
                // Completed during the slice. On an interrupt that lands mid-slice, `next` is the
                // accurate remainder whose head is the suspension the drive stopped in front of,
                // while `curr` is the stale slice-start snapshot.
                finish(if !isNull(next) then next else curr)
                Task.Done
            else if isNull(next) then
                Task.Done
            else
                next.evalNow match
                    case Present(a) =>
                        completeDiscard(Result.succeed(a))
                        curr = nullResult
                        Task.Done
                    case Absent =>
                        if completeAbort(next) then
                            // The abort completed the promise; the discarded remainder still
                            // carries its finalizers.
                            finish(next)
                            Task.Done
                        else if parked then
                            curr = next
                            Task.Done
                        else
                            curr = next
                            Task.Preempted
                end match
            end if
        end if
    end run

    // The completion of an unhandled Abort operation that crossed every installed handler: the
    // fiber boundary interprets it as promise failure.
    private def completeAbort(remainder: A < (Ctx & Async & Abort[E])): Boolean =
        var completed = false
        ArrowEffect.dispatchFirst(erasedAbortTag, remainder) {
            [C] =>
                input =>
                    completed = true
                    completeDiscard(input.asInstanceOf[Result[E, A]])
        }
        completed
    end completeAbort

    // Runs after the promise completed with the computation discarded: registers the interrupt
    // cascade for a join the drive stopped in front of, and runs the finalizers the discarded
    // remainder still carries.
    private def finish(remainder: A < (Ctx & Async & Abort[E])): Unit =
        if !isNull(remainder) && remainder.evalNow.isEmpty then
            ensureInterrupt(remainder)
            remainder.finalizeBracket
        curr = nullResult
    end finish

    // Handle race when interrupted before processing Async.Join and linking interrupts.
    // Walks the interrupted remainder head-only via dispatchFirst: no Defer body is drained, so it
    // runs no user code and cannot reintroduce the Sync.ensure finalizer-drop reverted in 33bb29bd94.
    // Invoking joinInput(this) registers the cascade link on this IOTask so the interrupt
    // propagates to the awaited promise.
    private def ensureInterrupt(remainder: A < (Ctx & Async & Abort[E])): Unit =
        ArrowEffect.dispatchFirst(Tag[Async.Join], remainder.asInstanceOf[Any < Async.Join]) {
            [C] => joinInput => discard(joinInput(this))
        }
    end ensureInterrupt

    private inline def nullResult = null.asInstanceOf[A < Ctx & Async & Abort[E]]

    override def toString =
        s"IOTask(id = ${hashCode()}, state = ${stateString()}, preempt = ${{ shouldPreempt() }}, curr = ${curr})"

end IOTask

object IOTask:

    private val _frame                = Frame.internal
    private inline given frame: Frame = _frame

    /** The fiber identity as context state: the boundary binds the running task into the context it drives with, once at
      * creation, and spawn sites read the parent for interrupt linking from the context threaded to them, replacing the old
      * kernel's per-thread interceptor read.
      */
    sealed private[kyo] trait CurrentFiber extends ContextEffect[IOPromise[?, ?]]

    private[kyo] def parentIn(context: Context): Maybe[IOPromise[?, ?]] =
        if context.contains(Tag[CurrentFiber]) then Present(context.get(Tag[CurrentFiber]))
        else Absent

    /** When `parent` is present, it is linked to interrupt the new task BEFORE the task is scheduled. This closes the window where the parent
      * is interrupted after a child starts but before the child is registered for interruption, orphaning the child. Doing it here, before
      * `schedule`, means the child cannot run unlinked. The caller reads the parent once and passes it, so this does not read the
      * thread-local per child. Detached creators (top-level and `Fiber.initUnscoped`, which must not inherit their creator's cancellation)
      * pass `Absent`.
      */
    def apply[Ctx, E, A](
        curr: A < (Ctx & Async & Abort[E]),
        context: Context,
        parent: Maybe[IOPromise[?, ?]] = Absent,
        runtime: Int = 0
    ): IOTask[Ctx, E, A] =
        val ctx = context
        val task =
            new IOTask[Ctx, E, A](curr):
                override val context = ctx.set(Tag[CurrentFiber], this)
        task.addRuntime(runtime)
        // Link the parent to interrupt this task BEFORE it is scheduled, so a parent interrupt that lands
        // while children are still launching cannot orphan a child that started but was not yet registered.
        // The caller reads the parent once and passes it, instead of this reading the thread local per task.
        parent.foreach(p => p.interrupts(task))
        Scheduler.get.schedule(task)
        task
    end apply

end IOTask
