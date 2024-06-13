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
                    promise.complete(p, Fibers.interruptedResult) || loop(promise)
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
                    p.flush(v.asInstanceOf[Result[T]])
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

    final def onComplete(f: Result[T] => Unit): Unit =
        @tailrec def loop(promise: IOPromise[T]): Unit =
            promise.get() match
                case p: Pending[T] @unchecked =>
                    if !promise.compareAndSet(p, p.add(f)) then
                        loop(promise)
                case l: Linked[T] @unchecked =>
                    loop(l.p)
                case v =>
                    try f(v.asInstanceOf[Result[T]])
                    catch
                        case ex if NonFatal(ex) =>
                            Logs.unsafe.error("uncaught exception", ex)
        loop(this)
    end onComplete

    protected def onComplete(): Unit = {}

    private def complete(p: Pending[T], v: Result[T]): Boolean =
        compareAndSet(p, v) && {
            onComplete()
            p.flush(v)
            true
        }

    private val nullCompletion = Result.success(null)

    final def complete(v: Result[T]): Boolean =
        val r =
            if !isNull(v) then v
            else nullCompletion.asInstanceOf[Result[T]]
        @tailrec def loop(): Boolean =
            get() match
                case p: Pending[T] @unchecked =>
                    complete(p, v) || loop()
                case _ =>
                    false
        loop()
    end complete

    final def block(deadline: Long): Result[T] =
        def loop(promise: IOPromise[T]): Result[T] =
            promise.get() match
                case _: Pending[T] @unchecked =>
                    Scheduler.get.flush()
                    object state extends (Result[T] => Unit):
                        @volatile
                        private var result = null.asInstanceOf[Result[T]]
                        private val waiter = Thread.currentThread()
                        def apply(v: Result[T]) =
                            result = v
                            LockSupport.unpark(waiter)
                        @tailrec def apply(): Result[T] =
                            if isNull(result) then
                                val remainingNanos = deadline - System.currentTimeMillis()
                                if remainingNanos <= 0 then
                                    return Fibers.interruptedResult
                                else if remainingNanos == Long.MaxValue then
                                    LockSupport.park(this)
                                else
                                    LockSupport.parkNanos(this, remainingNanos)
                                end if
                                apply()
                            else
                                result
                        end apply
                    end state
                    onComplete(state)
                    state()
                case l: Linked[T] @unchecked =>
                    loop(l.p)
                case v =>
                    v.asInstanceOf[Result[T]]
        loop(this)
    end block
end IOPromise

private[kyo] object IOPromise:

    type State[T] = Result[T] | Pending[T] | Linked[T]

    case class Linked[T](p: IOPromise[T])

    abstract class Pending[T]:
        self =>

        def run(v: Result[T]): Pending[T]

        def add(f: Result[T] => Unit): Pending[T] =
            new Pending[T]:
                def run(v: Result[T]) =
                    try f(v)
                    catch
                        case ex if NonFatal(ex) =>
                            Logs.unsafe.error("uncaught exception", ex)
                    end try
                    self
                end run

        final def interrupt(p: IOPromise[?]): Pending[T] =
            new Pending[T]:
                def run(v: Result[T]) =
                    p.interrupt()
                    self
                end run

        final def merge(tail: Pending[T]): Pending[T] =
            @tailrec def loop(p: Pending[T], v: Result[T]): Pending[T] =
                p match
                    case _ if (p eq Pending.Empty) =>
                        tail
                    case p: Pending[T] =>
                        loop(p.run(v), v)
            new Pending[T]:
                def run(v: Result[T]) =
                    loop(self, v)
        end merge

        final def flush(v: Result[T]): Unit =
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
            def run(v: Result[Nothing]) = this
    end Pending
end IOPromise
