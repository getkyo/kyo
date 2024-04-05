package kyo.scheduler

import kyo.*
import kyo.Locals.State
import kyo.core.*
import kyo.core.internal.*
import kyo.fibersInternal.*
import kyo.iosInternal.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] class IOTask[T](
    private var curr: T < Fibers,
    private var ensures: Ensures,
    @volatile private var state: Int // Math.abs(state) => runtime; state < 0 => preempting
) extends IOPromise[T] with Task
    with Preempt:
    import IOTask.*

    def locals: Locals.State = Locals.State.empty

    def check(): Boolean =
        state < 0

    def preempt() =
        if state > 0 then
            state = -state;

    def runtime(): Int =
        Math.abs(state)

    override protected def onComplete(): Unit =
        preempt()

    @tailrec private def eval(start: Long, curr: T < Fibers): T < Fibers =
        if check() then
            if isDone() then
                ensures.finalize()
                ensures = Ensures.empty
                nullIO
            else
                curr
        else
            curr match
                case kyo: Suspend[?, ?, ?, ?] =>
                    if kyo.tag == Tag[IOs] then
                        val k = kyo.asInstanceOf[Suspend[IO, Unit, T, Fibers]]
                        eval(start, k((), this, locals))
                    else if kyo.tag == Tag[FiberGets] then
                        val k = kyo.asInstanceOf[Suspend[Fiber, Any, T, Fibers]]
                        k.command match
                            case Promise(p) =>
                                this.interrupts(p)
                                val runtime = this.runtime() +
                                    (Coordinator.currentTick() - start).asInstanceOf[Int]
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
                                    start,
                                    k(v, this.asInstanceOf[Safepoint[FiberGets]], locals)
                                )
                        end match
                    else
                        IOs(bug.failTag(kyo.tag, Tag[FiberGets & IOs]))
                case _ =>
                    complete(curr.asInstanceOf[T < IOs])
                    finalize()
                    nullIO
        end if
    end eval

    def run() =
        val start = Coordinator.currentTick()
        try
            curr = eval(start, curr)
        catch
            case ex if (NonFatal(ex)) =>
                complete(IOs.fail(ex))
                curr = nullIO
        end try
        state = runtime() + (Coordinator.currentTick() - start).asInstanceOf[Int]
        if curr != nullIO then
            Task.Preempted
        else
            Task.Done
        end if
    end run

    def ensure(f: () => Unit): Unit =
        if curr != nullIO then
            ensures = ensures.add(f)

    def remove(f: () => Unit): Unit =
        ensures = ensures.remove(f)

    final override def toString =
        s"IOTask(id=${hashCode},preempting=${check()},curr=$curr,ensures=${ensures.size()},runtime=${runtime()},state=${get()})"
    end toString
end IOTask

private[kyo] object IOTask:
    private def nullIO[T] = null.asInstanceOf[T < IOs]

    def apply[T](
        v: T < Fibers,
        st: Locals.State,
        ensures: Ensures = Ensures.empty,
        runtime: Int = 1
    ): IOTask[T] =
        val f =
            if st eq Locals.State.empty then
                new IOTask[T](v, ensures, runtime)
            else
                new IOTask[T](v, ensures, runtime):
                    override def locals: State = st
        Scheduler.schedule(f)
        f
    end apply

    object TaskOrdering extends Ordering[IOTask[?]]:
        override def lt(x: IOTask[?], y: IOTask[?]): Boolean =
            val r = x.runtime()
            r == 0 || r < y.runtime()
        def compare(x: IOTask[?], y: IOTask[?]): Int =
            y.state - x.state
    end TaskOrdering

    given ord: Ordering[IOTask[?]] = TaskOrdering
end IOTask
