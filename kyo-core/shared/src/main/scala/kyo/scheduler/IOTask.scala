package kyo.scheduler

import kyo.*
import kyo.Result.Error
import kyo.kernel.*
import kyo.kernel.ArrowEffect
import kyo.kernel.internal.*
import kyo.scheduler.IOTask.*
import scala.util.control.NonFatal

sealed private[kyo] class IOTask[Ctx, E, A] private (
    private var curr: A < (Ctx & Async & Abort[E]),
    private var trace: Trace,
    private var finalizers: Finalizers
) extends IOPromise[E, A] with Task:

    import IOTask.frame

    def context: Context = Context.empty

    final override def enter(frame: Frame, value: Any) =
        !shouldPreempt()

    // The promise this task is parked on, with the callback it registered there, or null when the task is
    // running. Volatile because the writer is this task's own worker and the reader is whichever thread
    // completes the task; see `releaseJoin` for why that pairing is what makes the handoff safe.
    @volatile private var join: JoinLink = null

    // The parent whose interrupt cascades into this task, or null for a detached task. Held so the link can be
    // dropped when this task completes; a parent that outlives many children would otherwise accumulate one
    // registration per child it ever forked. Written once before this task is published (see IOTask.apply).
    @volatile private var parentLink: IOPromise[?, ?] = null

    private[scheduler] def linkParent(p: IOPromise[?, ?]): Unit =
        parentLink = p
        p.interrupts(this)
    end linkParent

    /** Drops this task's registration on its parent. A completed task has nothing left to interrupt, so the link is
      * dead weight, and on a parent that keeps forking (a supervisor, a poll loop) the dead links accumulate for as
      * long as the parent lives.
      */
    private def releaseParentLink(): Unit =
        val parent = parentLink
        if parent ne null then
            parentLink = null
            parent.remove(this)
    end releaseParentLink

    /** Takes this task off the promise it is parked on.
      *
      * A parked task is reachable from that promise's waiter chain through the callback it registered, and the
      * callback holds this task's continuation. Nothing completes a promise that no longer has a reason to
      * complete, so a task that dies while parked would otherwise keep itself, its trace, and its continuation
      * reachable from that promise for as long as the promise lives.
      *
      * Claimed with getAndSet semantics (read, then null) so the release runs once even though the parking
      * worker may be running the post-registration recheck concurrently. Removing twice is harmless anyway:
      * the second walk finds nothing.
      */
    private def releaseJoin(): Unit =
        val claimed = join
        if claimed ne null then
            join = null
            claimed.promise.remove(claimed)
    end releaseJoin

    // Fires on BOTH completion paths once this task's own waiters have been notified, which is the invariant
    // the release wants: the registration must live exactly as long as the task is waiting, and the task stops
    // waiting when it completes for ANY reason, not only when it is interrupted. Deliberately NOT onComplete:
    // interrupting this task cascades into the promise it awaits, and that promise completing is what
    // reschedules this task to run its finalizers. Releasing before the flush would cut that off.
    final override def onSettled(): Unit =
        releaseJoin()
        releaseParentLink()
    end onSettled

    final override def onComplete() =
        doPreempt()
        // The promise just completed (value or interrupt): drop accumulated runtime so a
        // still-queued task runs promptly to observe completion and run finalizers. Benign on
        // value-completion; a priority boost on interrupt.
        resetRuntime()
    end onComplete

    final override def addFinalizer(f: Maybe[Error[Any]] => Unit) =
        finalizers = finalizers.add(f)

    final override def removeFinalizer(f: Maybe[Error[Any]] => Unit) =
        finalizers = finalizers.remove(f)

    // Fiber interruption is recorded by IOPromise.interrupt's CAS of the promise state to
    // Error, the single source of truth. needsInterrupt and eval's stop check both read
    // it, so an interrupt can never be lost to a racing scheduler-level state update.
    final override def needsInterrupt(): Boolean =
        !isPending()

    final override def fiberTrace(): String =
        val snapshot = trace
        if snapshot eq null then ""
        else
            try Trace.render(snapshot)
            // Contain ANY throw (not just NonFatal): a diagnostic cross-thread read of a mutable trace
            // buffer must never escape to the leak-probe thread; any failure falls back to the JVM stack.
            catch case _: Throwable => ""
        end if
    end fiberTrace

    // Bumps the interrupt epoch and wakes the BlockingMonitor AFTER the promise CAS, so the
    // worker rebuild and monitor scan it triggers already see needsInterrupt() and the runtime
    // reset. The pre-CAS preInterrupt hook would let a worker spend its one gated rebuild
    // before the reset exists, stranding the task at its stale key.
    final override def onInterrupted(): Unit =
        Scheduler.get.notifyInterrupt()

    private inline def erasedAbortTag = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    private inline def locally[A](inline f: A): A = f

    final private def eval(startMillis: Long, clock: InternalClock, deadline: Long)(using Safepoint): A < (Ctx & Async & Abort[E]) =
        try
            val next: A < (Ctx & Async & Abort[E]) =
                Isolate.internal.restoring(trace, this) {
                    ArrowEffect.handlePartial(erasedAbortTag, Tag[Async.Join], curr, context)(
                        stop =
                            // !isPending() is the authoritative interrupt signal: IOPromise.interrupt
                            // CAS-completes the promise, so checking it here stops an interrupted fiber even if
                            // the racing scheduler preemption flag was lost. Ordered after shouldPreempt() and
                            // the deadline check so a step that stops for either of those skips the read.
                            shouldPreempt() || (deadline != Long.MaxValue && clock.currentMillis() > deadline) || needsInterrupt(),
                        [C] =>
                            (input, cont) =>
                                locally {
                                    completeDiscard(input.asInstanceOf[Result[E, A]])
                                    nullResult
                                },
                        [C] =>
                            (joinInput, cont) =>
                                locally {
                                    // Invoking joinInput registers the interrupt cascade link on THIS IOTask
                                    // before we read the promise's state (see Async.useResult).
                                    val input = joinInput(this)
                                    input.poll() match
                                        case null =>
                                            cont(null)
                                        case Present(r) =>
                                            // Promise was already complete when the thunk ran, so drop the
                                            // cascade link the thunk pre-registered so it doesn't accumulate.
                                            this.removeInterrupt(input)
                                            cont(r.asInstanceOf[Result[Nothing, C]])
                                        case Absent =>
                                            curr = nullResult
                                            val link =
                                                new JoinLink(input):
                                                    def apply(r: Result[Any, Any]): Unit =
                                                        // Resuming: the registration is being consumed, so stop
                                                        // tracking it before this task can complete and try to
                                                        // remove a callback that has already fired.
                                                        join = null
                                                        IOTask.this.removeInterrupt(input)
                                                        curr = Sync.defer(cont(r.asInstanceOf[Result[Nothing, C]]))
                                                        Scheduler.get.schedule(IOTask.this)
                                                    end apply
                                            // Publish BEFORE registering: a task interrupted after this point is
                                            // released by `onComplete`, and one interrupted before it is caught by
                                            // the recheck below. Between them the two cover every interleaving.
                                            join = link
                                            input.asInstanceOf[IOPromise[Any, C]]
                                                .onComplete(link.asInstanceOf[Result[Any, C] => Any])
                                            // The interrupt may have landed while this task was registering, in
                                            // which case `onComplete` already ran and saw no registration to take.
                                            // Ordering argument: this read of the task's state and the interrupt's
                                            // write of it are both volatile, as are the two accesses of `join`, so
                                            // if the interrupt's release missed the link then its state write
                                            // precedes this read, and this branch removes the link instead.
                                            if !isPending() then releaseJoin()
                                            nullResult
                                    end match
                                }
                    )
                }
            if !isNull(next) then
                next.evalNow match
                    case Absent =>
                        next
                    case Present(a) =>
                        completeDiscard(Result.succeed(a))
                        nullResult
            else
                next
            end if
        catch
            case ex =>
                completeDiscard(new Result.Panic(ex))
                if !NonFatal(ex) then throw ex
                nullResult
        end try
    end eval

    final def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        val safepoint = Safepoint.get
        val next      =
            try eval(startMillis, clock, deadline)(using safepoint)
            catch
                case ex =>
                    // A fatal error unwinds eval before the normal termination path below runs. The task's promise
                    // is already completed with a Panic, but its finalizers would be skipped, stranding whatever
                    // resource or awaited promise they release. Run the finalizers and release the trace, then
                    // re-propagate the fatal.
                    if !finalizers.isEmpty then
                        finalizers.run(pollError())
                        finalizers = Finalizers.empty
                    if trace ne null then
                        safepoint.releaseTrace(trace)
                        trace = null.asInstanceOf[Trace]
                    curr = nullResult
                    throw ex
        if !isPending() then
            // On an interrupt that lands mid-slice, `next` is the accurate remainder whose head is the
            // suspension eval stopped in front of (for example an Async.Join), while `curr` is the stale
            // slice-start snapshot. Walking the stale `curr` head-only misses a join sitting one step
            // behind a Defer prefix and leaves the awaited promise without the interrupt cascade.
            val remainder = if !isNull(next) then next else curr
            if !isNull(remainder) && remainder.evalNow.isEmpty then
                ensureInterrupt(remainder)(using safepoint)
            if !finalizers.isEmpty then
                finalizers.run(pollError())
                finalizers = Finalizers.empty
            if trace ne null then
                safepoint.releaseTrace(trace)
                trace = null.asInstanceOf[Trace]
            curr = nullResult
            Task.Done
        else if !isNull(next) then
            curr = next
            Task.Preempted
        else
            Task.Done
        end if
    end run

    // Handle race when interrupted before processing Async.Join and linking interrupts.
    // Walks the interrupted remainder head-only via dispatchFirst: no Defer body is drained, so it
    // runs no user code and cannot reintroduce the Sync.ensure finalizer-drop reverted in 33bb29bd94.
    // Bypasses the Safepoint via dispatchFirst: by the time this runs the fiber's promise is already
    // complete (interrupt), so the preempt flag is set and handleFirst would short-circuit before
    // reaching the matcher. Invoking joinInput(this) registers the cascade link on this IOTask so the
    // interrupt propagates to the awaited promise.
    private def ensureInterrupt(remainder: A < (Ctx & Async & Abort[E]))(using Safepoint): Unit =
        ArrowEffect.dispatchFirst(Tag[Async.Join], remainder.asInstanceOf[Any < Async.Join]) {
            [C] => joinInput => discard(joinInput(this))
        }
    end ensureInterrupt

    private inline def nullResult = null.asInstanceOf[A < Ctx & Async & Abort[E]]

    override def toString =
        s"IOTask(id = ${hashCode()}, state = ${stateString()}, preempt = ${{ shouldPreempt() }}, finalizers = ${finalizers.size()}, curr = ${curr})"

