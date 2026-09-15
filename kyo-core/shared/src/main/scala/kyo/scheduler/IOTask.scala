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
                                            input.onComplete { r =>
                                                this.removeInterrupt(input)
                                                curr = Sync.defer(cont(r.asInstanceOf[Result[Nothing, C]]))
                                                Scheduler.get.schedule(this)
                                            }
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
        val next =
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
        val ctx = context
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
        parent.foreach(p => p.interrupts(task))
        Scheduler.get.schedule(task)
        task
    end apply

end IOTask
