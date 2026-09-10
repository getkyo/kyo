package kyo.scheduler

import kyo.*
import kyo.kernel.ArrowEffect
import kyo.kernel.Effect
import kyo.kernel.Isolate
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Pending
import kyo.kernel.internal.Safepoint
import kyo.scheduler.IOTask.*
import scala.annotation.tailrec

sealed abstract private[kyo] class IOTask[E, A, S2] extends IOPromise[E, A < S2] with Task:

    /** This fiber's computation, already wrapped in the boundary below.
      *
      * A member rather than a function, so the spawn's captures live on the task instead of a closure beside
      * it. Each spawn passes the body itself, never a step composed onto it, so nothing stands between the
      * region and the body's first operation.
      */
    protected def prepared: Unit < Any

    /** The remainder of this fiber, prepared and wrapped in the boundary below.
      *
      * Filled by `start`, not here: `prepared` reads subclass fields assigned after this constructor runs.
      * Built once rather than per slice, since the region carries what a handler accumulates.
      */
    private var curr: Unit < Any = cleared

    /** Who owns this task, and whether it is still alive. Four states, never two at once:
      *
      *   - `Idle`: owned by nobody, between slices. `curr` holds what a resumption runs.
      *   - `Thread`: a worker is inside a slice, on that thread. Stops are delivered per thread, so this is
      *     how a preemption or interrupt reaches a slice in flight.
      *   - `IOPromise`: parked on that promise, not to be rescheduled; naming it lets the wakeup be
      *     registered and later unlinked.
      *   - `Done`: terminal, and what the remainder held has been released.
      *
      * Invariant: `Idle` is the only state another thread may take this task out of; every other transition
      * is made by the owner. Only the two claims out of `Idle` (`run`, `onInterrupted`) are contended, and
      * both go through `casStatus`. This makes a redundant schedule free, so an interrupt need not know
      * whether a slice is in flight.
      *
      * A union rather than an enum: both carrying cases hold an already-allocated reference, and enum cases
      * would add an allocation per slice. `AnyRef` rather than `Status` because the platform handle must
      * name the field's erased type.
      */
    @volatile private var status: AnyRef = Idle

    /** Takes this task out of `curr`, for a thread that does not own it yet.
      *
      * Absent handle means a single-threaded runtime, where the read-modify-write is already atomic with
      * respect to everything that can observe it.
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

    /** The frame of the join a park stopped at, for the unlink the wakeup performs.
      *
      * The wakeup is armed by `run` rather than by the boundary (see the park arm below), and the frame the
      * unlink is written under belongs to the join, which only the boundary is holding. So the boundary
      * leaves it here. Written and read by the same thread within one slice, which is why it is a plain var
      * next to a volatile one: nothing outside that slice looks at it.
      */
    private var joinFrame: Frame = Frame.internal

    /** The fiber boundary: one region answering everything the scheduler is responsible for.
      *
      * `Async.Join` and `Abort` share one entry through a tag that is the union of the two, and every abort
      * reaches it whatever its error type, since each `Abort[E]` is an `Abort[Nothing]`.
      *
      * Here rather than in `Fiber` because none of its decisions are effect interpretation: an abort
      * completes this promise, a ready join resumes in place, a pending one parks this task.
      */
    protected def boundary[P](v: P < (Abort[E] & Async))(complete: P => Unit): Unit < Any =
        // Typed at Unit: a fiber answers with its promise, so every exit completes this task or hands the
        // continuation to something that will.
        //
        // Erasure-forced: constructors are `Any` and the union tag is cast onto the region. A region is
        // contravariant in its input constructor, so one standing under two families with unrelated inputs
        // could only be `Nothing`, which type checks and then leaves the clause holding an uninhabited type.
        // The cast is confined to the tag, unchanged, so the region answers `Async.Join` and `Abort` and
        // nothing else; the match below recovers which one arrived.
        //
        // `Abort[E] & Async` rides in the region's `S` and is dropped from the row after: a row is
        // contravariant, while `Abort[E]` is an `Abort[Nothing]` and `Async` is opaque outside its package.
        ArrowEffect.handleCont[[X] =>> Any, [X] =>> Any, ArrowEffect[[X] =>> Any, [X] =>> Any], P, Unit, Abort[E] & Async, Any](
            Tag[Async.Join & Abort[Any]].asInstanceOf[Tag[ArrowEffect[[X] =>> Any, [X] =>> Any]]],
            v
        )(
            [C] =>
                (input, cont) =>
                    // one clause for two families, discriminated by what the operation carries: an abort's
                    // input is the error it is failing with, a join's is the thunk that hands over the
                    // promise once this task is linked to it
                    input match
                        case error: Result.Error[E] @unchecked =>
                            // Answering without applying the continuation discards the rest of the
                            // computation, so no stop is needed. The answer itself is never read: the done
                            // lane below checks whether this task is still pending, and this arm settled it.
                            completeDiscard(error)
                            null.asInstanceOf[P]
                        case joinInput: Async.JoinInput[C] @unchecked =>
                            // invoking it registers the interrupt cascade on this task before the promise's
                            // state is read, so an interrupt landing in between still reaches what is awaited
                            val promise = joinInput(this)
                            promise.poll() match
                                case null =>
                                    cont(null)
                                case Present(r) =>
                                    // already complete when the thunk ran, so drop the link it pre-registered
                                    // rather than letting it accumulate
                                    removeInterrupt(promise)(using joinInput.frame)
                                    cont(r)
                                case Absent =>
                                    // Waiting. The operation is left unanswered and raised again behind a
                                    // deferral, with a stop requested, so the eval parks in front of it.
                                    // Answering without applying the continuation would tell the region
                                    // the computation is over, draining finalizers a resumption still
                                    // needs. Parking is the only exit that carries owed releases out with
                                    // the remainder.
                                    //
                                    // The wakeup is armed by `run`, not here: the remainder does not exist
                                    // until the eval finishes unwinding, and arming early would let a
                                    // second worker restore the same park and re-enter a spent scope.
                                    parkOn(promise, joinInput.frame)
                                    discard(Safepoint.stop(Thread.currentThread(), this))
                                    // under the frame the join was written at, which the input carries
                                    // for this. A clause is never handed the frame of what it answers, so
                                    // without it the raise would take the scheduler's own and the fiber
                                    // would lose where it stopped
                                    ArrowEffect.suspendWith[C](using joinInput.frame)(Tag[Async.Join], joinInput)(r => cont(r))
                            end match
                        case other =>
                            bug(s"fiber boundary received an operation it does not answer: $other")
            ,
            // the body reached its end, so this is where the fiber answers. Guarded because the abort arm
            // above settles the task itself and answers with a value that stands for nothing
            p => if isPending() then complete(p) else ()
            // the region is the scheduler's own, built the same way for every fiber, so there is no call
            // site to name. What a parked fiber reports comes from the operation it stopped at, not here
        )(using Frame.internal).asInstanceOf[Unit < Any]
    end boundary

    /** Puts the prepared computation in place, once the spawn that built this task is fully constructed. */
    private def install(): Unit =
        curr = prepared

    /** Records that this slice has decided to park, and on what.
      *
      * A method rather than two writes at the site, because the site is inside the boundary's clause: a field
      * a lambda touches is not the enclosing class's private field any more, it is promoted and renamed, and
      * the platform handle finds `status` by name. Keeping every access in a method of this class is what
      * keeps the field private and its name its own.
      */
    private def parkOn(promise: IOPromise[?, ?], frame: Frame): Unit =
        status = promise
        joinFrame = frame
    end parkOn

    private def stopSlice(): Unit =
        status match
            // addressed to this task: the read of `status` and the sentinel landing are two steps,
            // and the slice can end between them with the thread already running another task. The
            // addressee is what lets the slot refuse such a late delivery; see `Safepoint.stop`
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
        // A slice in flight observes the interrupt through the stop above. One that is not running has
        // nothing to stop, and the promise that would have resumed it may never see this interrupt, while
        // the park it holds carries releases only a resumption can run. So make it runnable and let `run`
        // decide, keeping abandonment at the single site that owns the task.
        //
        // Unconditional, because the claim is what makes it safe: a schedule landing during a slice loses
        // the claim and returns. Reading the status here to decide would be the same race in disguise.
        Scheduler.get.schedule(this)
    end onInterrupted

    final override def needsInterrupt(): Boolean =
        !isPending()

    /** Where this fiber currently stands, as one rendered frame, or empty where there is none.
      *
      * A diagnostic read from other threads while this one runs, so it touches only fields already in hand
      * and never anything the evaluator would have run. Internal frames are dropped by identity so the
      * kernel's own plumbing never surfaces.
      */
    final override def fiberTrace(): String =
        try
            currentFrame(curr) match
                case Present(f) => render(f)
                case Absent     => ""
        catch case _: Throwable => ""

    /** The frame of the operation this fiber stands at, where it stands at one.
      *
      * Walks the regions, the park and the deferrals in front of the operation. A deferral's payload is a
      * value rather than a body, so this runs none of the fiber's computation.
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
                        // the deferral's applying arrow names the site that built it (a `Sync.defer` body's
                        // own file:line); the chained continuation is next, and only then the payload
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
            // Somebody else owns this task: a slice is in flight, or it is over. Whoever owns it finishes or
            // releases it, so dropping this queue entry loses nothing. This is what makes a redundant
            // schedule free, which is what lets the interrupt path schedule without knowing the state.
            Task.Done
        else if !isPending() then
            // Completed between slices by something that did not interrupt it, so no claim was made on its
            // behalf and this one is what releases the remainder.
            abandon()
            Task.Done
        else
            val previous = IOTask.current.get()
            IOTask.current.set(this)
            // the slice this claim runs, recorded for the stop channel: a stall check or interrupt
            // addresses its stop to this task, and the slot honors it only while this record
            // stands, so a delivery that races the slice boundary cannot stop whatever runs next
            val slot          = Safepoint.get()
            val previousSlice = Safepoint.beginSlice(slot, this)
            // the scheduler's slice deadline: on js-wasm it is the preemption source, checked at
            // the budget drains until the slice boundary consumes it; on jvm-native stops carry
            // preemption and the call inlines to nothing. Arming stays the eval's own entry step
            Safepoint.deadline(deadline)
            val next =
                try
                    try Eval.partial(curr)
                    finally
                        IOTask.current.set(previous)
                        Safepoint.endSlice(slot, previousSlice)
                catch
                    case ex =>
                        // The promise is completed here rather than by the boundary, which the failure
                        // unwound past. Constructed rather than built through `Result.panic`, which refuses
                        // to hold a fatal: the fatal is re-propagated below, and the observer is owed the
                        // reason either way.
                        completeDiscard(new Result.Panic(ex))
                        curr = cleared
                        if IsFatal(ex) then
                            // a fatal leaves `run` without reaching the arms below, which are what release
                            // ownership. Ownership never given up is never reclaimed: the claim at the top
                            // would fail for good, and with it every later schedule and the interrupt
                            // path's own claim. Nothing is left to release, so the terminal state is the
                            // honest one to leave behind
                            status = Done
                            throw ex
                        end if
                        cleared
            status match
                case promise: IOPromise[?, ?] =>
                    // `next` is the park, carrying the regions above it and the releases they owe. Kept
                    // uncomposed so `abandon` can find it.
                    //
                    // Order matters: store the remainder, clear the status, then arm. Arming publishes the
                    // task, so everything a resuming worker reads must already be written.
                    curr = next
                    // read out before the wakeup closes over it, so the field stays this class's own: see
                    // `parkOn` for why a lambda reaching a field is what the platform handle cannot survive
                    val frame = joinFrame
                    status = Idle
                    promise.onComplete { _ =>
                        removeInterrupt(promise)(using frame)
                        Scheduler.get.schedule(this)
                    }
                    // An interrupt that landed while this slice was unwinding found the task waiting and left
                    // it alone, because the remainder it would have released did not exist yet. It does now,
                    // and the wakeup above may never come, so the claim is made here instead.
                    if !isPending() && casStatus(Idle, Done) then abandon()
                    Task.Done
                case _ =>
                    // The remainder is stored before ownership is released, for the reason the park arm
                    // above gives: once this task is idle another thread may claim it, and what it claims
                    // has to be this slice's remainder rather than the one it replaced.
                    if next.evalNow.isDefined then
                        // The computation reached its end. The boundary completed the fiber on the way here.
                        curr = cleared
                        status = Done
                        Task.Done
                    else
                        curr = next
                        if !isPending() then
                            // Interrupted or completed mid-slice: the remainder the eval stopped at is the
                            // accurate one, and nobody will resume it.
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

    /** Releases what an abandoned remainder still holds, and links what it was about to wait on.
      *
      * A parked computation carries its owed releases rather than running them. This fiber will not resume,
      * so they are run here.
      *
      * The link comes first: an interrupt arriving before the fiber reached its join finds a remainder
      * standing in front of one, and the promise behind it is not yet tied to this fiber.
      *
      * Only reached by a thread owning the task, which is what makes the release happen once. `Done` keeps a
      * later schedule from resuming what was just released.
      */
    private def abandon(): Unit =
        val remainder = curr
        curr = cleared
        status = Done
        if !isNull(remainder) then
            // The release reports the join this fiber never reached, so the promise it was about to wait on
            // is linked to this interrupt rather than left pending for whoever else holds it. Invoking the
            // input is what registers the link, and it is the same call the boundary makes.
            Eval.release(remainder, new KyoException("fiber abandoned")(using Frame.internal), Tag[Async.Join]) {
                [C] => input => discard(input(this))
            }
        end if
    end abandon

    // Drops the reference so a finished task does not retain the computation it ran. Never a signal: `curr`
    // is read only while the task is runnable, and what a slice produced is said by `evalNow` and `status`.
    private inline def cleared = null.asInstanceOf[Unit < Any]

    override def toString =
        s"IOTask(id = ${hashCode()}, state = ${stateString()}, preempt = ${{ shouldPreempt() }}, status = $status, curr = $curr)"

