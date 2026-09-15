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

    /** This fiber's computation wrapped in the boundary, nothing composed between the region and the body's
      * first operation. A member, not a function, so the spawn's captures live on the task.
      */
    protected def prepared: Unit < Any

    /** The remainder of this fiber. Filled by `start`, not here: `prepared` reads subclass fields assigned
      * after this constructor runs. Built once, not per slice, since the region carries what a handler accumulates.
      */
    private var curr: Unit < Any = cleared

    /** Who owns this task and whether it is still alive. Four states, never two at once:
      *
      *   - `Idle`: owned by nobody, between slices; `curr` holds what a resumption runs.
      *   - `Thread`: a worker is in a slice on that thread; a stop (preemption or interrupt) is delivered there, reaching the slice.
      *   - `IOPromise`: parked on that promise, not to be rescheduled; naming it lets the wakeup be registered and later unlinked.
      *   - `Done`: terminal, remainder released.
      *
      * `Idle` is the only state another thread may take this task out of; every other transition is the owner's. Only the two claims out
      * of `Idle` (`run`, `onInterrupted`) are contended, both through `casStatus`, so a redundant schedule is free: an interrupt need not
      * know whether a slice is in flight. `AnyRef` not `Status` because the platform handle must name the field's erased type.
      */
    @volatile private var status: AnyRef = Idle

    /** Claims this task for a thread that does not own it yet. An absent handle means a single-threaded
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

    /** The frame of the join a park stopped at, for the unlink the wakeup performs. `run` arms the wakeup but only the boundary holds the
      * join's frame, so the boundary leaves it here. A plain var, not volatile: written and read by the same thread within one slice.
      */
    private var joinFrame: Frame = Frame.internal

    /** The fiber boundary: one region answering everything the scheduler owns, in `IOTask` not `Fiber` since its
      * decisions are scheduling, not effect interpretation. `Async.Join` and `Abort` share one entry through a tag
      * that is the union of the two, and every abort reaches it whatever its type since each `Abort[E]` is an `Abort[Nothing]`.
      */
    protected def boundary[P](v: P < (Abort[E] & Async))(complete: P => Unit): Unit < Any =
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
                    // one clause for two families, discriminated by what the operation carries: an abort's input is
                    // its error, a join's the promise thunk
                    input match
                        case error: Result.Error[E] @unchecked =>
                            // Ending the region without an answer discards the rest. The result is never read: the done
                            // lane checks isPending, and this arm settled it.
                            completeDiscard(error)
                            Loop.done(())
                        case joinInput: Async.JoinInput[C] @unchecked =>
                            // invoking it registers the interrupt cascade on this task before the promise's state is
                            // read, so an interrupt landing between still reaches what is awaited
                            val promise = joinInput(this)
                            promise.poll() match
                                case null =>
                                    // placeholder for a not-ready poll; O[C] is erased to Any here
                                    Loop.continue(null.asInstanceOf[Any])
                                case Present(r) =>
                                    // already complete when the thunk ran, so drop the link it pre-registered
                                    removeInterrupt(promise)(using joinInput.frame)
                                    Loop.continue(r)
                                case Absent =>
                                    // Waiting: the operation is raised again bare with a stop requested, so the evaluator
                                    // dispatches it back here and parks the fiber with every region carried, letting a bracket
                                    // around the join keep its release and close at its own end where the fiber resumes. The
                                    // wakeup is armed by `run`, not here: the remainder does not exist until the eval unwinds,
                                    // and arming early would let a second worker restore the same park and re-enter a spent scope.
                                    parkOn(promise, joinInput.frame)
                                    discard(Safepoint.stop(Thread.currentThread(), this))
                                    // Under the join's own frame, carried by the input: a clause is never handed the frame of what it
                                    // answers, and the scheduler's own would lose where it stopped.
                                    Loop.continue(ArrowEffect.suspend[C](using joinInput.frame)(Tag[Async.Join], joinInput))
                            end match
                        case other =>
                            bug(s"fiber boundary received an operation it does not answer: $other")
            ,
            // Guarded because the abort arm settled the task itself; `Loop.done` bypasses this, fine since
            // `completeDiscard` already settled the promise.
            p => if isPending() then complete(p) else ()
            // No call site to name: what a parked fiber reports comes from the operation it stopped at.
        )(using Frame.internal).asInstanceOf[Unit < Any]
    end boundary

    /** Puts the prepared computation in place, once the spawn that built this task is fully constructed. */
    private def install(): Unit =
        curr = prepared

    /** Records that this slice has decided to park, and on what. A method, not two writes at the site: a field a
      * lambda touches is promoted and renamed, while the platform handle finds `status` by name.
      */
    private def parkOn(promise: IOPromise[?, ?], frame: Frame): Unit =
        status = promise
        joinFrame = frame
    end parkOn

    private def stopSlice(): Unit =
        status match
            // Addressed to this task: the read and the sentinel landing are two steps and the slice can end between
            // them, so the addressee lets the slot refuse a late delivery; see `Safepoint.stop`.
            case thread: Thread => discard(Safepoint.stop(thread, this))
            case _              => ()
    end stopSlice

    final override def onComplete() =
        doPreempt()
        resetRuntime()
    end onComplete

    final override def doPreempt(): Unit =
        super.doPreempt()
        stopSlice()

    final override def onInterrupted(): Unit =
        stopSlice()
        Scheduler.get.notifyInterrupt()
        // A slice in flight observes the interrupt through the stop above. One not running has nothing to stop, may
        // never see this interrupt via its promise, and holds a park whose releases only a resumption runs, so make
        // it runnable and let `run` decide, keeping abandonment at the one owning site. Unconditional: a schedule
        // during a slice loses the claim and returns, and reading status here would be the same race.
        Scheduler.get.schedule(this)
    end onInterrupted

    final override def needsInterrupt(): Boolean =
        !isPending()

    /** Where this fiber currently stands, as one rendered frame, or empty where there is none. A cross-thread
      * diagnostic read: it touches only fields already in hand, never anything the evaluator would have run.
      */
    final override def fiberTrace(): String =
        try
            currentFrame(curr) match
                case Present(f) => render(f)
                case Absent     => ""
        catch case _: Throwable => ""

    /** The frame of the operation this fiber stands at, where it stands at one. A deferral's payload is a value,
      * not a body, so this runs none of the fiber's computation.
      */
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
        if !casStatus(Idle, Thread.currentThread()) then
            // Owned by somebody else, who finishes or releases it, so dropping this entry loses nothing.
            Task.Done
        else if !isPending() then
            // Completed between slices without an interrupt, so no claim was made and this one releases the remainder.
            abandon()
            Task.Done
        else
            val previous = IOTask.current.get()
            IOTask.current.set(this)
            // Records the slice for the stop channel: the slot honors a stop only while this record stands, so a
            // delivery racing the slice boundary cannot stop whatever runs next.
            val slot          = Safepoint.get()
            val previousSlice = Safepoint.beginSlice(slot, this)
            // The slice deadline. On js-wasm it is the preemption source; on jvm-native stops carry preemption and this inlines to nothing.
            Safepoint.deadline(deadline)
            val next =
                try
                    try Eval.partial(curr)
                    finally
                        IOTask.current.set(previous)
                        Safepoint.endSlice(slot, previousSlice)
                catch
                    case ex =>
                        // Completed here because the failure unwound past the boundary. Constructed rather than through
                        // `Result.panic`, which refuses to hold a fatal.
                        completeDiscard(new Result.Panic(ex))
                        curr = cleared
                        if IsFatal(ex) then
                            // A fatal skips the arms below that release ownership, and ownership never given up is never
                            // reclaimed. Nothing is left to release, so mark it terminal.
                            status = Done
                            throw ex
                        end if
                        cleared
            status match
                case promise: IOPromise[?, ?] =>
                    // `next` is the park, carrying the regions above it and the releases they owe, kept uncomposed so
                    // `abandon` can find it. Order matters (store the remainder, clear the status, then arm): arming
                    // publishes the task, so everything a resuming worker reads must already be written.
                    curr = next
                    // Read out before the wakeup closes over it; see `parkOn`.
                    val frame = joinFrame
                    status = Idle
                    promise.onComplete { _ =>
                        removeInterrupt(promise)(using frame)
                        Scheduler.get.schedule(this)
                    }
                    // An interrupt landing while this slice unwound left the task alone, the remainder not existing yet.
                    // It does now, and the wakeup may never come, so claim it here.
                    if !isPending() && casStatus(Idle, Done) then abandon()
                    Task.Done
                case _ =>
                    // Stored before ownership is released: once idle, another thread may claim this task.
                    if next.evalNow.isDefined then
                        // The boundary completed the fiber on the way here.
                        curr = cleared
                        status = Done
                        Task.Done
                    else
                        curr = next
                        if !isPending() then
                            // Interrupted or completed mid-slice, and nobody will resume the remainder.
                            abandon()
                            Task.Done
                        else
                            status = Idle
                            Task.Preempted
                        end if
                    end if
            end match
        end if
    end run

    /** Releases what an abandoned remainder still holds, and links what it was about to wait on. A parked computation carries its
      * owed releases rather than running them, so they run here. The link comes first: an interrupt arriving before the fiber reached
      * its join finds a remainder in front of one, the promise behind it not yet tied to this fiber. Only reached by an owning thread,
      * so the release happens once; `Done` keeps a later schedule from resuming what was just released.
      */
    private def abandon(): Unit =
        val remainder = curr
        curr = cleared
        status = Done
        if !isNull(remainder) then
            // Invoking the input registers the link, the same call the boundary makes.
            Eval.release(remainder, new KyoException("fiber abandoned")(using Frame.internal), Tag[Async.Join]) {
                [C] => input => discard(input(this))
            }
        end if
    end abandon

    // Drops the reference so a finished task does not retain the computation it ran. Never a signal: what a
    // slice produced is said by `evalNow` and `status`.
    private inline def cleared = null.asInstanceOf[Unit < Any]

    override def toString =
        s"IOTask(id = ${hashCode()}, state = ${stateString()}, preempt = ${{ shouldPreempt() }}, status = $status, curr = $curr)"

