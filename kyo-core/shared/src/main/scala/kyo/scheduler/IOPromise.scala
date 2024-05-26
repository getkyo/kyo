package kyo.scheduler

import IOPromise.*
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kyo.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] class IOPromise[T](state: State[T])
    extends AtomicReference(state):

    def this() = this(Pending())

    final def isDone(): Boolean =
        @tailrec def loop(promise: IOPromise[T]): Boolean =
            promise.get() match
                case p: Pending[T] @unchecked =>
                    false
                case l: Linked[T] @unchecked =>
                    loop(l.p)
                case _ =>
                    true
        loop(this)
    end isDone

    final def interrupts(i: IOPromise[?]): Unit =
        @tailrec def loop(promise: IOPromise[T]): Unit =
            promise.get() match
                case p: Pending[T] @unchecked =>
                    if !promise.compareAndSet(p, p.interrupt(i)) then
                        loop(promise)
                case l: Linked[T] @unchecked =>
                    loop(l.p)
                case v =>
                    try discard(i.interrupt())
                    catch
                        case ex if NonFatal(ex) =>
                            Logs.unsafe.error("uncaught exception", ex)
        loop(this)
    end interrupts

    final def interrupt(): Boolean =
        @tailrec def loop(promise: IOPromise[T]): Boolean =
            promise.get() match
                case p: Pending[T] @unchecked =>
                    promise.complete(p, Fibers.interrupted) || loop(promise)
                case l: Linked[T] @unchecked =>
                    loop(l.p)
                case _ =>
                    false
        loop(this)
    end interrupt

    private def compress(): IOPromise[T] =
        @tailrec def loop(p: IOPromise[T]): IOPromise[T] =
            p.get() match
                case l: Linked[T] @unchecked =>
                    loop(l.p)
                case _ =>
                    p
        loop(this)
    end compress

    private def merge(p: Pending[T]): Unit =
        @tailrec def loop(promise: IOPromise[T]): Unit =
            promise.get() match
                case p2: Pending[T] @unchecked =>
                    if !promise.compareAndSet(p2, p2.merge(p)) then
                        loop(promise)
                case l: Linked[T] @unchecked =>
                    loop(l.p)
                case v =>
                    p.flush(v.asInstanceOf[T < IOs])
        loop(this)
    end merge

    final def become(other: IOPromise[T]): Boolean =
        @tailrec def loop(other: IOPromise[T]): Boolean =
            get() match
                case p: Pending[T] @unchecked =>
                    if compareAndSet(p, Linked(other)) then
                        other.merge(p)
                        true
                    else
                        loop(other)
                case _ =>
                    false
        loop(other.compress())
    end become

    final def onComplete(f: T < IOs => Unit): Unit =
        @tailrec def loop(promise: IOPromise[T]): Unit =
            promise.get() match
                case p: Pending[T] @unchecked =>
                    if !promise.compareAndSet(p, p.add(f)) then
                        loop(promise)
                case l: Linked[T] @unchecked =>
                    loop(l.p)
                case v =>
                    try f(v.asInstanceOf[T < IOs])
                    catch
                        case ex if NonFatal(ex) =>
                            Logs.unsafe.error("uncaught exception", ex)
        loop(this)
    end onComplete

    protected def onComplete(): Unit = {}

    private def complete(p: Pending[T], v: T < IOs): Boolean =
        compareAndSet(p, v) && {
            onComplete()
            p.flush(v)
            true
        }

    private val nullCompletion = IOs(null)

    final def complete(v: T < IOs): Boolean =
        val r =
            if !isNull(v) then v
            else nullCompletion.asInstanceOf[T < IOs]
        @tailrec def loop(): Boolean =
            get() match
                case p: Pending[T] @unchecked =>
                    complete(p, r) || loop()
                case _ =>
                    false
        loop()
    end complete

    final def block(deadline: Long): T < IOs =
        def loop(promise: IOPromise[T]): T < IOs =
            promise.get() match
                case _: Pending[T] @unchecked =>
                    IOs {
                        Scheduler.get.flush()
                        val b = new (T < IOs => Unit) with (() => T < IOs):
                            @volatile
                            private var result: T < IOs = null.asInstanceOf[T < IOs]
                            private val waiter          = Thread.currentThread()
                            def apply(v: T < IOs) =
                                result = v
                                LockSupport.unpark(waiter)
                            @tailrec def apply() =
                                if isNull(result) then
                                    val remainingNanos = deadline - System.currentTimeMillis()
                                    if remainingNanos <= 0 then
                                        return IOs.fail(Fibers.Interrupted)
                                    else if remainingNanos == Long.MaxValue then
                                        LockSupport.park(this)
                                    else
                                        LockSupport.parkNanos(this, remainingNanos)
                                    end if
                                    apply()
                                else
                                    result
                            end apply
                        onComplete(b)
                        b()
                    }
                case l: Linked[T] @unchecked =>
                    loop(l.p)
                case v =>
                    v.asInstanceOf[T < IOs]
        IOs(loop(this))
    end block
end IOPromise

private[kyo] object IOPromise:

    type State[T] = (T < IOs) | Pending[T] | Linked[T]

    case class Linked[T](p: IOPromise[T])

    abstract class Pending[T]:
        self =>

        def run(v: T < IOs): Pending[T]

        def add(f: T < IOs => Unit): Pending[T] =
            new Pending[T]:
                def run(v: T < IOs) =
                    try f(v)
                    catch
                        case ex if NonFatal(ex) =>
                            Logs.unsafe.error("uncaught exception", ex)
                    end try
                    self
                end run

        final def interrupt(p: IOPromise[?]): Pending[T] =
            new Pending[T]:
                def run(v: T < IOs) =
                    p.interrupt()
                    self
                end run

        final def merge(tail: Pending[T]): Pending[T] =
            @tailrec def loop(p: Pending[T], v: T < IOs): Pending[T] =
                p match
                    case _ if (p eq Pending.Empty) =>
                        tail
                    case p: Pending[T] =>
                        loop(p.run(v), v)
            new Pending[T]:
                def run(v: T < IOs) =
                    loop(self, v)
        end merge

        final def flush(v: T < IOs): Unit =
            @tailrec def loop(p: Pending[T]): Unit =
                p match
                    case _ if (p eq Pending.Empty) => ()
                    case p: Pending[T] =>
                        loop(p.run(v))
            loop(this)
        end flush
    end Pending

    object Pending:
        def apply[T](): Pending[T] = Empty.asInstanceOf[Pending[T]]
        case object Empty extends Pending[Nothing]:
            def run(v: Nothing < IOs) = this
    end Pending
end IOPromise