end IOTask

object IOTask:

    /** The two states of a task's status word that name no thread and no promise.
      *
      * Objects rather than an enum over the whole word: the two carrying states hold a reference that is
      * already allocated, so naming all four as cases would put an allocation on every slice.
      */
    private[scheduler] case object Idle
    private[scheduler] case object Done

    /** Who owns a task, and whether it is still alive. See the field's documentation for the machine. */
    private[scheduler] type Status = Thread | IOPromise[?, ?] | Idle.type | Done.type

    /** Compare-and-set on a task's `status` field, without an atomic wrapper around it.
      *
      * The same shape as `IOPromise.StateHandle` and for the same reason: a task is allocated per fiber, and
      * a field the platform updates in place costs nothing beside it, where a boxed atomic would be a second
      * object per fiber.
      */
    abstract class StatusHandle:
        def compareAndSet(task: IOTask[?, ?, ?], curr: Status, next: Status): Boolean

    private val _frame                = Frame.internal
    private inline given frame: Frame = _frame

    // Install the scheduler's Diagnostics dumper at kyo-core's first touch of the scheduler: this object initializes when the first
    // fiber task is created, so a leaf that later hangs has the scheduler's live worker state in its Diagnostics.dumpAll() instead of blank.
    SchedulerDiagnostics.init()

    /** The fiber running on this thread, or null where none is.
      *
      * The boundary needs the fiber whose slice it is running in: to register an interrupt cascade on it, to
      * tell it what it is waiting on, and to complete it. The boundary is built before the fiber exists, so
      * it asks here rather than closing over one. It is also what a spawning fiber reads to link its
      * children.
      */
    private[kyo] val current: ThreadLocal[IOTask[?, ?, ?]] = new ThreadLocal[IOTask[?, ?, ?]]

    private[kyo] def currentTask(): Maybe[IOTask[?, ?, ?]] = Maybe(current.get())

    /** When `parent` is present it is linked to interrupt the new task BEFORE the task is scheduled, which
      * closes the window where a parent interrupted while children are still launching orphans one that
      * started but was not yet registered. The caller reads the parent once and passes it, so this does not
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

    /** Spawns a fiber detached from its caller, crossing nothing.
      *
      * What the caller hands over carries no effects of its own, so there is no state to capture and nothing
      * to restore: the body is prepared as written and the promise answers with its value.
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
        // after the subclass is constructed, so `prepare` reads fields that are assigned
        task.install()
        task.addRuntime(runtime)
        parent.foreach(p => p.interrupts(task))
        Scheduler.get.schedule(task)
        task
    end start

end IOTask
