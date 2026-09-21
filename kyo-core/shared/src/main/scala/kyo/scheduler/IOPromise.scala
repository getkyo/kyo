package kyo.scheduler

import IOPromise.*
import java.util.concurrent.locks.LockSupport
import kyo.*
import kyo.Result.Error
import kyo.kernel.internal.Safepoint
import scala.annotation.tailrec

sealed private[kyo] trait IOPromiseBase[+E, +A]:
    self: IOPromise[E, A] =>

private[kyo] class IOPromise[E, A](init: State[E, A]) extends Safepoint.Interceptor with Serializable with IOPromiseBase[E, A]:

    @volatile private var state = init

    def this() = this(Pending())
    def this(interrupts: IOPromise[?, ?]) = this(Pending().interrupts(interrupts))

    def addFinalizer(f: Maybe[Error[Any]] => Unit): Unit    = {}
    def removeFinalizer(f: Maybe[Error[Any]] => Unit): Unit = {}
    def enter(frame: Frame, value: Any): Boolean            = true

    private def compareAndSet(curr: State[E, A], next: State[E, A]): Boolean =
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
        @tailrec def pollLoop(promise: IOPromise[E, A]): Maybe[Result[E, A]] =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    Absent
                case l: Linked[E, A] @unchecked =>
                    pollLoop(l.p)
                case r =>
                    Present(r.asInstanceOf[Result[E, A]])
        pollLoop(this)
    end poll

    final protected def pollError(): Maybe[Error[Any]] =
        (state: @unchecked) match
            case e: Result.Error[Any] =>
                Present(e)
            case _ =>
                Absent

    final protected def isPending(): Boolean =
        state.isInstanceOf[Pending[?, ?]]

    final def interrupts(other: IOPromise[?, ?])(using frame: Frame): Unit =
        interrupts(other, Absent)

    /** Links `other` to be interrupted when this promise is, optionally taking over a registration made on `other`.
      *
      * `release`, when present, is a callback registered on `other` by whoever is awaiting it, and it is that
      * awaiter's only way back. Interrupting the awaiter cascades into `other`; where the cascade lands, completing
      * `other` fires the callback and nothing more is needed. Where it does not land, because `other` is masked or
      * already done, the link takes the callback off `other` and fires it with the interrupt itself, so the awaiter
      * is woken on the same path either way and holds no registration on a promise it has stopped waiting for.
      */
    final def interrupts[E2, A2](other: IOPromise[E2, A2], release: Maybe[Result[E2, A2] => Any])(using frame: Frame): Unit =
        @tailrec def interruptsLoop(promise: IOPromise[E, A]): Unit =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    if !promise.compareAndSet(p, p.interrupts(other, release)) then
                        interruptsLoop(promise)
                case l: Linked[E, A] @unchecked =>
                    interruptsLoop(l.p)
                case _ =>
                    discard(other.interrupt(Result.Panic(Interrupted(frame))))
        interruptsLoop(this)
    end interrupts

    /** Drops the registration made under `key`, matched by reference: the promise given to [[interrupts]], or the
      * function given to [[onComplete]] / [[onInterrupt]]. Returns whether it was there to drop.
      *
      * The result is what makes a handoff safe: the registration is removed under the same CAS that completion goes
      * through, so `true` means the caller now owns a callback that this promise will never fire, and `false` means
      * either the promise already fired it or nobody registered it. A caller that fires the callback itself does so
      * only on `true`, and it can never fire twice.
      *
      * The two arms of `key` do not reduce to one. A join link carries a callback belonging to the promise being
      * AWAITED rather than to the one holding the link, so no type parameterized by the holder covers both.
      *
      * A registration that is never dropped is what makes a waiter permanent. A promise that stays pending keeps every
      * registration reachable, and a completion callback holds the whole computation waiting on it, so a waiter that
      * goes away before the promise completes has to take its registration with it.
      */
    final def remove(key: IOPromise[?, ?] | Function1[?, ?]): Boolean =
        @tailrec def removeLoop(promise: IOPromise[E, A]): Boolean =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    if !p.holds(key) then false
                    else if !promise.compareAndSet(p, p.remove(key)) then removeLoop(promise)
                    else true
                case l: Linked[E, A] @unchecked =>
                    removeLoop(l.p)
                case _ =>
                    false
        removeLoop(this)
    end remove

    def preInterrupt(): Boolean = true

    /** Called exactly once when an interrupt completes this promise, after the state CAS, so observers it notifies already see the final
      * state. Never fires on value completion. No-op by default; IOTask overrides it to notify the scheduler.
      */
    protected def onInterrupted(): Unit = {}

    final def mask(): IOPromise[E, A] =
        val p = new IOPromise[E, A]:
            override def preInterrupt() = false
        onComplete(p.completeDiscard)
        p
    end mask

    inline def interruptDiscard(inline error: => Error[E]): Unit =
        discard(interrupt(error))

    inline def interrupt(inline error: => Error[E]): Boolean =
        @tailrec def interruptLoop(promise: IOPromise[E, A], _error: Maybe[Error[E]]): Boolean =
            promise.state match
                case p: Pending[E, A] @unchecked =>
                    val e = _error.getOrElse(error)
                    promise.interrupt(p, e) || interruptLoop(promise, Present(e))
                case l: Linked[E, A] @unchecked =>
                    interruptLoop(l.p, _error)
                case _ =>
                    false
        preInterrupt() && interruptLoop(this, Absent)
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

    final private def merge(p: Pending[E, A]): Unit =
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

    final def becomeDiscard(other: IOPromise[E, A]): Unit =
        discard(become(other))

    final def become(other: IOPromise[E, A]): Boolean =
        @tailrec def becomeLoop(other: IOPromise[E, A]): Boolean =
            state match
                case p: Pending[E, A] @unchecked =>
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

    /** Resets a completed promise back to Pending.Empty for reuse. Returns true if successful, false if not completed or CAS fails. Only
      * valid when no callbacks are registered (i.e., the promise was used as a single-owner notification, not with onComplete listeners).
      * Protected — only subclasses (e.g., ReadPump, WritePump) should call this.
      */
    protected def becomeAvailable(): Boolean =
        state match
            case _: Pending[?, ?] => false
            case _: Linked[?, ?]  => false
            case v                => compareAndSet(v, Pending())

    final private def interrupt(p: Pending[E, A], v: Error[E]): Boolean =
        compareAndSet(p, v) && {
            onComplete()
            onInterrupted()
            p.flushInterrupt(v)
            true
        }

    final private def complete(p: Pending[E, A], v: Result[E, A]): Boolean =
        compareAndSet(p, v) && {
            onComplete()
            p.flush(v)
            true
        }

    final def completeDiscard(v: Result[E, A]): Unit =
        discard(complete(v))

    final def complete(v: Result[E, A]): Boolean =
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
        @tailrec def blockLoop(promise: IOPromise[E, A]): Result[E | Timeout, A] =
            promise.state match
                case _: Pending[E, A] @unchecked =>
                    Scheduler.get.flush()
                    object state extends (Result[E, A] => Unit):
                        @volatile
                        private var result         = null.asInstanceOf[Result[E, A]]
                        private val waiter         = Thread.currentThread()
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
                                if Thread.interrupted() then
                                    promise.interruptDiscard(Result.Panic(Interrupted(
                                        frame,
                                        s"blocking thread ${Thread.currentThread().getName} being interrupted"
                                    )))
                                    Thread.currentThread().interrupt()
                                    throw new java.lang.InterruptedException()
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

    type State[E, A] = Result[E, A] | Pending[E, A] | Linked[E, A]

    final case class Linked[E, A](p: IOPromise[E, A])

    private val interruptPanic = Result.Panic(Interrupted(Frame.internal))

    sealed abstract class Pending[E, A]:
        self =>

        def interrupt(v: Error[E]): Pending[E, A]
        def run(v: Result[E, A]): Pending[E, A]

        /** The rest of the chain this node sits in front of, `Empty` at the end of it.
          *
          * Every traversal here is over a chain whose length is the number of waiters a promise has, which a fan-out
          * makes arbitrarily long. Exposing the link lets the traversals below be loops rather than one frame per
          * registration, so none of them is bounded by the platform's thread stack.
          */
        def next: Pending[E, A]

        /** Whether this node alone is the registration made under `key`, matched by identity: the promise for
          * `interrupts`, the function for `onComplete` and `onInterrupt`. A merged node answers for the half of the
          * chain that [[next]] does not reach.
          */
        def matches(key: IOPromise[?, ?] | Function1[?, ?]): Boolean

        /** Puts this node's registration back in front of `rest`, or returns `rest` alone to drop it.
          *
          * Dropped when the node [[matches]] `key`, and when the node is a link that can no longer fire, which is the
          * only chance a long-lived promise gets to collect those.
          */
        def rebuild(rest: Pending[E, A], key: IOPromise[?, ?] | Function1[?, ?]): Pending[E, A]

        /** What this node alone contributes to [[waiters]], excluding the chain behind it. */
        protected def selfWaiters: Int = 1

        final def waiters: Int =
            @tailrec def waitersLoop(p: Pending[E, A], acc: Int): Int =
                if p eq Pending.Empty then acc
                else waitersLoop(p.next, acc + p.selfWaiters)
            waitersLoop(this, 0)
        end waiters

        final def holds(key: IOPromise[?, ?] | Function1[?, ?]): Boolean =
            @tailrec def holdsLoop(p: Pending[E, A]): Boolean =
                if p eq Pending.Empty then false
                else p.matches(key) || holdsLoop(p.next)
            holdsLoop(this)
        end holds

        /** Rebuilds this chain without the registration made under `key`.
          *
          * Runs to the end rather than stopping at the first match: the requested key is typically the newest
          * registration and so sits at the head, while links whose target has since completed sit behind it and are
          * collected on the same walk. The walk is two loops over an array rather than a recursion, so a fan-out
          * that registers ten thousand waiters costs no stack.
          */
        final def remove(key: IOPromise[?, ?] | Function1[?, ?]): Pending[E, A] =
            if next eq Pending.Empty then rebuild(Pending[E, A](), key)
            else
                var n = 0
                var p = this
                while p ne Pending.Empty do
                    n += 1
                    p = p.next
                val nodes = new Array[AnyRef](n)
                var i     = 0
                p = this
                while p ne Pending.Empty do
                    nodes(i) = p
                    i += 1
                    p = p.next
                end while
                // Rebuilt from the end of the chain back to the head, so the original order is kept.
                var rest = Pending[E, A]()
                i = n - 1
                while i >= 0 do
                    rest = nodes(i).asInstanceOf[Pending[E, A]].rebuild(rest, key)
                    i -= 1
                rest
        end remove

        final def onComplete(f: Result[E, A] => Any): Pending[E, A] =
            new Pending[E, A]:
                def next: Pending[E, A]        = self
                def interrupt(error: Error[E]) =
                    eval(discard(f(error)))
                    self
                def matches(key: IOPromise[?, ?] | Function1[?, ?])                      = key eq f
                def rebuild(rest: Pending[E, A], key: IOPromise[?, ?] | Function1[?, ?]) =
                    if key eq f then rest
                    else rest.onComplete(f)
                def run(v: Result[E, A]) =
                    eval(discard(f(v.asInstanceOf[Result[E, A]])))
                    self
                end run

        final def interrupts(p: IOPromise[?, ?]): Pending[E, A] =
            interrupts(p, Absent)

        final def interrupts[E2, A2](p: IOPromise[E2, A2], release: Maybe[Result[E2, A2] => Any]): Pending[E, A] =
            new Pending[E, A]:
                def interrupt(error: Error[E]) =
                    val ex =
                        error match
                            case error: Result.Panic => error
                            case _                   => interruptPanic

                    // A cascade that lands completes `p`, and completing `p` is what fires `release` and wakes the
                    // awaiter. A cascade that does not land, because `p` is masked or already done, fires nothing,
                    // so the link fires `release` itself with the interrupt, on the same path completion would
                    // have used. `remove` is true only if the registration was still on `p`, which is what keeps
                    // this from firing a callback that completion got to first.
                    if !p.interrupt(ex) then
                        release.foreach(r => if p.remove(r) then eval(discard(r(ex))))
                    self
                end interrupt
                // Matches either identity the link was made under: the awaited promise, or the registration it
                // carries. The awaiting task reclaims by the latter, because the callback is what it holds.
                def matches(key: IOPromise[?, ?] | Function1[?, ?]) = (key eq p) || release.exists(_ eq key)
                // A completed promise can never be interrupted again, so a link to one can never do anything and is
                // dropped on sight.
                def rebuild(rest: Pending[E, A], key: IOPromise[?, ?] | Function1[?, ?]) =
                    if matches(key) || p.done() then rest
                    else rest.interrupts(p, release)
                def next: Pending[E, A]  = self
                def run(v: Result[E, A]) =
                    self

        def onInterrupt(f: Error[E] => Any): Pending[E, A] =
            new Pending[E, A]:
                def interrupt(error: Error[E]) =
                    eval(discard(f(error)))
                    self
                def matches(key: IOPromise[?, ?] | Function1[?, ?])                      = key eq f
                def rebuild(rest: Pending[E, A], key: IOPromise[?, ?] | Function1[?, ?]) =
                    if key eq f then rest
                    else rest.onInterrupt(f)
                def next: Pending[E, A]  = self
                def run(v: Result[E, A]) =
                    self

        final def merge(tail: Pending[E, A]): Pending[E, A] =

            @tailrec def runLoop(p: Pending[E, A], v: Result[E, A]): Pending[E, A] =
                p match
                    case _ if (p eq Pending.Empty) => tail
                    case p: Pending[E, A]          => runLoop(p.run(v), v)

            @tailrec def interruptLoop(p: Pending[E, A], error: Error[E]): Pending[E, A] =
                p match
                    case _ if (p eq Pending.Empty) => tail
                    case p: Pending[E, A]          => interruptLoop(p.interrupt(error), error)

            // A merged node fronts two chains: `self`, which `next` walks, and `tail`, which it answers for itself.
            // Nesting is bounded by how many promises became one another, never by how many waiters they have.
            new Pending[E, A]:
                def next: Pending[E, A]                                                  = self
                override protected def selfWaiters: Int                                  = tail.waiters
                def interrupt(error: Error[E])                                           = interruptLoop(self, error)
                def matches(key: IOPromise[?, ?] | Function1[?, ?])                      = tail.holds(key)
                def rebuild(rest: Pending[E, A], key: IOPromise[?, ?] | Function1[?, ?]) =
                    if tail.holds(key) then rest.merge(tail.remove(key))
                    else rest.merge(tail)
                def run(v: Result[E, A]) = runLoop(self, v)
            end new
        end merge

        final def flushInterrupt(error: Error[E]): Unit =
            @tailrec def flushInterruptLoop(p: Pending[E, A]): Unit =
                p match
                    case _ if (p eq Pending.Empty) => ()
                    case p: Pending[E, A]          =>
                        flushInterruptLoop(p.interrupt(error))
            flushInterruptLoop(this)
        end flushInterrupt

        final def flush(v: Result[E, A]): Unit =
            @tailrec def flushLoop(p: Pending[E, A]): Unit =
                p match
                    case _ if (p eq Pending.Empty) => ()
                    case p                         =>
                        flushLoop(p.run(v))
            flushLoop(this)
        end flush

    end Pending

    object Pending:
        def apply[E, A](): Pending[E, A] = Empty.asInstanceOf[Pending[E, A]]
        case object Empty extends Pending[Nothing, Nothing]:
            def next: Pending[Nothing, Nothing]                                                  = this
            override protected def selfWaiters: Int                                              = 0
            def interrupt(v: Error[Nothing])                                                     = this
            def matches(key: IOPromise[?, ?] | Function1[?, ?])                                  = false
            def rebuild(rest: Pending[Nothing, Nothing], key: IOPromise[?, ?] | Function1[?, ?]) = rest
            def run(v: Result[Nothing, Nothing])                                                 = this
        end Empty
    end Pending

    private inline def eval[A](inline f: => Unit): Unit =
        try f
        catch
            // Completion callbacks run in a single loop over all of a promise's waiters; a throwable escaping here
            // would abort that loop and leave the remaining waiters unnotified. A callback is an isolated
            // side-effecting notification, not the fiber's own computation, so contain and log every failure
            // (fatal included) rather than propagate.
            case ex =>
                given Frame = Frame.internal
                import AllowUnsafe.embrace.danger
                Log.live.unsafe.error("uncaught exception", ex)
        end try
    end eval
end IOPromise
