package kyo.scheduler

import IOPromise.*
import java.util.concurrent.locks.LockSupport
import kyo.*
import kyo.Result.Error
import kyo.kernel.internal.Safepoint
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.util.control.NonFatal

private[kyo] class IOPromise[+E, +A](init: State[E, A]) extends Safepoint.Interceptor:

    @volatile private var state = init

    def this() = this(Pending())
    def this(interrupts: IOPromise[?, ?]) = this(Pending().interrupts(interrupts))

    def addFinalizer(f: () => Unit): Unit        = {}
    def removeFinalizer(f: () => Unit): Unit     = {}
    def enter(frame: Frame, value: Any): Boolean = true

    private def compareAndSet[E2 >: E, A2 >: A](curr: State[E2, A2], next: State[E2, A2]): Boolean =
        IOPromisePlatformSpecific.stateHandle match
            case Absent =>
                ((isNull(state) && isNull(curr)) || state.equals(curr)) && {
                    state = next.asInstanceOf[State[E, A]]
                    true
                }
            case Present(handle) =>
                handle.compareAndSet(this, curr, next)

    final def done(): Boolean =
        @tailrec def doneLoop(promise: IOPromise[E, A]): Boolean =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    false
                case l: Linked[E, A] @unchecked =>
                    doneLoop(l.p)
                case _ =>
                    true
        doneLoop(this)
    end done

    final def poll(): Maybe[Result[E, A]] =
        this.state match
            case _: (Pending[?, ?] | Linked[?, ?]) => Absent
            case r                                 => Present(r.asInstanceOf[Result[E, A]])

    final protected def isPending(): Boolean =
        state.isInstanceOf[Pending[?, ?]]

    final def interrupts(other: IOPromise[?, ?])(using frame: Frame): Unit =
        @tailrec def interruptsLoop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    if !promise.compareAndSet(p, p.interrupts(other)) then
                        interruptsLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    interruptsLoop(l.p)
                case _ =>
                    discard(other.interrupt(Result.Panic(Interrupt())))
        interruptsLoop(this)
    end interrupts

    final def removeInterrupt(other: IOPromise[?, ?])(using frame: Frame): Unit =
        @tailrec def removeInterruptLoop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    if !promise.compareAndSet(p, p.removeInterrupt(other)) then
                        removeInterruptLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    removeInterruptLoop(l.p)
                case _ =>
        removeInterruptLoop(this)
    end removeInterrupt

    final def mask(): IOPromise[E, A] =
        val p = new IOPromise[E, A]:
            override def interrupt[E2 >: E](error: Error[E2]): Boolean = false
        onComplete(p.completeDiscard)
        p
    end mask

    def interruptDiscard[E2 >: E](error: Error[E2]): Unit =
        discard(interrupt(error))

    def interrupt[E2 >: E](error: Error[E2]): Boolean =
        @tailrec def interruptLoop(promise: IOPromise[E, A]): Boolean =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    promise.interrupt(p, error) || interruptLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    interruptLoop(l.p)
                case _ =>
                    false
        interruptLoop(this)
    end interrupt

    final private def compress(): IOPromise[E, A] =
        @tailrec def compressLoop(p: IOPromise[E, A]): IOPromise[E, A] =
            p.state match
                case l: Linked[E, A] @unchecked =>
                    compressLoop(l.p)
                case _ =>
                    p
        compressLoop(this)
    end compress

    final private def merge[E2 >: E, A2 >: A](p: Pending[E2, A2]): Unit =
        @tailrec def mergeLoop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p2: Pending[E, A] @unchecked =>
                    if !promise.compareAndSet(p2, p2.merge(p)) then
                        mergeLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    mergeLoop(l.p)
                case v =>
                    p.flush(v.asInstanceOf[Result[E, A]])
        mergeLoop(this)
    end merge

    final def becomeDiscard[E2 >: E, A2 >: A](other: IOPromise[E2, A2]): Unit =
        discard(become(other))

    final def become[E2 >: E, A2 >: A](other: IOPromise[E2, A2]): Boolean =
        @tailrec def becomeLoop(other: IOPromise[E2, A2]): Boolean =
            state match
                case p: Pending[E2, A2] @unchecked =>
                    if compareAndSet(p, Linked(other)) then
                        other.merge(p)
                        true
                    else
                        becomeLoop(other)
                case _ =>
                    false
        becomeLoop(other.compress())
    end become

    def onComplete(f: Result[E, A] => Any): Unit =
        @tailrec def onCompleteLoop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    if !promise.compareAndSet(p, p.onComplete(f)) then
                        onCompleteLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    onCompleteLoop(l.p)
                case v =>
                    IOPromise.eval(discard(f(v.asInstanceOf[Result[E, A]])))
        onCompleteLoop(this)
    end onComplete

    def onInterrupt(f: Error[E] => Any): Unit =
        @tailrec def onInterruptLoop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    if !promise.compareAndSet(p, p.onInterrupt(f)) then
                        onInterruptLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    onInterruptLoop(l.p)
                case _ =>
        onInterruptLoop(this)
    end onInterrupt

    protected def onComplete(): Unit = {}

    final private def interrupt[E2 >: E, A2 >: A](p: Pending[E2, A2], v: Error[E2]): Boolean =
        compareAndSet(p, v) && {
            onComplete()
            p.flushInterrupt(v)
            true
        }

    final private def complete[E2 >: E, A2 >: A](p: Pending[E2, A2], v: Result[E2, A2]): Boolean =
        compareAndSet(p, v) && {
            onComplete()
            p.flush(v)
            true
        }

    final def completeDiscard[E2 >: E, A2 >: A](v: Result[E2, A2]): Unit =
        discard(complete(v))

    final def complete[E2 >: E, A2 >: A](v: Result[E2, A2]): Boolean =
        @tailrec def completeLoop(): Boolean =
            state match
                case p: Pending[E, A] @unchecked =>
                    complete(p, v) || completeLoop()
                case _ =>
                    false
        completeLoop()
    end complete

    def waiters(): Int =
        @tailrec def waitersLoop(promise: IOPromise[?, ?]): Int =
            promise.state match
                case p: Pending[?, ?] =>
                    p.waiters
                case l: Linked[?, ?] =>
                    waitersLoop(l.p)
                case _ =>
                    0
        waitersLoop(this)
    end waiters

    final def block(deadline: Clock.Deadline.Unsafe)(using frame: Frame): Result[E | Timeout, A] =
        def blockLoop(promise: IOPromise[E, A]): Result[E | Timeout, A] =
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
                            import kyo.AllowUnsafe.embrace.danger
                            if isNull(result) then
                                if deadline.isOverdue() then
                                    return Result.fail(Timeout())
                                val timeLeft = deadline.timeLeft()
                                if !timeLeft.isFinite then
                                    LockSupport.park(this)
                                else
                                    LockSupport.parkNanos(this, timeLeft.toNanos)
                                end if
                                apply()
                            else
                                result
                            end if
                        end apply
                    end state
                    onComplete(state)
                    state()
                case l: Linked[E, A] @unchecked =>
                    blockLoop(l.p)
                case v =>
                    v.asInstanceOf[Result[E | Timeout, A]]
        blockLoop(this)
    end block

    protected def stateString(): String =
        state match
            case p: Pending[?, ?] => s"Pending(waiters = ${p.waiters})"
            case l: Linked[?, ?]  => s"Linked(promise = ${l.p})"
            case r                => s"Done(result = ${r.asInstanceOf[Result[Any, Any]].show})"

    override def toString =
        s"IOPromise(state = ${stateString()})"

