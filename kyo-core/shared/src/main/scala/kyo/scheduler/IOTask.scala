package kyo.scheduler

import kyo.*
import kyo.kernel.ArrowEffect
import kyo.kernel.Effect
import kyo.kernel.Isolate
import kyo.kernel.Loop
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Pending
import kyo.kernel.internal.Safepoint
import kyo.scheduler.IOTask.*
import scala.annotation.tailrec

sealed abstract private[kyo] class IOTask[E, A, S2] extends IOPromise[E, A < S2] with Task:

    /** A member, not a function, so the spawn's captures live on the task.
      */
    protected def prepared: Unit < Any

    /** Filled by `start`, not here: `prepared` reads subclass fields assigned
      * after this constructor runs. Built once, not per slice, since the region carries what a handler accumulates.
      */
    private var curr: Unit < Any = cleared

    /** Who owns this task and whether it is still alive. Five states, never two at once:
      *
      *   - `Idle`: owned by nobody, between slices; `curr` holds what a resumption runs.
      *   - `Thread`: a worker is in a slice on that thread; a stop (preemption or interrupt) is delivered there, reaching the slice.
      *   - `IOPromise`: parked on that promise, not to be rescheduled; naming it lets the wakeup be registered and later unlinked.
      *   - `Result.Error`: interrupted with that error, the remainder not yet released; the promise stays pending until it is, so a fiber's
      *     result is available once its finalizers have run.
      *   - `Done`: terminal, remainder released.
      *
      * A task in `Idle` or holding the error is unowned, and either word may be claimed by any thread. An interrupt is the only transition
      * another thread makes out of `Thread` or `IOPromise`; every other transition is the owner's, and each one out of a slice is a CAS
      * since an interrupt may have taken the word meanwhile. So a run is never scheduled for a slice in flight, and the two contended
      * claims are out of `Idle` and out of the error.
      */
    @volatile private var status: Status = Status.Idle

    private def interrupted: Boolean = status.isInterrupted

    /** It completes only while the ending is still the body's to settle: not after the abort arm settled it and
      * answered with a placeholder. By name, so a value is not restored for an ending nobody settles.
      */
    private def finish(result: => Result[E, A < S2]): Unit =
        if isPending() then completeDiscard(result)

    /** An absent handle means a single-threaded
      * runtime, where the read-modify-write is already atomic.
      */
    private def casStatus(curr: Status, next: Status): Boolean =
        IOTaskPlatformSpecific.statusHandle match
            case Absent =>
                (status eq curr) && {
                    status = next
                    true
                }
            case Present(handle) =>
                handle.compareAndSet(this, curr, next)
    end casStatus

    /** `run` arms the wakeup but only the boundary holds the
      * one the join's link carries, so the boundary leaves it here. A plain var, not volatile: written and read by the same thread within one slice.
      */
    private var parkWakeup: Result[Any, Any] => Any = null

    /** The fiber boundary: one region answering everything the scheduler owns, in `IOTask` not `Fiber` since its
      * decisions are scheduling, not effect interpretation.
      */
    protected def boundary[P](v: P < (Abort[E] & Async))(restore: P => A < S2): Unit < Any =
        // Typed at Unit: a fiber answers with its promise, so every exit completes this task or hands the continuation on.
        //
        // Erasure-forced: constructors are `Any` and the union tag is cast onto the region. A region is
        // contravariant in its input constructor, so one standing under two families with unrelated inputs
        // could only be `Nothing`, leaving the clause holding an uninhabited type. The cast is confined to
        // the tag, so the region answers those two families and nothing else; the match below recovers which
        // one arrived.
        //
        // `Abort[E] & Async` rides in the region's `S` and is dropped from the row after: a row is contravariant,
        // while `Abort[E]` is an `Abort[Nothing]` and `Async` is opaque outside its package.
        // A loop, not a cont: a `handleCont` would dump the regions above before the clause ran, moving a bracket's
        // release onto the boundary to fire only at the fiber's end.
        ArrowEffect.handleLoop[[X] =>> Any, [X] =>> Any, ArrowEffect[[X] =>> Any, [X] =>> Any], P, Unit, Abort[E] & Async, Any](
            Tag[Async.Join & Abort[Any]].asInstanceOf[Tag[ArrowEffect[[X] =>> Any, [X] =>> Any]]],
            v
        )(
            [C] =>
                input =>
                    input match
                        case error: Result.Error[E] @unchecked =>
                            // Ending the region discards the rest and releases the regions above it, so no stop is
                            // needed.
                            finish(error)
                            Loop.done(())
                        case joinInput: Async.JoinInput[C] @unchecked =>
                            // The wakeup `run` registers if this join parks. Made here so the link carries it: an awaited
                            // promise that refuses the interrupt has it taken back and fired by the link, where it would
                            // otherwise stay registered for as long as the promise lives. It drops its own link by identity.
                            val wakeup: Result[Any, Any] => Any =
                                new (Result[Any, Any] => Any):
                                    self =>
                                    def apply(r: Result[Any, Any]): Any =
                                        discard(IOTask.this.remove(self))
                                        Scheduler.get.schedule(IOTask.this)
                                    end apply
                            // invoking it registers the interrupt cascade on this task before the promise's state is
                            // read, so an interrupt landing between still reaches what is awaited
                            val promise = joinInput(this, Present(wakeup))
                            promise.poll() match
                                case null =>
                                    // A promise completed with a `null` value polls as a bare `null`, since `Success` and
                                    // `Present` are both unwrapped; O[C] is erased to Any here
                                    Loop.continue(null.asInstanceOf[Any])
                                case Present(r) =>
                                    // already complete when the thunk ran, so drop the link it pre-registered
                                    discard(remove(promise))
                                    Loop.continue(r)
                                case Absent =>
                                    // Waiting: the operation is raised again bare with a stop requested, so the evaluator
                                    // dispatches it back here and parks the fiber with every region carried, letting a bracket
                                    // around the join keep its release and close at its own end where the fiber resumes. The
                                    // wakeup is armed by `run`, not here: the remainder does not exist until the eval unwinds,
                                    // and arming early would let a second worker restore the same park and re-enter a spent scope.
                                    parkOn(promise, wakeup)
                                    discard(Safepoint.stop(Thread.currentThread(), this))
                                    // Under the join's own frame, carried by the input: a clause is never handed the frame of what it
                                    // answers, and the scheduler's own would lose where it stopped.
                                    Loop.continue(ArrowEffect.suspend[C](using joinInput.frame)(Tag[Async.Join], joinInput))
                            end match
                        case other =>
                            bug(s"fiber boundary received an operation it does not answer: $other")
            ,
            p =>
                // An interrupt taken on this slice wins over a value the body produced on it. The completion and
                // `interrupt` both claim the status word by CAS, so exactly one wins: taking it here completes the
                // fiber with the value and a racing `interrupt` finds the word gone; losing it means an interrupt took
                // the word first, the value is dropped, and the slice end settles the promise with the interrupt. A
                // bare `!interrupted` check is not enough, the interrupt can land between the check and the completion.
                // Claiming `Done` is safe because the boundary is the fiber's last step.
                if casStatus(Status.running(Thread.currentThread()), Status.Done) then finish(Result.succeed(restore(p)))
        )(using Frame.internal).asInstanceOf[Unit < Any]
    end boundary

    private def install(): Unit =
        curr = prepared

    /** A method, not two writes at the site: a field a
      * lambda touches is promoted and renamed, while the platform handle finds `status` by name.
      */
    private def parkOn(promise: IOPromise[?, ?], wakeup: Result[Any, Any] => Any): Unit =
        // A CAS, not a store: an interrupt that landed on this slice holds the word, and the park is then released
        // at the slice's end rather than armed.
        discard(casStatus(Status.running(Thread.currentThread()), Status.parked(promise)))
        parkWakeup = wakeup
    end parkOn

    private def stopSlice(): Unit =
        // Addressed to this task: the read and the sentinel landing are two steps and the slice can end between them,
        // so the addressee lets the slot refuse a late delivery.
        status.runningThread.foreach(thread => discard(Safepoint.stop(thread, this)))
    end stopSlice

    final override def onComplete() =
        doPreempt()
        resetRuntime()
    end onComplete

    final override def doPreempt(): Unit =
        super.doPreempt()
        stopSlice()

    final override protected def interrupt(p: IOPromise.Pending[E, A < S2], error: Result.Error[E]): Boolean =
        @tailrec def loop(): Boolean =
            val s = status
            if s.isIdle then
                (casStatus(Status.Idle, Status.interrupted(error)) && {
                    taken()
                    Scheduler.get.schedule(this)
                    true
                }) || loop()
            else
                s.runningThread match
                    case Present(thread) =>
                        (casStatus(Status.running(thread), Status.interrupted(error)) && {
                            discard(Safepoint.stop(thread, this))
                            taken()
                            true
                        }) || loop()
                    case Absent =>
                        s.parkedPromise match
                            case Present(promise) =>
                                (casStatus(Status.parked(promise), Status.interrupted(error)) && {
                                    taken()
                                    true
                                }) || loop()
                            case Absent => false
            end if
        end loop
        loop()
    end interrupt

    // Runs promptly from here: the release is what frees the worker and the finalizers, and the runtime this task
    // accumulated would otherwise deprioritize it.
    private def taken(): Unit =
        Scheduler.get.notifyInterrupt()
        resetRuntime()

    final override def preInterrupt(): Boolean =
        val s = status
        !(s.isInterrupted || s.isDone)

    final override def needsInterrupt(): Boolean =
        interrupted || !isPending()

    /** A cross-thread
      * diagnostic read: it touches only fields already in hand, never anything the evaluator would have run.
      */
    final override def fiberTrace(): String =
        try
            currentFrame(curr) match
                case Present(f) => render(f)
                case Absent     => ""
        catch case _: Throwable => ""

    private def currentFrame(v: Unit < Any): Maybe[Frame] =
        @tailrec def loop(x: Any, fuel: Int): Maybe[Frame] =
            if fuel == 0 then Absent
            else
                x match
                    case s: Pending.Suspend[?, ?, ?, ?] =>
                        val f = s.frame
                        if f.eq(Frame.internal) then Absent else Present(f)
                    case h: Pending.Handle[?, ?, ?, ?] => loop(h.value, fuel - 1)
                    case p: Pending.Park[?, ?]         => loop(p.value, fuel - 1)
                    case d: Pending.Defer[?, ?, ?, ?]  =>
                        // the deferral's applying arrow names the site that built it (a `Sync.defer` body's file:line),
                        // the chained continuation is next, then the payload
                        val fa = d.contA.frame
                        if !fa.eq(Frame.internal) then Present(fa)
                        else
                            val fb = d.contB.frame
                            if !fb.eq(Frame.internal) then Present(fb)
                            else loop(d.value, fuel - 1)
                        end if
                    case _ => Absent
        loop(v, 16)
    end currentFrame

    private def render(f: Frame): String =
        val at = StackTraceElement(
            s"${f.snippetShort} @ ${f.className}",
            f.callerName,
            f.position.fileName,
            f.position.lineNumber
        )
        s"at $at"
    end render

    final def run(startMillis: Long, clock: InternalClock, deadline: Long): Task.Result =
        val thread = Thread.currentThread()
        if !casStatus(Status.Idle, Status.running(thread)) then
            // Owned by somebody else, who finishes or releases it, unless the word holds an interrupt taken while the
            // task was idle: this is the run that interrupt scheduled, and it releases.
            release()
            Task.Done
        else if !isPending() then
            // Completed between slices without an interrupt, so no claim was made on its behalf and this one releases the remainder.
            abandon(Absent)
            Task.Done
        else
            val previous = IOTask.current.get()
            IOTask.current.set(this)
            // Records the slice for the stop channel: the slot honors a stop only while this record stands, so a
            // delivery racing the slice boundary cannot stop whatever runs next.
            val slot          = Safepoint.get()
            val previousSlice = Safepoint.beginSlice(slot, this)
            // On js-wasm it is the preemption source; on jvm-native stops carry preemption and this inlines to nothing.
            Safepoint.deadline(deadline)
            val next =
                try
                    try Eval.partial(curr)
                    finally
                        IOTask.current.set(previous)
                        Safepoint.endSlice(slot, previousSlice)
                catch
                    case ex =>
                        // Finished here because the failure unwound past the boundary. A fatal completes the promise
                        // regardless: the release an interrupt would wait for never runs after one. Otherwise a throw is
                        // the body's own ending, except while an interrupt is taken on this slice, where the throw is
                        // the interrupt arriving (a blocked worker's promise throws once the monitor interrupts the
                        // thread), so the interrupt settles the promise instead. Constructed rather than through
                        // `Result.panic`, which refuses to hold a fatal.
                        if IsFatal(ex) then completeDiscard(new Result.Panic(ex))
                        else if !interrupted then finish(new Result.Panic(ex))
                        curr = cleared
                        if IsFatal(ex) then
                            // A fatal skips the arms below that release ownership, and ownership never given up is never
                            // reclaimed. Nothing is left to release, so mark it terminal.
                            status = Status.Done
                            throw ex
                        end if
                        cleared
            val s = status
            s.parkedPromise match
                case Present(promise) =>
                    // `next` is the park, carrying the regions above it and the releases they owe, kept uncomposed so
                    // `abandon` can find it. Order matters (store the remainder, then arm): arming publishes the task,
                    // so everything a resuming worker reads must already be written.
                    curr = next
                    // Read out before `Idle` publishes the task: a resumed slice can park again and overwrite it.
                    val wakeup = parkWakeup
                    if casStatus(Status.parked(promise), Status.Idle) then
                        // Erasure-forced: the wakeup ignores the value, and the promise's type parameters are erased.
                        promise.asInstanceOf[IOPromise[Any, Any]].onComplete(wakeup)
                        // Completed while this slice unwound, without an interrupt: no run was scheduled on its behalf,
                        // and the wakeup may never come, so claim it here.
                        if !isPending() && casStatus(Status.Idle, Status.Done) then abandon(Absent)
                        // A release that ran before the wakeup was registered found nothing on the promise to take back,
                        // so it is taken back here. A no-op once it has fired.
                        val after = status
                        if after.isInterrupted || after.isDone then discard(promise.remove(wakeup))
                    else release()
                    end if
                    Task.Done
                case Absent =>
                    if s.isInterrupted then
                        // An interrupt landed on this slice. What the stop left is the remainder, unless the body
                        // reached its own ending first, in which case its value was dropped by the boundary's failed
                        // claim on the word and its finalizers already ran, so there is nothing to release and the
                        // promise is still pending.
                        curr = if next.evalNow.isDefined then cleared else next
                        release()
                        Task.Done
                    else if next.evalNow.isDefined then
                        // The boundary completed the fiber on the way here.
                        curr = cleared
                        if !casStatus(Status.running(thread), Status.Done) then release()
                        Task.Done
                    else
                        curr = next
                        if !isPending() then
                            abandon(Absent)
                            Task.Done
                        else if casStatus(Status.running(thread), Status.Idle) then
                            Task.Preempted
                        else
                            release()
                            Task.Done
                        end if
                    end if
            end match
        end if
    end run

    /** The claim is what keeps two runs from releasing the same remainder.
      */
    private def release(): Unit =
        status.interruptError.foreach { error =>
            // Erasure-forced: the word is E-agnostic; the interrupt it holds is this task's own.
            if casStatus(Status.interrupted(error), Status.Done) then abandon(Present(error.asInstanceOf[Result.Error[E]]))
        }

    /** A parked computation carries its owed releases rather than running them, and this fiber will not resume, so
      * they are run here. The link comes first: an interrupt arriving as the fiber reached its join can find a
      * remainder standing at one whose promise is not yet tied to this fiber.
      * The completion comes last: the cascade to what this fiber linked, and every observer of its result, run only
      * once its finalizers have.
      *
      * Only reached by a thread owning the task, so the release happens once. `Done` keeps a later schedule from
      * resuming what was just released.
      */
    private def abandon(interruption: Maybe[Result.Error[E]]): Unit =
        val remainder = curr
        curr = cleared
        status = Status.Done
        if !isNull(remainder) then
            Eval.release(remainder, new KyoException("fiber abandoned")(using Frame.internal), Tag[Async.Join]) {
                // Invoking the input registers the link. It carries no wakeup: the one registered at the join already has
                // a link that carries it.
                [C] => input => discard(input(this, Absent))
            }
        end if
        interruption.foreach(error => discard(settleInterrupt(error)))
    end abandon

    // Drops the reference so a finished task does not retain the computation it ran. Never a signal: what a
    // slice produced is said by `evalNow` and `status`.
    private inline def cleared = null.asInstanceOf[Unit < Any]

    override def toString =
        s"IOTask(id = ${hashCode()}, state = ${stateString()}, preempt = ${{ shouldPreempt() }}, status = $status, curr = $curr)"

