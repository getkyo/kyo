package kyo.scheduler

import kyo.*
import kyo.kernel.ArrowEffect
import kyo.kernel.Effect
import kyo.kernel.Isolate
import kyo.kernel.internal.Eval
import kyo.kernel.internal.Kyo
import kyo.kernel.internal.Safepoint
import kyo.scheduler.IOTask.*
import scala.annotation.tailrec
import scala.util.control.NonFatal

sealed abstract private[kyo] class IOTask[E, A, S2] extends IOPromise[E, A < S2] with Task:

    /** What this fiber runs, before the boundary is put around it.
      *
      * A method on the task rather than a function handed to it: whatever it builds has to complete this
      * task, so it needs `this` either way, and an abstract member lets the spawn's own captures live on the
      * task instead of costing a closure beside it.
      *
      * It is also where a crossing goes, when there is one. A spawn that carries effects isolates the body
      * and applies the restore to the result rather than composing it into the body, which keeps the
      * restored effects out of the row the scheduler has to answer and puts them in the value the promise
      * holds. A spawn that carries none prepares the body as written.
      */
    protected def prepare: Unit < (Abort[E] & Async)

    /** The remainder of this fiber, prepared and wrapped in the boundary below.
      *
      * Filled by `start` rather than here, because `prepare` reads the fields of whichever subclass a spawn
      * built and those are not assigned until after this constructor has run. Built once, not per slice: the
      * region carries what a handler accumulates, and re-establishing it every slice would lose it.
      */
    private var curr: Unit < Any = cleared

    /** What this task is doing. Three states, and it can never be two of them:
      *
      *   - `Present(thread)`: running, on that thread. Stops belong to the Safepoint and are delivered per
      *     thread through its slot, so this is what lets a preemption or an interrupt reach a slice already
      *     in flight.
      *   - `Present(promise)`: waiting, on that promise. The fiber stopped inside another one's
      *     continuation, so it has produced no result and must not be rescheduled; whoever it waits on will
      *     do that. Saying which promise is what lets the wakeup be registered and later unlinked.
      *   - `Absent`: neither, between slices.
      *
      * A union rather than an enum: the transitions run at the top of every slice and at every park, and the
      * two carrying cases hold a reference that is already allocated, so naming them as enum cases would add
      * an allocation per slice to the hottest path the scheduler has.
      *
      * Written by the running thread at the ends of a slice and by the boundary when it decides to wait, and
      * read by whoever is stopping or resuming it, so it is volatile.
      */
    @volatile private var status = Maybe.empty[Thread | IOPromise[?, ?]]

    /** The fiber boundary: one region answering everything the scheduler is responsible for.
      *
      * `Async.Join` and `Abort` are answered together through a tag that is the union of the two. A region's
      * tag says which operations it answers and an operation is answered where its own tag is subsumed by
      * it, so one entry covers both families, and every abort reaches it whatever its error type, because
      * `Abort` is contravariant and each `Abort[E]` is an `Abort[Nothing]`.
      *
      * It lives here rather than in `Fiber` because none of its decisions are effect interpretation. An
      * abort completes this promise, a ready join resumes in place, and a pending one parks this task: all
      * three are scheduling, and all three need state that is nobody else's business.
      */
    private def boundary(v: Unit < (Abort[E] & Async)): Unit < Any =
        // The region is typed at Unit because a fiber answers with its promise, not with a value: every way
        // out of here completes this task or hands the continuation to something that will, so there is
        // nothing for the region to carry and nothing after it to run.
        //
        // The constructors are `Any` and the union tag is cast onto the region that describes. Spelling the
        // bound honestly is what fails: a region is contravariant in its input constructor, so a
        // constructor standing under two families that carry unrelated inputs can only be the bottom type,
        // which type checks and then leaves the clause holding a `Nothing` that no arriving value inhabits.
        // The cast is confined to the tag, and the tag is the union unchanged. It is what selects
        // operations, so the region still answers `Async.Join` and `Abort` and nothing besides; what the
        // constructors give up is only the clause's ability to state which of the two it is looking at,
        // which the match below establishes anyway.
        //
        // `Abort[E] & Async` rides in the region's `S` and is dropped from the row afterwards. Both are
        // answered here, and neither can say so in `E`, because a row is contravariant while both of these
        // names are subtypes of what the tag above spells: `Abort[E]` is an `Abort[Nothing]`, and `Async` is
        // an opaque alias whose expansion `Async.Join & Sync` is only an upper bound outside its own
        // package. What the tag subsumes and what a row position accepts run in opposite directions, so the
        // names are carried whole through `S` and discharged by the cast, which is what states that the
        // region answered them. Nothing is left behind: every abort reaches the clause, `Async.Join` is the
        // tag itself, and `Sync` is a marker that nothing suspends on, `Sync.defer` being a deferral the
        // eval runs on its own.
        ArrowEffect.handleCont[[X] =>> Any, [X] =>> Any, ArrowEffect[[X] =>> Any, [X] =>> Any], Unit, Unit, Abort[E] & Async, Any](
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
                            // no stop is needed to end the slice: answering without applying the
                            // continuation is what discards the rest of the computation, so the region
                            // completes here and the eval has nothing left to carry on with
                            completeDiscard(error)
                        case joinInput: Async.JoinInput[C] @unchecked =>
                            // invoking it registers the interrupt cascade on this task before the promise's
                            // state is read, so an interrupt landing in between still reaches what is awaited
                            val promise = joinInput(this)
                            promise.poll() match
                                case null =>
                                    cont(null)
                                case Present(r) =>
                                    // already complete when the thunk ran, so drop the link it pre-registered
                                    // rather than letting it accumulate. Unlinking is bookkeeping between
                                    // two promises with no user call behind it, so the frame is internal
                                    removeInterrupt(promise)(using Frame.internal)
                                    cont(r)
                                case Absent =>
                                    // Waiting. The operation is deliberately left unanswered: it is raised
                                    // again behind a deferral, with a stop requested, so the eval parks in
                                    // front of it rather than running off the end.
                                    //
                                    // Answering without applying the continuation is how a region says the
                                    // computation is over, and a suspended fiber is not over. The eval
                                    // drains the finalizers on that exit, correctly, since as far as it can
                                    // see nothing is left; the continuation meanwhile lives in this
                                    // completion, and resuming it re-enters a bracket whose release already
                                    // ran. Parking is what distinguishes the two, and it is the only exit
                                    // that carries the outstanding releases out with the remainder instead
                                    // of running them.
                                    //
                                    // Nothing composes over what the eval hands back, so the park stays at
                                    // the head of `curr`, where `finalizeResources` can still find it if
                                    // this fiber is abandoned rather than resumed.
                                    //
                                    // The completion only reschedules. Resuming replays this clause, and
                                    // the poll above answers it in place the second time.
                                    //
                                    // `status` is what tells `run` the slice ended waiting rather than
                                    // finished. It is cleared by `run`, never here, so a promise that
                                    // completes inline still leaves `run` able to see that it parked.
                                    status = Present(promise)
                                    promise.onComplete { _ =>
                                        removeInterrupt(promise)(using Frame.internal)
                                        Scheduler.get.schedule(this)
                                    }
                                    discard(Safepoint.stop(Thread.currentThread()))
                                    // The frame is internal because a clause is never handed the frame of
                                    // the operation it answers, and this raise is the scheduler's own. It
                                    // is why a fiber parked on a promise reports no frame: what the eval
                                    // stops in front of is this node, and nothing on it came from user
                                    // code. Reporting where such a fiber stopped needs the frame carried
                                    // to the clause, which the region protocol does not do today.
                                    ArrowEffect.suspendWith[C](using Frame.internal)(Tag[Async.Join], joinInput)(r => cont(r))
                            end match
                        case other =>
                            bug(s"fiber boundary received an operation it does not answer: $other")
            ,
            a => a
            // the region is the scheduler's own, built the same way for every fiber, so there is no call
            // site to name. What a parked fiber reports comes from the operation it stopped at, not here
        )(using Frame.internal).asInstanceOf[Unit < Any]
    end boundary

    /** Puts the prepared computation in place, once the spawn that built this task is fully constructed. */
    private def install(): Unit =
        curr = boundary(prepare)

    private def stopSlice(): Unit =
        status match
            case Present(thread: Thread) => discard(Safepoint.stop(thread))
            case _                       => ()
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

    final override def needsInterrupt(): Boolean =
        !isPending()

    /** Where this fiber currently stands, as one rendered frame, or empty where there is none.
      *
      * A diagnostic, read from other threads while this one runs: the scheduler's status view asks every
      * busy worker for it, and the leak checker prints it beside the JVM stack. So it reads fields already
      * in hand and never anything the evaluator would have run. A deferral's payload and a recovery's body
      * are methods on purpose, so that reading them runs user code; neither is touched here.
      *
      * The frame is the operation's own. Deferrals carry none worth showing, which is what leaves a fiber
      * doing nothing but `Sync.defer` with an empty trace, and the internal frame is dropped by identity so
      * the kernel's own plumbing never surfaces.
      */
    final override def fiberTrace(): String =
        try
            currentFrame(curr) match
                case Present(f) => render(f)
                case Absent     => ""
        catch case _: Throwable => ""

    private def currentFrame(v: Unit < Any): Maybe[Frame] =
        v match
            case p: Kyo.Park[?, ?] =>
                // A park wraps what it stopped in front of with the deferral that takes its payload by
                // value, so the operation underneath is a reference the eval already held. Reading it runs
                // nothing, which the by-name deferral written by user code would not allow, and that one is
                // never what a park holds.
                p.value match
                    case d: Kyo.Defer[?, ?, ?, ?] => operationFrame(d.value)
                    case other                    => operationFrame(other)
            case other => operationFrame(other)

    // the frame of the operation itself. A deferral carries none worth reporting, which is what leaves a
    // fiber doing nothing but `Sync.defer` reading as empty, and the internal frame is dropped by identity
    private def operationFrame(v: Any): Maybe[Frame] =
        v match
            case s: Kyo.Suspend[?, ?, ?, ?, ?, ?] =>
                val f = s.frame
                if f eq Frame.internal then Absent else Present(f)
            case _ => Absent

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
        if !isPending() then
            abandon()
            Task.Done
        else
            status = Present(Thread.currentThread())
            val previous = IOTask.current.get()
            IOTask.current.set(this)
            val next =
                try
                    try Eval.partial(curr)
                    finally IOTask.current.set(previous)
                catch
                    case ex =>
                        // The promise is completed here rather than by the boundary, which the failure
                        // unwound past. Constructed rather than built through `Result.panic`, which refuses
                        // to hold a fatal: the fatal is re-propagated below, and the observer is owed the
                        // reason either way.
                        completeDiscard(new Result.Panic(ex))
                        curr = cleared
                        if !NonFatal(ex) then throw ex
                        cleared
            status match
                case Present(_: IOPromise[?, ?]) =>
                    // the boundary parked on another promise: `next` is the park the eval handed back,
                    // carrying the regions above it and the releases they still owe, and it is what the
                    // wakeup resumes. Kept as it was handed over, not composed with anything, so `abandon`
                    // can still see the park if this fiber is dropped instead. No result was produced and
                    // nothing reschedules from here: the promise's completion does that.
                    //
                    // The status is cleared here rather than in the completion, so a promise that was
                    // already complete and called back inline still leaves this able to see that it parked.
                    curr = next
                    status = Absent
                    Task.Done
                case _ =>
                    status = Absent
                    if next.evalNow.isDefined then
                        // The computation reached its end. The boundary completed the fiber on the way here.
                        curr = cleared
                        Task.Done
                    else
                        curr = next
                        if !isPending() then
                            // Interrupted or completed mid-slice: the remainder the eval stopped at is the
                            // accurate one, and nobody will resume it.
                            abandon()
                            Task.Done
                        else Task.Preempted
                        end if
                    end if
            end match
        end if
    end run

    /** Releases what an abandoned remainder still holds.
      *
      * A parked computation carries its outstanding releases rather than running them, because whoever holds
      * it may carry on. This fiber will not: its promise is complete and nothing will resume it.
      */
    private def abandon(): Unit =
        val remainder = curr
        curr = cleared
        status = Absent
        if !isNull(remainder) then remainder.finalizeResources
    end abandon

    // Drops the reference so a finished task does not retain the computation it ran. Never a signal: `curr`
    // is read only while the task is runnable, and what a slice produced is said by `evalNow` and `status`.
    private inline def cleared = null.asInstanceOf[Unit < Any]

    override def toString =
        s"IOTask(id = ${hashCode()}, state = ${stateString()}, preempt = ${{ shouldPreempt() }}, status = $status, curr = $curr)"

end IOTask

object IOTask:

    private val _frame                = Frame.internal
    private inline given frame: Frame = _frame

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
                protected def prepare =
                    isolate.isolate(state, body).map(t => completeDiscard(Result.succeed(isolate.restore(t))))
            ,
            parent,
            runtime
        )

    /** Spawns a fiber that crosses nothing.
      *
      * What the caller hands over carries no effects of its own, so there is no state to capture and nothing
      * to restore: the body is prepared as written and the promise answers with its value.
      */
    def unscoped[E, A](
        body: A < (Abort[E] & Async),
        parent: Maybe[IOPromise[?, ?]] = Absent,
        runtime: Int = 0
    ): IOTask[E, A, Any] =
        start(
            new IOTask[E, A, Any]:
                protected def prepare = body.map(a => completeDiscard(Result.succeed(a)))
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
