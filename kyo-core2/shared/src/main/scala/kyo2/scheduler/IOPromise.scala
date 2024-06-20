package kyo2.scheduler

import IOPromise.*
import java.lang.invoke.MethodHandles
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kyo.scheduler.Scheduler
import kyo2.*
import kyo2.kernel.Safepoint
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.util.control.NoStackTrace

private[kyo2] class IOPromise[E, A](init: State[E, A]) extends Safepoint.Interceptor:

    @volatile private var state: State[E, A] = init

    def this() = this(Pending())
    def this(interrupts: IOPromise[?, ?]) = this(Pending().interrupts(interrupts))

    def enter(frame: Frame, value: Any): Boolean = true
    def exit(): Unit                             = {}

    private def cas[E2 <: E, A2 <: A](curr: State[E2, A2], next: State[E2, A2]): Boolean =
        stateHandle.compareAndSet(this, curr, next)

    final def isDone(): Boolean =
        @tailrec def loop(promise: IOPromise[E, A]): Boolean =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    false
                case l: Linked[E, A] @unchecked =>
                    loop(l.p)
                case _ =>
                    true
        loop(this)
    end isDone

    final def interrupts(i: IOPromise[?, ?])(using frame: Frame): Unit =
        @tailrec def loop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    if !promise.cas(p, p.interrupts(i)) then
                        loop(promise)
                case l: Linked[E, A] @unchecked =>
                    loop(l.p)
                case v =>
                    try discard(i.interrupt(Result.Panic(Interrupt(frame))))
                    catch
                        case ex if NonFatal(ex) =>
                            Log.unsafe.error("uncaught exception", ex)
        loop(this)
    end interrupts

    final def interrupt(error: Result.Error[E])(using Frame): Boolean =
        @tailrec def loop(promise: IOPromise[E, A]): Boolean =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    promise.complete(p, error) || loop(promise)
                case l: Linked[E, A] @unchecked =>
                    loop(l.p)
                case _ =>
                    false
        loop(this)
    end interrupt

    final private def compress(): IOPromise[E, A] =
        @tailrec def loop(p: IOPromise[E, A]): IOPromise[E, A] =
            p.state match
                case l: Linked[E, A] @unchecked =>
                    loop(l.p)
                case _ =>
                    p
        loop(this)
    end compress

    final private def merge(p: Pending[E, A]): Unit =
        @tailrec def loop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p2: Pending[E, A] @unchecked =>
                    if !promise.cas(p2, p2.merge(p)) then
                        loop(promise)
                case l: Linked[E, A] @unchecked =>
                    loop(l.p)
                case v =>
                    p.flush(v.asInstanceOf[Result[E, A]])
        loop(this)
    end merge

    final def becomeUnit[E2 <: E, A2 <: A](other: IOPromise[E2, A2]): Unit =
        discard(become(other))

    final def become[E2 <: E, A2 <: A](other: IOPromise[E2, A2]): Boolean =
        @tailrec def loop(other: IOPromise[E2, A2]): Boolean =
            state match
                case p: Pending[E2, A2] @unchecked =>
                    if cas(p, Linked(other)) then
                        other.merge(p)
                        true
                    else
                        loop(other)
                case _ =>
                    false
        loop(other.compress())
    end become

    final def onComplete(f: Result[E, A] => Unit): Unit =
        @tailrec def loop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    if !promise.cas(p, p.add(f)) then
                        loop(promise)
                case l: Linked[E, A] @unchecked =>
                    loop(l.p)
                case v =>
                    try f(v.asInstanceOf[Result[E, A]])
                    catch
                        case ex if NonFatal(ex) =>
                            Log.unsafe.error("uncaught exception", ex)
        loop(this)
    end onComplete

    protected def onComplete(): Unit = {}

    final private def complete(p: Pending[E, A], v: Result[E, A]): Boolean =
        cas(p, v) && {
            onComplete()
            p.flush(v)
            true
        }

    final def completeUnit[E2 <: E, A2 <: A](v: Result[E2, A2]): Unit =
        discard(complete(v))

    final def complete[E2 <: E, A2 <: A](v: Result[E2, A2]): Boolean =
        val r =
            if !isNull(v) then v
            else Result.success(null)
        @tailrec def loop(): Boolean =
            state match
                case p: Pending[E, A] @unchecked =>
                    complete(p, v) || loop()
                case _ =>
                    false
        loop()
    end complete

    final def block(deadline: Long)(using frame: Frame): Result[E | Timeout, A] =
        def loop(promise: IOPromise[E, A]): Result[E | Timeout, A] =
            promise.state match
                case _: Pending[E, A] @unchecked =>
                    Scheduler.get.flush()
                    object state extends (Result[E, A] => Unit):
                        @volatile
                        private var result = null.asInstanceOf[Result[E, A]]
                        private val waiter = Thread.currentThread()
                        def apply(v: Result[E, A]) =
                            result = v
                            LockSupport.unpark(waiter)
                        @tailrec def apply(): Result[E | Timeout, A] =
                            if isNull(result) then
                                val remainingNanos = deadline - System.currentTimeMillis()
                                if remainingNanos <= 0 then
                                    return Result.fail(Timeout(frame))
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
                case l: Linked[E, A] @unchecked =>
                    loop(l.p)
                case v =>
                    v.asInstanceOf[Result[E | Timeout, A]]
        loop(this)
    end block
end IOPromise

private[kyo2] object IOPromise:

    private val stateHandle =
        val lookup = MethodHandles.privateLookupIn(classOf[IOPromise[?, ?]], MethodHandles.lookup())
        lookup.findVarHandle(classOf[IOPromise[?, ?]], "state", classOf[Object])

    case class Interrupt(origin: Frame) extends Exception with NoStackTrace

    type State[E, T] = Result[E, T] | Pending[E, T] | Linked[E, T]

    case class Linked[E, T](p: IOPromise[E, T])

    abstract class Pending[E, A]:
        self =>

        def run(v: Result[E, A])(using Frame): Pending[E, A]

        def add(f: Result[E, A] => Unit): Pending[E, A] =
            new Pending[E, A]:
                def run(v: Result[E, A])(using Frame) =
                    try f(v)
                    catch
                        case ex if NonFatal(ex) =>
                            Log.unsafe.error("uncaught exception", ex)
                    end try
                    self
                end run

        final def interrupts(p: IOPromise[?, ?]): Pending[E, A] =
            new Pending[E, A]:
                def run(v: Result[E, A])(using frame: Frame) =
                    p.interrupt(Result.Panic(Interrupt(frame)))
                    self
                end run

        final def merge(tail: Pending[E, A]): Pending[E, A] =
            @tailrec def loop(p: Pending[E, A], v: Result[E, A])(using Frame): Pending[E, A] =
                p match
                    case _ if (p eq Pending.Empty) =>
                        tail
                    case p: Pending[E, A] =>
                        loop(p.run(v), v)
            new Pending[E, A]:
                def run(v: Result[E, A])(using Frame) =
                    loop(self, v)
        end merge

        final def flush(v: Result[E, A])(using Frame): Unit =
            @tailrec def loop(p: Pending[E, A])(using Frame): Unit =
                p match
                    case _ if (p eq Pending.Empty) => ()
                    case p: Pending[E, A] =>
                        loop(p.run(v))
            loop(this)
        end flush
    end Pending

    object Pending:
        def apply[E, A](): Pending[E, A] = Empty.asInstanceOf[Pending[E, A]]
        case object Empty extends Pending[Nothing, Nothing]:
            def run(v: Result[Nothing, Nothing])(using Frame) = this
    end Pending
end IOPromise