end IOTask

object IOTask:

    /** The two states of a task's status word that name no thread and no promise. Objects, not an enum over the
      * whole word: the two carrying states hold an already-allocated reference, so naming all four would allocate on every slice.
      */
    private[scheduler] case object Idle
    private[scheduler] case object Done

    /** Who owns a task, and whether it is still alive. See the field's documentation for the machine. */
    private[scheduler] type Status = Thread | IOPromise[?, ?] | Idle.type | Done.type

    /** Compare-and-set on a task's `status` field, without an atomic wrapper. Same shape and reason as
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

    /** The fiber running on this thread, or null where none is. The boundary is built before the fiber exists,
      * so it asks here rather than closing over one, and a spawning fiber reads it to link its children.
      */
    private[kyo] val current: ThreadLocal[IOTask[?, ?, ?]] = new ThreadLocal[IOTask[?, ?, ?]]

    private[kyo] def currentTask(): Maybe[IOTask[?, ?, ?]] = Maybe(current.get())

    /** When `parent` is present it is linked to interrupt the new task BEFORE it is scheduled, closing the window where a parent interrupted
      * while children launch orphans one started but not yet registered. The caller reads the parent once and passes it, so this does not
      * read the thread local per child. Detached creators pass `Absent`.
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
                    boundary(isolate.isolate(state, body))(t => completeDiscard(Result.succeed(isolate.restore(t))))
            ,
            parent,
            runtime
        )

    /** Spawns a fiber detached from its caller, crossing nothing: what it hands over carries no effects of
      * its own, so there is no state to capture and nothing to restore.
      */
    def detached[E, A](
        body: A < (Abort[E] & Async),
        parent: Maybe[IOPromise[?, ?]] = Absent,
        runtime: Int = 0
    ): IOTask[E, A, Any] =
        start(
            new IOTask[E, A, Any]:
                protected def prepared = boundary(body)(a => completeDiscard(Result.succeed(a)))
            ,
            parent,
            runtime
        )

    private def start[E, A, S2](task: IOTask[E, A, S2], parent: Maybe[IOPromise[?, ?]], runtime: Int): IOTask[E, A, S2] =
        // after the subclass is constructed, so `prepared` reads fields that are assigned
        task.install()
        task.addRuntime(runtime)
        parent.foreach(p => p.interrupts(task))
        Scheduler.get.schedule(task)
        task
    end start

end IOTask
