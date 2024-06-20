package kyo2.scheduler

import kyo.Tag
import kyo.scheduler.InternalClock
import kyo.scheduler.Scheduler
import kyo.scheduler.Task
import kyo2.*
import kyo2.kernel.*
import kyo2.kernel.Effect
import kyo2.scheduler.IOTask.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo2] class IOTask[Ctx, E, A](
    private var curr: A < (Ctx & Async & Abort[E]),
    private var trace: Trace,
    private var ensures: Ensures
) extends IOPromise[E, A] with Task:

    import IOTask.frame

    def context: Context = Context.empty

    final override def enter(frame: Frame, value: Any) =
        !shouldPreempt()

    final override def onComplete() =
        doPreempt()

    final override def addEnsure(f: () => Unit) =
        if !isNull(curr) then
            ensures = ensures.add(f)

    final override def removeEnsure(f: () => Unit) =
        ensures = ensures.remove(f)

    private inline def erasedAbortTag = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    final private def eval(startMillis: Long, clock: InternalClock)(using Safepoint) =
        try
            curr = Boundary.restoring(trace, this) {
                Effect.handle.partial(Tag[IO], erasedAbortTag, Tag[Async.Join], curr, context)(
                    stop = shouldPreempt(),
                    [C] =>
                        (input, cont) =>
                            if shouldPreempt() then
                                Effect.defer(cont(()))
                            else
                                cont(())
                    ,
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
                                input.onComplete { r =>
                                    if !this.isDone() then
                                        val task = IOTask(cont(r.asInstanceOf[Result[Nothing, C]]), trace, context, ensures, runtime)
                                        this.becomeUnit(task)
                                    end if
                                }
                                nullResult
                        }
                )
            }

            curr.evalNow.foreach(a => completeUnit(Result.success(a)))
        catch
            case ex => completeUnit(Result.panic(ex))
        end try
    end eval

    final def run(startMillis: Long, clock: InternalClock): Task.Result =
        val safepoint = Safepoint.get
        eval(startMillis, clock)(using safepoint)
        if isDone() then
            ensures.run()
            ensures = Ensures.empty
            safepoint.releaseTrace(trace)
            trace = null.asInstanceOf[Trace]
            curr = nullResult
            Task.Done
        else
            Task.Preempted
        end if
    end run

    private inline def nullResult = null.asInstanceOf[A]

end IOTask

object IOTask:

    private val _frame                = Frame.derive
    private inline given frame: Frame = _frame

    val boundaryResult = Result.Panic(new IllegalStateException("Placeholder for async boundary."))

    def apply[Ctx, E, A](
        curr: A < (Ctx & Async & Abort[E]),
        trace: Trace,
        context: Context,
        ensures: Ensures = Ensures.empty,
        runtime: Int = 0
    )(using Frame): IOTask[Ctx, E, A] =
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