end IOPromise

private[kyo] object IOPromise:

    abstract class StateHandle:
        def compareAndSet[E, A](promise: IOPromise[E, A], curr: State[E, A], next: State[E, A]): Boolean

    type State[+E, +A] = Result[E, A] | Pending[E, A] | Linked[E, A]

    case class Linked[+E, +A](p: IOPromise[E, A])

    abstract class Pending[+E, +A]:
        self =>

        def waiters: Int
        def interrupt[E2 >: E](v: Error[E2]): Pending[E, A]
        def removeInterrupt(other: IOPromise[?, ?]): Pending[E, A]
        def run[E2 >: E, A2 >: A](v: Result[E2, A2]): Pending[E2, A2]

        @nowarn("msg=anonymous")
        final def onComplete(f: Result[E, A] => Any): Pending[E, A] =
            new Pending[E, A]:
                def waiters: Int = self.waiters + 1
                def interrupt[E2 >: E](error: Error[E2]) =
                    eval(discard(f(error.asInstanceOf[Error[E]])))
                    self
                def removeInterrupt(other: IOPromise[?, ?]) =
                    self.removeInterrupt(other).onComplete(f)
                def run[E2 >: E, A2 >: A](v: Result[E2, A2]) =
                    eval(discard(f(v.asInstanceOf[Result[E, A]])))
                    self
                end run

        final def interrupts(p: IOPromise[?, ?]): Pending[E, A] =
            new Pending[E, A]:
                def interrupt[E2 >: E](error: Error[E2]) =
                    discard(p.interrupt(error.asInstanceOf[Error[E]]))
                    self
                def removeInterrupt(other: IOPromise[?, ?]) =
                    if p eq other then self
                    else self.removeInterrupt(other).interrupts(p)
                def waiters: Int = self.waiters + 1
                def run[E2 >: E, A2 >: A](v: Result[E2, A2]) =
                    self

        @nowarn("msg=anonymous")
        def onInterrupt(f: Error[E] => Any): Pending[E, A] =
            new Pending[E, A]:
                def interrupt[E2 >: E](error: Error[E2]) =
                    eval(discard(f(error.asInstanceOf[Error[E]])))
                    self
                def removeInterrupt(other: IOPromise[?, ?]) =
                    self.removeInterrupt(other).onInterrupt(f)
                def waiters: Int = self.waiters + 1
                def run[E2 >: E, A2 >: A](v: Result[E2, A2]) =
                    self

        final def merge[E2 >: E, A2 >: A](tail: Pending[E2, A2]): Pending[E2, A2] =

            @tailrec def runLoop[E3 >: E2, A3 >: A2](p: Pending[? <: E3, ? <: A3], v: Result[E3, A3]): Pending[E3, A3] =
                p match
                    case _ if (p eq Pending.Empty) => tail
                    case p: Pending[?, ?]          => runLoop(p.run(v), v)

            @tailrec def interruptLoop(p: Pending[E, A], error: Error[E2]): Pending[E2, A2] =
                p match
                    case _ if (p eq Pending.Empty) => tail
                    case p: Pending[E, A]          => interruptLoop(p.interrupt(error), error)

            @tailrec def removeInterruptsLoop(p: Pending[E, A], other: IOPromise[?, ?]): Pending[E2, A2] =
                p match
                    case _ if (p eq Pending.Empty) => tail
                    case p: Pending[E, A]          => removeInterruptsLoop(p.removeInterrupt(other), other)

            new Pending[E2, A2]:
                def waiters: Int                               = self.waiters + tail.waiters
                def interrupt[E2 >: E](error: Error[E2])       = interruptLoop(self, error.asInstanceOf[Error[E]])
                def removeInterrupt(other: IOPromise[?, ?])    = removeInterruptsLoop(self, other)
                def run[E3 >: E2, A3 >: A2](v: Result[E3, A3]) = runLoop(self, v)
            end new
        end merge

        final def flushInterrupt[E2 >: E, A2 >: A](error: Error[E2]): Unit =
            @tailrec def flushInterruptLoop(p: Pending[E, A]): Unit =
                p match
                    case _ if (p eq Pending.Empty) => ()
                    case p: Pending[E, A] =>
                        flushInterruptLoop(p.interrupt(error))
            flushInterruptLoop(this)
        end flushInterrupt

        final def flush[E2 >: E, A2 >: A](v: Result[E2, A2]): Unit =
            @tailrec def flushLoop[E3 >: E2, A3 >: A2](p: Pending[? <: E3, ? <: A3]): Unit =
                p match
                    case _ if (p eq Pending.Empty) => ()
                    case p: Pending[?, ?] =>
                        flushLoop(p.run(v))
            flushLoop(this)
        end flush

    end Pending

    object Pending:
        def apply[E, A](): Pending[E, A] = Empty.asInstanceOf[Pending[E, A]]
        case object Empty extends Pending[Nothing, Nothing]:
            def waiters: Int                            = 0
            def interrupt[E2 >: Nothing](v: Error[E2])  = this
            def removeInterrupt(other: IOPromise[?, ?]) = this
            def run[E2, A2](v: Result[E2, A2])          = this
        end Empty
    end Pending

    private inline def eval[A](inline f: => Unit): Unit =
        try f
        catch
            case ex if NonFatal(ex) =>
                given Frame = Frame.internal
                import AllowUnsafe.embrace.danger
                Log.live.unsafe.error("uncaught exception", ex)
        end try
    end eval
end IOPromise
