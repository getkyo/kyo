package kyo2.scheduler

import IOPromise.*
import java.lang.invoke.MethodHandles
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.Scheduler
import kyo2.*
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

private[kyo2] class IOPromise[A](init: State[A]) extends IOFiber[A]:

    @volatile private var state: State[A] = init

    def this() = this(Pending())
    def this(interrupts: IOPromise[?]) = this(Pending().interrupts(interrupts))

    private def cas(curr: State[A], next: State[A]): Boolean =
        stateHandle.compareAndSet(this, curr, next)

    final def isDone(): Boolean =
        @tailrec def loop(promise: IOPromise[A]): Boolean =
            promise.state match
                case p: Pending[A] @unchecked =>
                    false
                case l: Linked[A] @unchecked =>
                    loop(l.p)
                case _ =>
                    true
        loop(this)
    end isDone

    final def interrupts(i: IOPromise[?])(using Frame): Unit =
        @tailrec def loop(promise: IOPromise[A]): Unit =
            promise.state match
                case p: Pending[A] @unchecked =>
                    if !promise.cas(p, p.interrupts(i)) then
                        loop(promise)
                case l: Linked[A] @unchecked =>
                    loop(l.p)
                case v =>
                    try discard(i.interrupt())
                    catch
                        case ex if NonFatal(ex) =>
                            Log.unsafe.error("uncaught exception", ex)
        loop(this)
    end interrupts

    final def interrupt()(using frame: Frame): Boolean =
        @tailrec def loop(promise: IOPromise[A]): Boolean =
            promise.state match
                case p: Pending[A] @unchecked =>
                    promise.complete(p, Result.failure(Interrupted(frame))) || loop(promise)
                case l: Linked[A] @unchecked =>
                    loop(l.p)
                case _ =>
                    false
        loop(this)
    end interrupt

    final private def compress(): IOPromise[A] =
        @tailrec def loop(p: IOPromise[A]): IOPromise[A] =
            p.state match
                case l: Linked[A] @unchecked =>
                    loop(l.p)
                case _ =>
                    p
        loop(this)
    end compress

    final private def merge(p: Pending[A]): Unit =
        @tailrec def loop(promise: IOPromise[A]): Unit =
            promise.state match
                case p2: Pending[A] @unchecked =>
                    if !promise.cas(p2, p2.merge(p)) then
                        loop(promise)
                case l: Linked[A] @unchecked =>
                    loop(l.p)
                case v =>
                    p.flush(v.asInstanceOf[Result[A]])
        loop(this)
    end merge

    final def become(other: IOPromise[A]): Boolean =
        @tailrec def loop(other: IOPromise[A]): Boolean =
            state match
                case p: Pending[A] @unchecked =>
                    if cas(p, Linked(other)) then
                        other.merge(p)
                        true
                    else
                        loop(other)
                case _ =>
                    false
        loop(other.compress())
    end become

    final def onComplete(f: Result[A] => Unit): Unit =
        @tailrec def loop(promise: IOPromise[A]): Unit =
            promise.state match
                case p: Pending[A] @unchecked =>
                    if !promise.cas(p, p.add(f)) then
                        loop(promise)
                case l: Linked[A] @unchecked =>
                    loop(l.p)
                case v =>
                    try f(v.asInstanceOf[Result[A]])
                    catch
                        case ex if NonFatal(ex) =>
                            Log.unsafe.error("uncaught exception", ex)
        loop(this)
    end onComplete

    protected def onComplete(): Unit = {}

    final private def complete(p: Pending[A], v: Result[A]): Boolean =
        cas(p, v) && {
            onComplete()
            p.flush(v)
            true
        }

    final private val nullCompletion = Result.success(null)

    final def complete(v: Result[A]): Boolean =
        val r =
            if !isNull(v) then v
            else nullCompletion.asInstanceOf[Result[A]]
        @tailrec def loop(): Boolean =
            state match
                case p: Pending[A] @unchecked =>
                    complete(p, v) || loop()
                case _ =>
                    false
        loop()
    end complete

    final def block(deadline: Long)(using frame: Frame): Result[A] =
        def loop(promise: IOPromise[A]): Result[A] =
            promise.state match
                case _: Pending[A] @unchecked =>
                    Scheduler.get.flush()
                    object state extends (Result[A] => Unit):
                        @volatile
                        private var result = null.asInstanceOf[Result[A]]
                        private val waiter = Thread.currentThread()
                        def apply(v: Result[A]) =
                            result = v
                            LockSupport.unpark(waiter)
                        @tailrec def apply(): Result[A] =
                            if isNull(result) then
                                val remainingNanos = deadline - System.currentTimeMillis()
                                if remainingNanos <= 0 then
                                    return Result.failure(Interrupted(frame))
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
                case l: Linked[A] @unchecked =>
                    loop(l.p)
                case v =>
                    v.asInstanceOf[Result[A]]
        loop(this)
    end block
end IOPromise

private[kyo2] object IOPromise:

    private val stateHandle = MethodHandles.lookup().findVarHandle(classOf[IOPromise[?]], "state", classOf[Object])

    case class Interrupted(origin: Frame) extends Exception with NoStackTrace

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
                            Log.unsafe.error("uncaught exception", ex)
                    end try
                    self
                end run

        final def interrupts(p: IOPromise[?]): Pending[T] =
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
