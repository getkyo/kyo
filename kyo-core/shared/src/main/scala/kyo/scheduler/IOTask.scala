package kyo.scheduler

import kyo.{Clock as _, *}
import kyo.Locals.State
import kyo.core.*
import kyo.core.internal.*
import kyo.fibersInternal.*
import kyo.iosInternal.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] class IOTask[T](
    private var curr: T < Fibers,
    private var ensures: Ensures
) extends IOPromise[T] with Task
    with Preempt:
    import IOTask.*

    def locals: Locals.State = Locals.State.empty

    override def preempt(): Boolean =
        shouldPreempt()

    override protected def onComplete(): Unit =
        doPreempt()

    @tailrec private def eval(
        curr: T < Fibers,
        scheduler: Scheduler,
        startMillis: Long,
        clock: InternalClock
    ): T < Fibers =

        if preempt() then
            if isDone() then
                ensures.finalize()
                ensures = Ensures.empty
                nullIO
            else
                curr
        else
            curr match
                case kyo: Suspend[?, ?, ?, ?] =>
                    if kyo.tag =:= Tag[IOs] then
                        val k = kyo.asInstanceOf[Suspend[IO, Unit, T, Fibers]]
                        eval(k((), this, locals), scheduler, startMillis, clock)
                    else if kyo.tag =:= Tag[FiberGets] then
                        val k = kyo.asInstanceOf[Suspend[Fiber, Any, T, Fibers]]
                        k.command match
                            case Promise(p) =>
                                this.interrupts(p)
                                val runtime = (clock.currentMillis() - startMillis + this.runtime()).toInt
                                p.onComplete { (v: Any < IOs) =>
                                    val io = IOs(k(
                                        v,
                                        this.asInstanceOf[Safepoint[FiberGets]],
                                        locals
                                    ))
                                    this.become(IOTask(io, locals, ensures, runtime))
                                    ()
                                }
                                nullIO
                            case Done(v) =>
                                eval(
                                    k(v, this.asInstanceOf[Safepoint[FiberGets]], locals),
                                    scheduler,
                                    startMillis,
                                    clock
                                )
                        end match
                    else
                        IOs(bug.failTag(kyo.tag, Tag.Intersection[FiberGets & IOs]))
                case _ =>
                    complete(curr.asInstanceOf[T < IOs])
                    finalize()
                    nullIO
        end if
    end eval

    def run(startMillis: Long, clock: InternalClock) =
        val scheduler = Scheduler.get
        try
            curr = eval(curr, scheduler, startMillis, clock)
        catch
            case ex if (NonFatal(ex)) =>
                complete(IOs.fail(ex))
                curr = nullIO
        end try
        if !isNull(curr) then
            Task.Preempted
        else
            Task.Done
        end if
    end run

    def ensure(f: () => Unit): Unit =
        if !isNull(curr) then
            ensures = ensures.add(f)

    def remove(f: () => Unit): Unit =
        ensures = ensures.remove(f)

end IOTask

private[kyo] object IOTask:

    private def nullIO[T] = null.asInstanceOf[T < IOs]

    def apply[T](
        v: T < Fibers,
        st: Locals.State
    ): IOTask[T] =
        apply(v, st, Ensures.empty, 1)

    def apply[T](
        v: T < Fibers,
        st: Locals.State,
        ensures: Ensures,
        runtime: Int
    ): IOTask[T] =
        val f =
            if st eq Locals.State.empty then
                new IOTask[T](v, ensures)
            else
                new IOTask[T](v, ensures):
                    override def locals: State = st
        f.addRuntime(runtime)
        Scheduler.get.schedule(f)
        f
    end apply
end IOTask
