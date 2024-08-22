package kyo.scheduler

import kyo.*
import kyo.Tag
import kyo.kernel.*
import kyo.kernel.Effect
import kyo.scheduler.InternalClock
import kyo.scheduler.IOTask.*
import kyo.scheduler.Scheduler
import kyo.scheduler.Task
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] class IOTask[Ctx, E, A] private (
    private var curr: A < (Ctx & Async & Abort[E]),
    private var trace: Trace,
    private var ensures: Ensures
) extends IOPromise[E, A] with Task:

    inline given Flat[A] = Flat.unsafe.bypass

    import IOTask.frame

    def context: Context = Context.empty

    final override def enter(frame: Frame, value: Any) =
        !shouldPreempt()

    final override def onComplete() =
        doPreempt()

    final override def addEnsure(f: () => Unit) =
        ensures = ensures.add(f)

    final override def removeEnsure(f: () => Unit) =
        ensures = ensures.remove(f)

    private inline def erasedAbortTag = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    final private def eval(startMillis: Long, clock: InternalClock)(using Safepoint) =
        try
            curr = Boundary.restoring(trace, this) {
                Effect.handle.partial(Tag[IO], erasedAbortTag, Tag[Async.Join], curr, context)(
                    stop = shouldPreempt(),
                    [C] => (input, cont) => cont(()),
                    [C] =>
                        (input, cont) =>
                            locally {
                                completeUnit(input.asInstanceOf[Result[E, A]])
                                nullResult
                        },
                    [C] =>
                        (input, cont) =>
                            locally {
                                val runtime = (clock.currentMillis() - startMillis + this.runtime()).toInt
                                this.interrupts(input)
                                val ensures = this.ensures
                                this.ensures = Ensures.empty
                                val trace = this.trace
                                this.trace = null.asInstanceOf[Trace]
                                input.onComplete { r =>
                                    val task = IOTask(IO(cont(r.asInstanceOf[Result[Nothing, C]])), trace, context, ensures, runtime)
                                    this.becomeUnit(task)
                                }
                                nullResult
                        }
                )
            }
            if !isNull(curr) then
                curr.evalNow.foreach(a => completeUnit(Result.success(a)))
        catch
            case ex =>
                completeUnit(Result.panic(ex))
        end try
    end eval

    final def run(startMillis: Long, clock: InternalClock): Task.Result =
        val safepoint = Safepoint.get
        eval(startMillis, clock)(using safepoint)
        if isNull(curr) || !isPending() then
            ensures.run()
            ensures = Ensures.empty
            if !isNull(trace) then
                safepoint.releaseTrace(trace)
                trace = null.asInstanceOf[Trace]
            curr = nullResult
            Task.Done
        else
            Task.Preempted
        end if
    end run

    private inline def nullResult = null.asInstanceOf[A < Ctx & Async & Abort[E]]

end IOTask

object IOTask:

    private val _frame                = Frame.internal
    private inline given frame: Frame = _frame

    def apply[Ctx, E, A](
        curr: A < (Ctx & Async & Abort[E]),
        trace: Trace,
        context: Context,
        ensures: Ensures = Ensures.empty,
        runtime: Int = 0
    )(using Frame, Flat[A]): IOTask[Ctx, E, A] =
        val ctx = context
        val task =
            if ctx.isEmpty then
                new IOTask(curr, trace, ensures)
            else
                new IOTask(curr, trace, ensures):
                    override def context = ctx
        task.addRuntime(runtime)
        Scheduler.get.schedule(task)
        task
    end apply

end IOTask