end IOTask

object IOTask:

    /** Objects, not an enum
      * over the whole word: the three carrying states hold an already-allocated reference, so naming all five would allocate on every slice and every interrupt.
      */
    private[scheduler] case object Idle
    private[scheduler] case object Done

    /** Opaque over the union so the states stay typed and exhaustive at every match inside this file while the
      * union never leaks to a call site, no state costs a wrapper, and the field's erased type stays `Object` for the platform handle.
      * Bounded by `AnyRef` so the word can be compared by reference identity for the CAS.
      */
    private[scheduler] opaque type Status <: AnyRef = Thread | IOPromise[?, ?] | Idle.type | Done.type | Result.Error[?]

    private[scheduler] object Status:
        val Idle: Status = IOTask.Idle
        val Done: Status = IOTask.Done

        def running(thread: Thread): Status = thread

        def parked(promise: IOPromise[?, ?]): Status = promise

        def interrupted(error: Result.Error[?]): Status = error

        extension (self: Status)
            def isIdle: Boolean = self match
                case _: Idle.type => true;
                case _            => false
            def isDone: Boolean = self match
                case _: Done.type => true;
                case _            => false
            def isInterrupted: Boolean = self match
                case _: Result.Error[?] => true;
                case _                  => false

            def interruptError: Maybe[Result.Error[?]] = self match
                case e: Result.Error[?] => Present(e);
                case _                  => Absent

            def parkedPromise: Maybe[IOPromise[?, ?]] = self match
                case p: IOPromise[?, ?] => Present(p);
                case _                  => Absent

            def runningThread: Maybe[Thread] = self match
                case t: Thread => Present(t);
                case _         => Absent
        end extension
    end Status

    /** Same shape and reason as
      * `IOPromise.StateHandle`: a boxed atomic would be a second object per fiber, where a field the platform updates in place costs
      * nothing.
      */
    abstract class StatusHandle:
        def compareAndSet(task: IOTask[?, ?, ?], curr: Status, next: Status): Boolean

    private val _frame                = Frame.internal
    private inline given frame: Frame = _frame

    // Install the scheduler's Diagnostics dumper at kyo-core's first touch of the scheduler: this object initializes when the first
    // fiber task is created, so a leaf that later hangs has the scheduler's live worker state in its Diagnostics.dumpAll() instead of blank.
    SchedulerDiagnostics.init()

    private[kyo] val current: ThreadLocal[IOTask[?, ?, ?]] = new ThreadLocal[IOTask[?, ?, ?]]

    private[kyo] def currentTask(): Maybe[IOTask[?, ?, ?]] = Maybe(current.get())

    /** When `parent` is present it is linked to interrupt the new task BEFORE it is scheduled, closing the window where a parent interrupted
      * while children launch orphans one started but not yet registered. The caller reads the parent once and passes it, so this does not
      * read the thread local per child.
      */
    def apply[E, A, S, S2](isolate: Isolate[S, Abort[E] & Async, S2])(
        state: isolate.State,
        body: A < (Abort[E] & Async & S),
        parent: Maybe[IOPromise[?, ?]] = Absent,
        runtime: Int = 0
    ): IOTask[E, A, S2] =
        start(
            new IOTask[E, A, S2]:
                protected def prepared =
                    boundary(isolate.isolate(state, body))(t => isolate.restore(t))
            ,
            parent,
            runtime
        )

    def detached[E, A](
        body: A < (Abort[E] & Async),
        parent: Maybe[IOPromise[?, ?]] = Absent,
        runtime: Int = 0
    ): IOTask[E, A, Any] =
        start(
            new IOTask[E, A, Any]:
                protected def prepared = boundary(body)(a => a)
            ,
            parent,
            runtime
        )

    private def start[E, A, S2](task: IOTask[E, A, S2], parent: Maybe[IOPromise[?, ?]], runtime: Int): IOTask[E, A, S2] =
        task.install()
        task.addRuntime(runtime)
        parent.foreach(p => p.interrupts(task))
        Scheduler.get.schedule(task)
        task
    end start

end IOTask
