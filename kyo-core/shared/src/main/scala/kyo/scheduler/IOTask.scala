package kyo.scheduler

import kyo.*
import kyo.Tag
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

    final override def addFinalizer(f: () => Unit) =
        finalizers = finalizers.add(f)

    final override def removeFinalizer(f: () => Unit) =
        finalizers = finalizers.remove(f)

    private inline def erasedAbortTag = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    private inline def locally[A](inline f: A): A = f

    final private def eval(startMillis: Long, clock: InternalClock, deadline: Long)(using Safepoint) =
        try
            curr = Isolate.restoring(trace, this) {
                ArrowEffect.handlePartial(erasedAbortTag, Tag[Async.Join], curr, context)(
                    stop =
                        shouldPreempt() || (deadline != Long.MaxValue && clock.currentMillis() > deadline),
                    [C] =>
                        (input, cont) =>
                            locally {
                                completeDiscard(input.asInstanceOf[Result[E, A]])
                                nullResult
                        },
                    [C] =>
                        (input, cont) =>
                            locally {
                                input.poll() match
                                    case null =>
                                        cont(null)
                                    case Present(r) =>
                                        cont(r.asInstanceOf[Result[Nothing, C]])
                                    case Absent =>
                                        val runtime    = (clock.currentMillis() - startMillis + this.runtime()).toInt
                                        val finalizers = this.finalizers
                                        this.finalizers = Finalizers.empty
                                        val trace = this.trace
                                        this.trace = null.asInstanceOf[Trace]
                                        this.interrupts(input)
                                        input.onComplete { r =>
                                            val task =
                                                IOTask(IO(cont(r.asInstanceOf[Result[Nothing, C]])), trace, context, finalizers, runtime)
                                            this.removeInterrupt(input)
                                            this.becomeDiscard(task)
                                        }
                                        nullResult
                        }
                )
            }
            if !isNull(curr) then
                curr.evalNow.foreach(a => completeDiscard(Result.succeed(a)))
        catch
            case ex =>
                completeDiscard(Result.panic(ex))
        end try
    end eval

    final def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        val safepoint = Safepoint.get
        eval(startMillis, clock, deadline)(using safepoint)
        if isNull(curr) || !isPending() then
            finalizers.run()
            finalizers = Finalizers.empty
            if trace ne null then
                safepoint.releaseTrace(trace)
                trace = null.asInstanceOf[Trace]
            curr = nullResult
            Task.Done
        else
            Task.Preempted
        end if
    end run

    private inline def nullResult = null.asInstanceOf[A < Ctx & Async & Abort[E]]

    override def toString =
        s"IOTask(state = ${stateString()}, preempt = ${{ shouldPreempt() }}, finalizers = ${finalizers.size()}, curr = ${curr})"

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
