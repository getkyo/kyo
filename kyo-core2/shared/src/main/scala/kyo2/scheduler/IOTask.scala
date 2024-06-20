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

private[kyo2] class IOTask[E, A](
    private var curr: A < (Async & Abort[E] & IO),
    private var ensures: Ensures
)(using Frame) extends IOPromise[E, A] with Task:

    override def enter(frame: Frame, value: Any) =
        !shouldPreempt()
    override def onComplete() =
        doPreempt()

    override def addEnsure(f: () => Unit) =
        if !isNull(curr) then
            ensures = ensures.add(f)

    override def removeEnsure(f: () => Unit) =
        ensures = ensures.remove(f)

    private inline def erasedAbortTag = Tag[Abort[Any]].asInstanceOf[Tag[Abort[E]]]

    private def eval(startMillis: Long, clock: InternalClock)(using Safepoint) =
        try
            curr =
                Effect.handle.partial(Tag[IO], erasedAbortTag, Tag[Async], curr)(
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
                                ensures.run()
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
                                        val task = IOTask(cont(r.asInstanceOf[Result[Nothing, C]]), ensures, runtime)
                                        discard(this.become(task.asInstanceOf))
                                    end if
                                }
                                nullResult
                        }
                ).evalPartial(interceptor = this)

            curr.evalNow.foreach(a => completeUnit(Result.success(a)))
        catch
            case ex =>
                ensures.run()
                completeUnit(Result.panic(ex))
        end try
    end eval

    def run(startMillis: Long, clock: InternalClock): Task.Result =
        eval(startMillis, clock)
        if isDone() then
            ensures = Ensures.empty
            curr = nullResult
            Task.Done
        else
            Task.Preempted
        end if
    end run

    private inline def nullResult = null.asInstanceOf[A]

end IOTask

object IOTask:

    val boundaryResult = Result.Panic(new IllegalStateException("Placeholder for async boundary."))

    def apply[E, A](
        curr: A < (Async & Abort[E] & IO),
        ensures: Ensures = Ensures.empty,
        runtime: Int = 0
    )(using Frame): IOTask[E, A] =
        val task = new IOTask(curr, ensures)
        task.addRuntime(runtime)
        Scheduler.get.schedule(task)
        task
    end apply

end IOTask