end IOTask

object IOTask:

    /** A task's registration on the promise it is parked on, carrying the promise so the task can take the
      * registration back without a second field. It replaces the closure the join would allocate anyway, so
      * parking costs no more than it did.
      */
    abstract private[scheduler] class JoinLink(val promise: IOPromise[?, ?]) extends (Result[Any, Any] => Unit)

    private val _frame                = Frame.internal
    private inline given frame: Frame = _frame

    // Install the scheduler's Diagnostics dumper at kyo-core's first touch of the scheduler: this object initializes when the first
    // fiber task is created, so a leaf that later hangs has the scheduler's live worker state in its Diagnostics.dumpAll() instead of blank.
    SchedulerDiagnostics.init()

    /** When `parent` is present, it is linked to interrupt the new task BEFORE the task is scheduled. This closes the window where the parent
      * is interrupted after a child starts but before the child is registered for interruption, orphaning the child. Doing it here, before
      * `schedule`, means the child cannot run unlinked. The caller reads the parent once (from the Safepoint interceptor) and passes it, so
      * this does not read the thread-local per child. Detached creators (top-level and `Fiber.initUnscoped`, which must not inherit their
      * creator's cancellation) pass `Absent`.
      */
    def apply[Ctx, E, A](
        curr: A < (Ctx & Async & Abort[E]),
        trace: Trace,
        context: Context,
        parent: Maybe[IOPromise[?, ?]] = Absent,
        finalizers: Finalizers = Finalizers.empty,
        runtime: Int = 0
    ): IOTask[Ctx, E, A] =
        val ctx  = context
        val task =
            if ctx.isEmpty then
                new IOTask(curr, trace, finalizers)
            else
                new IOTask(curr, trace, finalizers):
                    override def context = ctx
        task.addRuntime(runtime)
        // Link the parent to interrupt this task BEFORE it is scheduled, so a parent interrupt that lands
        // while children are still launching cannot orphan a child that started but was not yet registered.
        // The caller reads the parent once and passes it, instead of this reading the Safepoint per task.
        parent.foreach(task.linkParent)
        Scheduler.get.schedule(task)
        task
    end apply

end IOTask
