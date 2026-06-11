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
        // The promise just completed (interrupt for a still-running fiber): drop accumulated
        // runtime so this task is scheduled promptly to observe the interrupt and run its
        // finalizers, instead of being deprioritized by the runtime it built up while running.
        resetRuntime()
    end onComplete

    final override def addFinalizer(f: Maybe[Error[Any]] => Unit) =
        finalizers = finalizers.add(f)

    final override def removeFinalizer(f: Maybe[Error[Any]] => Unit) =
        finalizers = finalizers.remove(f)

    // Fiber interruption is recorded by IOPromise.interrupt's CAS of the promise state to
    // Error — the single source of truth. needsInterrupt and eval's stop check both read
    // it, so an interrupt can never be lost to a racing scheduler-level state update.
    final override def needsInterrupt(): Boolean =
        !isPending()

    // Wakes the BlockingMonitor for an immediate scan. If the worker thread is blocked
    // (flat CPU time), Thread.interrupt() is dispatched once the monitor observes the
    // interrupted promise.
    final override def preInterrupt(): Boolean =
        Scheduler.get.notifyInterrupt()
        true
    end preInterrupt

    private inline def erasedAbortTag = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    private inline def locally[A](inline f: A): A = f

    final private def eval(startMillis: Long, clock: InternalClock, deadline: Long)(using Safepoint): A < (Ctx & Async & Abort[E]) =
        try
            val next: A < (Ctx & Async & Abort[E]) =
                Isolate.internal.restoring(trace, this) {
                    ArrowEffect.handlePartial(erasedAbortTag, Tag[Async.Join], curr, context)(
                        stop =
                            // !isPending() is the authoritative interrupt signal — IOPromise.interrupt
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
                                    // before we read the promise's state — see Async.useResult.
                                    val input = joinInput(this)
                                    input.poll() match
                                        case null =>
                                            cont(null)
                                        case Present(r) =>
                                            // Promise was already complete when the thunk ran — drop the
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
        val next      = eval(startMillis, clock, deadline)(using safepoint)
        if !isPending() then
            if !isNull(curr) && curr.evalNow.isEmpty then
                ensureInterrupt()(using safepoint)
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
    // Bypasses the Safepoint via dispatchFirst: by the time this runs the fiber's promise
    // is already complete (interrupt), so the preempt flag is set and handleFirst would
    // short-circuit before reaching the matcher. Invoking joinInput(this) registers the
    // cascade link on this IOTask so the interrupt propagates to the awaited promise.
    private def ensureInterrupt()(using Safepoint): Unit =
        ArrowEffect.dispatchFirst(Tag[Async.Join], curr.asInstanceOf[Any < Async.Join]) {
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

    def apply[Ctx, E, A](
        curr: A < (Ctx & Async & Abort[E]),
        trace: Trace,
        context: Context,
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
        Scheduler.get.schedule(task)
        task
    end apply

end IOTask
