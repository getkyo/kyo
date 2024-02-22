package kyo.scheduler

import java.util.ArrayDeque
import java.util.Arrays
import java.util.IdentityHashMap
import kyo.*
import kyo.Locals.State
import kyo.core.*
import kyo.core.internal.*
import kyo.fibersInternal.*
import kyo.iosInternal.*
import org.jctools.queues.MpmcArrayQueue
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] object IOTask:
    private def nullIO[T] = null.asInstanceOf[T < IOs]

    def apply[T](
        v: T < Fibers,
        st: Locals.State,
        ensures: Any /*(() => Unit) | ArrayDeque[() => Unit]*/ = null,
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

    private val bufferCache = new MpmcArrayQueue[ArrayDeque[() => Unit]](1000)
    private def buffer(): ArrayDeque[() => Unit] =
        val b = bufferCache.poll()
        if b == null then new ArrayDeque()
        else b
    end buffer

    object TaskOrdering extends Ordering[IOTask[_]]:
        override def lt(x: IOTask[?], y: IOTask[?]): Boolean =
            val r = x.runtime()
            r == 0 || r < y.runtime()
        def compare(x: IOTask[?], y: IOTask[?]): Int =
            y.state - x.state
    end TaskOrdering

    given ord: Ordering[IOTask[_]] = TaskOrdering
end IOTask

private[kyo] class IOTask[T](
    private var curr: T < Fibers,
    private var ensures: Any /*(() => Unit) | ArrayDeque[() => Unit]*/ = null,
    @volatile private var state: Int // Math.abs(state) => runtime; state < 0 => preempting
) extends IOPromise[T]
    with Preempt:
    import IOTask.*

    def locals: Locals.State = Locals.State.empty

    def check(): Boolean =
        state < 0

    def preempt() =
        if state > 0 then
            state = -state;

    private def runtime(): Int =
        Math.abs(state)

    override protected def onComplete(): Unit =
        preempt()

    @tailrec private def eval(start: Long, curr: T < Fibers): T < Fibers =
        def finalize() =
            ensures match
                case null =>
                case f: (() => Unit) @unchecked =>
                    f()
                case arr: ArrayDeque[() => Unit] @unchecked =>
                    while !arr.isEmpty() do
                        val f = arr.poll()
                        f()
                    bufferCache.offer(arr)
            end match
            ensures = null
        end finalize
        if check() then
            if isDone() then
                finalize()
                nullIO
            else
                curr
        else
            curr match
                case kyo: Kyo[?, ?, ?, ?, ?] =>
                    if kyo.effect eq IOs then
                        val k = kyo.asInstanceOf[Kyo[IO, IOs, Unit, T, Fibers]]
                        eval(start, k((), this, locals))
                    else if kyo.effect eq FiberGets then
                        val k = kyo.asInstanceOf[Kyo[Fiber, FiberGets, Any, T, Fibers]]
                        k.value match
                            case Promise(p) =>
                                this.interrupts(p)
                                val runtime =
                                    this.runtime() + (Coordinator.tick() - start).asInstanceOf[Int]
                                p.onComplete { (v: Any < IOs) =>
                                    val io = IOs(k(
                                        v,
                                        this.asInstanceOf[Safepoint[Fiber, FiberGets]],
                                        locals
                                    ))
                                    this.become(IOTask(io, locals, ensures, runtime))
                                    ()
                                }
                                nullIO
                            case Done(v) =>
                                eval(
                                    start,
                                    k(v, this.asInstanceOf[Safepoint[Fiber, FiberGets]], locals)
                                )
                        end match
                    else
                        IOs.fail("Unhandled effect: " + kyo.effect)
                case _ =>
                    complete(curr.asInstanceOf[T < IOs])
                    finalize()
                    nullIO
        end if
    end eval

    def run(): Unit =
        val start = Coordinator.tick()
        try
            curr = eval(start, curr)
        catch
            case ex if (NonFatal(ex)) =>
                complete(IOs.fail(ex))
                curr = nullIO
        end try
        state = runtime() + (Coordinator.tick() - start).asInstanceOf[Int]
    end run

    def reenqueue(): Boolean =
        curr != nullIO

    def ensure(f: () => Unit): Unit =
        if curr != nullIO then
            ensures match
                case null =>
                    ensures = f
                case f0: (() => Unit) @unchecked =>
                    val b = buffer()
                    b.add(f0)
                    b.add(f)
                    ensures = b
                case arr: ArrayDeque[() => Unit] @unchecked =>
                    arr.add(f)

    def remove(f: () => Unit): Unit =
        ensures match
            case null =>
            case f0: (() => Unit) @unchecked =>
                if f0 eq f then ensures = null
            case arr: ArrayDeque[() => Unit] @unchecked =>
                def loop(): Unit =
                    if arr.remove(f) then loop()
                loop()

    final override def toString =
        val e = ensures match
            case null =>
                "[]"
            case f: (() => Unit) @unchecked =>
                s"[$f]"
            case arr: ArrayDeque[() => Unit] @unchecked =>
                Arrays.toString(arr.toArray)
        s"IOTask(id=${hashCode},preempting=${check()},curr=$curr,ensures=$ensures,runtime=${runtime()},state=${get()})"
    end toString
end IOTask
