package kyo.internal

import kyo.*
import kyo.kernel.Isolate

/** Input metadata extracted from the flow AST at registration time. */
private[kyo] case class InputMeta(name: String, tag: Tag[Any], schema: Schema[Any], frame: Frame)

/** Wraps the entire Flow.run computation to handle custom effects.
  *
  * The identity-typed `erased` method slots into any effect context. At runtime the implementation handles effects pending inside the
  * computation via type erasure.
  *
  * **The `Scope` in the answer is not decoration.** A runner is built from
  * `[V] => V < S => V < (Async & Scope & Abort[FlowException])`, so wrapping a computation PRODUCES a scope requirement, and a signature
  * that answered the caller's own row would erase it: the step's resources would then be acquired against whatever scope happened to be
  * open around the engine, and a file or a connection a step opened would stay open until the engine itself shut down. Saying it in the
  * type is what makes the caller close the scope at the end of the attempt that opened it.
  */
abstract private[kyo] class FlowRunner:
    def erased[V, S](v: V < S)(using Frame): V < (S & Scope)

private[kyo] object FlowRunner:
    def apply[S](f: [V] => V < S => V < (Async & Scope & Abort[FlowException])): FlowRunner =
        new FlowRunner:
            def erased[V, S2](v: V < S2)(using Frame): V < (S2 & Scope) =
                f(v.asInstanceOf[V < S]).asInstanceOf[V < (S2 & Scope)]
end FlowRunner

private[kyo] case class FlowDefinition(
    id: Flow.Id.Workflow,
    flow: Flow[?, ?, ?],
    runner: Maybe[FlowRunner],
    inputs: Seq[InputMeta],
    meta: FlowEngine.WorkflowInfo,
    schema: WorkflowSchema
)

/** Interprets one attempt at one claimed execution.
  *
  * **It holds two capabilities and they are not interchangeable.** Reads go to the `FlowStore`, which anybody may read; every write
  * goes through the [[kyo.FlowStore.Claimed]] the poll was handed, and the store judges each one against the generation that claim
  * carries. There is no client-side claim check left: an executor learns it has lost the row by having a write REFUSED, which is what
  * makes the guarantee the store's rather than the interpreter's good manners.
  *
  * @param retried
  *   how many times each node has already been retried, counted from the history this attempt read. A step's retry schedule resumes at
  *   its recorded position: without it an executor that crashed at attempt 3 of 5 begins again at attempt 1, and a step that fails
  *   deterministically burns its whole schedule once per resume without ever exhausting it.
  * @param reentering
  *   the verdict a previous attempt recorded when it entered its unwind, present exactly when this attempt is resuming one. The forward
  *   pass then runs in skip-only mode: completed nodes re-register their handlers, and the first node that would actually RUN raises the
  *   recorded verdict instead, because replaying forward into the step that failed is how an unwind silently turns into a completion.
  */
private[kyo] class StoreInterpreter(
    store: FlowStore,
    claimed: FlowStore.Claimed,
    executorId: Flow.Id.Executor,
    defn: FlowDefinition,
    retried: Map[String, Int] = Map.empty,
    reentering: Maybe[Flow.Cause] = Maybe.empty
)(using Frame) extends FlowInterpreter[Async & Abort[FlowException | FlowSuspension | FlowStoreException]]:

    // The store's own error channel is part of the interpreter's row: a store failure raised while an execution is running is a failure
    // of that execution, and the engine discharges it where it discharges the flow's own.
    type S = Async & Abort[FlowException | FlowSuspension | FlowStoreException]

    private val eid    = claimed.state.executionId
    private val flowId = claimed.state.flowId

    /** A write the store refused because the claim is gone stops this attempt, and that is the ONLY thing that stops it.
      *
      * The authority is the store's rather than this executor's, so a refusal is how an executor finds out the row is somebody else's.
      * `ClaimLost` is its own ending: the new owner needs no help from the executor that stopped owning the row, so nothing at all is
      * written, not a status, not an event, not a release.
      */
    private def fenced(outcome: FlowStore.WriteOutcome < S): Unit < S =
        outcome.map {
            case FlowStore.WriteOutcome.Applied   => ()
            case FlowStore.WriteOutcome.ClaimLost => Abort.fail[FlowSuspension](FlowSuspension.ClaimLost)
        }

    /** The same, for a write that moves the lifecycle.
      *
      * A status on the wrong side of the partition is a defect in the CALL rather than a state of the execution, so it panics here
      * instead of ending the attempt quietly: nothing in this interpreter hands `updateStatus` a terminal status, and if something ever
      * does, the loud answer is the useful one.
      */
    private def fencedStatus(outcome: FlowStore.StatusOutcome < S): Unit < S =
        outcome.map {
            case FlowStore.StatusOutcome.Applied   => ()
            case FlowStore.StatusOutcome.ClaimLost => Abort.fail[FlowSuspension](FlowSuspension.ClaimLost)
            case FlowStore.StatusOutcome.WrongSideOfTerminal =>
                Abort.panic(new IllegalStateException(
                    s"a mid-attempt lifecycle write for ${eid.value} carried a terminal status, which only finish may write"
                ))
        }

    /** The same, for progress, answering whether THIS attempt was the writer.
      *
      * An already-recorded answer is not a failure. It is the race exemption working: two live branches completing a shared output name
      * resolve to the first writer at the store, which is the same answer replay derives from the field's presence.
      */
    private def fencedProgress(outcome: FlowStore.ProgressOutcome < S): Boolean < S =
        outcome.map {
            case FlowStore.ProgressOutcome.Recorded        => true
            case FlowStore.ProgressOutcome.AlreadyRecorded => false
            case FlowStore.ProgressOutcome.ClaimLost       => Abort.fail[FlowSuspension](FlowSuspension.ClaimLost)
        }

    private def appendEvent(event: Instant => Flow.Event): Unit < S =
        Clock.nowWith(ts => fenced(claimed.appendEvent(event(ts))))

    private def withEvent[A](event: Instant => Flow.Event)(body: => A < S): A < S =
        appendEvent(event).andThen(body)

    /** What every node boundary asks before it does anything: is this attempt still supposed to be going forward.
      *
      * Two reasons it is not, and they are read here rather than anywhere else because a node boundary is the only place an execution
      * can be stopped cleanly. A CANCEL REQUEST is read fresh from the row, never from the claim's snapshot, which was taken before the
      * attempt began and cannot see a request that arrived mid-flight. A RESUMED UNWIND raises the verdict the interrupted attempt
      * recorded, so the forward pass re-registers the handlers of everything already done and then stops rather than re-running the step
      * that failed, whose second attempt might succeed and complete an execution whose compensations have already half run.
      *
      * The claim is deliberately not among them. It is not this executor's to check: every write below carries it and the store
      * decides.
      */
    private def guard: Unit < S =
        reentering match
            case Present(cause) => Abort.fail[FlowException](FlowResumedUnwindException(cause))
            case _ =>
                store.getExecution(eid).map {
                    case Present(s) if s.cancelRequested => Abort.fail[FlowException](FlowCancelledException(eid.value))
                    case _                               => ()
                }

    /** Runs a step's computation under the `timeout` and the `retry` schedule its [[Flow.Meta]] declared.
      *
      * The schedule wraps the COMPUTATION alone. Everything the interpreter writes about an attempt, the `StepTimedOut` and the
      * `StepRetried` events, rides the store's own channel in the continuation of that catch, so a store failure can never be read as
      * a failure of the step: it leaves on the channel the engine classifies as infrastructure instead of spending a budget the
      * caller allotted to their own work and writing events that say the step failed when the store did. Those writes are claimed like
      * every other, so an executor whose lease is taken as its step begins cannot write its whole retry schedule into an execution
      * somebody else now owns, re-running the body once per entry.
      *
      * **The position is read back from history rather than restarted.** Every retry appends a `StepRetried` carrying its attempt
      * number, so the position is durable; an entry point that always began at attempt 1 would leave it durable and never read, and a
      * step failing deterministically then burns its whole schedule once per resume without ever exhausting it, which turns a bounded
      * retry policy into an unbounded one exactly when the system is already unhealthy.
      *
      * What the schedule re-asks is an accident and nothing else. A [[FlowException]] is a verdict on the work and ends the step at
      * its first appearance, failed or thrown, because re-asking a decision re-fires the step's side effects to reach the same
      * answer. A [[FlowStoreException]] is a fact about the store rather than about the step. An interrupt is the one mechanism that
      * stops an executor whose lease lapsed, and a schedule that consumed it would sleep its backoff and re-run the body under a
      * claim the store may have handed to somebody else, with nothing left to fire a second time. The timeout is the exception that
      * proves the rule: it is typed, but it measures slowness rather than deciding anything, and pairing `timeout` with `retry` is
      * exactly what the two `Meta` fields are for.
      */
    private def withTimeoutAndRetry[V](name: String, computation: V < Sync, meta: Flow.Meta): V < S =
        val bounded: V < (Async & Abort[FlowStepTimeoutException]) =
            if meta.timeout == Duration.Infinity then computation
            else
                Abort.recover[Timeout](_ => Abort.fail(FlowStepTimeoutException(name, meta.timeout)))(
                    Async.timeout(meta.timeout)(computation)
                )

        def errorOf(result: Result[Throwable, V]): Throwable =
            result match
                case Result.Failure(e) => e
                case Result.Panic(e)   => e
                case _                 => new IllegalStateException(s"Step '$name' ended with neither a value nor an error")

        /** The node's failure, recorded under its own name before it is re-raised into the flow.
          *
          * This is the one place every unrecovered failure of this node passes through, whatever route it took: a node with no retry
          * schedule, a declared failure the schedule is not allowed to re-ask, and a schedule that ran out. Recording here rather
          * than at each of the three keeps the record and the raise from ever disagreeing.
          *
          * **Two errors are not the node's verdict and write nothing.** A [[FlowStoreException]] is a fact about the store, and the
          * execution is left exactly as claimable as it was for the next poll to try again; an [[Interrupted]] means this attempt was
          * told to stop, which is no statement about the work. Painting either as a failed node would report a failure to an operator
          * where the engine itself records none.
          */
        def recordFailure(error: Throwable): Unit < S =
            error match
                case _: FlowStoreException => ()
                case _: Interrupted        => ()
                case e: FlowException =>
                    appendEvent(ts => Flow.Event.StepFailed(flowId, eid, name, e.getMessage, Maybe(e.kind), ts))
                case e =>
                    appendEvent(ts => Flow.Event.StepFailed(flowId, eid, name, e.getMessage, Maybe.empty, ts))

        def raise(result: Result[Throwable, V]): V < S =
            result match
                case Result.Success(v) => v
                case _ =>
                    val error = errorOf(result)
                    recordFailure(error).andThen {
                        error match
                            case e: FlowException      => Abort.fail(e)
                            case e: FlowStoreException => Abort.fail(e)
                            case e                     => Abort.panic(e)
                    }

        def isAccident(error: Throwable): Boolean =
            error match
                case _: FlowStepTimeoutException => true
                case _: FlowException            => false
                case _: FlowStoreException       => false
                case _: Interrupted              => false
                case _                           => true

        // One attempt, with the timeout's own event appended in the continuation of the catch rather than inside it.
        val attemptOnce: Result[Throwable, V] < S =
            Abort.run[Throwable](bounded).map { result =>
                result match
                    case Result.Failure(_: FlowStepTimeoutException) =>
                        appendEvent(ts => Flow.Event.StepTimedOut(flowId, eid, name, meta.timeout, ts)).andThen(result)
                    case _ => result
            }

        // Where this node's schedule already stood when the last attempt on it ended. Walking `next` that many times is the same
        // derivation `deriveCompleted` makes from the same history, and a schedule that runs out on the way is one whose budget was
        // already spent: the first failure then raises instead of sleeping a delay nobody has left.
        def resume(schedule: Schedule, spent: Int): Maybe[Schedule] < S =
            if spent <= 0 then Maybe(schedule)
            else
                Clock.nowWith { now =>
                    @annotation.tailrec
                    def walk(sched: Schedule, remaining: Int): Maybe[Schedule] =
                        if remaining <= 0 then Maybe(sched)
                        else
                            sched.next(now) match
                                case Present((_, next)) => walk(next, remaining - 1)
                                case _                  => Maybe.empty
                    walk(schedule, spent)
                }

        meta.retry match
            case Absent => attemptOnce.map(raise)
            case Present(schedule) =>
                def attempt(sched: Maybe[Schedule], attemptNum: Int): V < S =
                    attemptOnce.map {
                        case Result.Success(v) => v
                        case result =>
                            val error = errorOf(result)
                            if !isAccident(error) then raise(result)
                            else
                                Clock.nowWith { now =>
                                    sched.flatMap(_.next(now)) match
                                        case Present((delay, nextSched)) =>
                                            appendEvent(_ =>
                                                Flow.Event.StepRetried(flowId, eid, name, error.getMessage, attemptNum, delay, now)
                                            )
                                                .andThen(Async.sleep(delay))
                                                .andThen(attempt(Maybe(nextSched), attemptNum + 1))
                                        case _ => raise(result)
                                }
                            end if
                    }
                val spent = retried.getOrElse(name, 0)
                resume(schedule, spent).map(attempt(_, spent + 1))
        end match
    end withTimeoutAndRetry

    /** A [[FlowException]] goes back out as a failure, which is what keeps a step's own [[FlowDomainException]] a typed failure the
      * engine records with its kind, rather than a panic carrying only a message. Anything else was never a declared failure of this
      * flow and stays a panic.
      */
    override def onUnwind(error: Throwable): Nothing < S =
        error match
            case flowError: FlowException => Abort.fail(flowError)
            // The store's own failures go back out on the store's own channel, which this row carries. Panicking them
            // instead would throw away the classification a store went to the trouble of making, the retryable marker
            // included, at the one place that still has somewhere to put it.
            case storeError: FlowStoreException => Abort.fail(storeError)
            case other                          => Abort.panic(other)

    def getField[V](name: String)(using Tag[V], Schema[V]): Maybe[V] < S =
        store.getField[V](eid, name)

    def onOutput[V](name: String, computation: V < Sync, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]): V < S =
        for
            _ <- guard
            _ <- appendEvent(ts => Flow.Event.StepStarted(flowId, eid, name, executorId, ts))
            v <- withTimeoutAndRetry(name, computation, meta)
            // The value, its completion event and the clearing of the rows under its path are ONE transition. Split, a store
            // that fails between them leaves the value durably present and the history saying the step never finished, and the
            // two readers then disagree forever: replay decides from the field and never runs the step again, while every
            // operator surface reads the history and reports it unfinished.
            _ <- fencedProgress(
                Clock.nowWith(ts =>
                    claimed.recordProgress[V](name, Maybe(v), Flow.Event.StepCompleted(flowId, eid, name, ts))
                )
            )
        yield v

    def onStep(name: String, computation: Unit < Sync, frame: Frame, meta: Flow.Meta): Unit < S =
        for
            _ <- guard
            _ <- appendEvent(ts => Flow.Event.StepStarted(flowId, eid, name, executorId, ts))
            _ <- withTimeoutAndRetry(name, computation, meta)
            _ <- fencedProgress(
                Clock.nowWith(ts => claimed.recordProgress(name, Flow.Event.StepCompleted(flowId, eid, name, ts)))
            )
        yield ()

    /** The verdict is recorded under the node it is about, then raised.
      *
      * This row carries `Abort[FlowException]`, so a declared failure goes back out as a failure and reaches the engine with its kind
      * intact. See [[FlowInterpreter.onFailure]].
      *
      * The two failures that arrive here, a loop that ran its schedule out and a fan-out whose collection changed size, are node-level
      * verdicts with a kind. Reaching the store as a terminal status and nothing else would leave no event naming the node, so the
      * progress view would have nothing to paint and every node would read pending while the execution was Failed. The record is
      * written first, for the reason [[withTimeoutAndRetry]]'s own record is: once the failure is raised it travels through the unwind
      * and the name is gone.
      */
    def onFailure(path: String, error: FlowException): Nothing < S =
        appendEvent(ts => Flow.Event.StepFailed(flowId, eid, path, error.getMessage, Maybe(error.kind), ts))
            .andThen(Abort.fail(error))

    /** The node boundary's own check and the node's `timeout` and `retry`, with nothing written. See [[FlowInterpreter.onIteration]]
      * for why an unscheduled loop's iteration gets the policy without the record.
      */
    def onIteration[V](name: String, computation: V < Sync, frame: Frame, meta: Flow.Meta): V < S =
        guard.andThen(withTimeoutAndRetry(name, computation, meta))

    /** Writes the branch's name under the dispatch's reserved key, with the event that says so, in one transition.
      *
      * The field is the record replay reads and the event is what an operator reads, and one write carries both: a crash between two
      * writes would leave the field without its event, and the dispatch is exactly the node where the two readers must not diverge.
      */
    def onChoice(name: String, branch: String, frame: Frame, meta: Flow.Meta): Unit < S =
        guard.andThen(
            fencedProgress(
                Clock.nowWith(ts =>
                    claimed.recordProgress[String](
                        FlowInterpreter.chosenKey(name),
                        Maybe(branch),
                        Flow.Event.BranchChosen(flowId, eid, name, branch, ts)
                    )
                )
            ).unit
        )

    /** Writes the value the mapper supplied under the child input's own path, with the event that says so, in one transition.
      *
      * Built exactly like [[onChoice]] and for the same reason: the field is what replay reads and the event is what an operator
      * reads, and a crash between two writes would leave one of them able to answer and the other not. The answer is the store's,
      * not this interpreter's: an already-recorded path means somebody else's write landed first, which is the race exemption
      * working rather than a failure, and the caller reads the recorded value back.
      */
    def onInputSupplied[V](name: String, value: V, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]): Boolean < S =
        guard.andThen(
            fencedProgress(
                Clock.nowWith(ts =>
                    claimed.recordProgress[V](name, Maybe(value), Flow.Event.InputSupplied(flowId, eid, name, ts))
                )
            )
        )

    def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]): V < S =
        store.getField[V](eid, name).map {
            case Present(v) => v
            case _ =>
                guard.andThen(
                    Clock.nowWith { now =>
                        fenced(
                            claimed.recordWait(
                                name,
                                Flow.Wake.OnField(name),
                                Flow.Event.InputWaiting(flowId, eid, name, now)
                            )
                        ).andThen(Abort.fail[FlowSuspension](FlowSuspension.Parked(Set(name))))
                    }
                )
        }

    /** The wait row an input wrote, cleared by the node that consumed the value.
      *
      * A satisfied input takes its value from the record and writes nothing else, so without this the row it wrote while parking would
      * stay outstanding forever and readiness would hand the execution back on every poll. The row's own absence is what makes this
      * idempotent: a replay past an input that was never waiting on finds nothing to clear and writes nothing.
      *
      * The progress is VALUELESS, and that is the one write the write-once rule has to treat differently: the path already carries the
      * signalled value, so refusing it on field presence would silently delete this event and this clearing.
      */
    override def onInputDischarged(name: String, frame: Frame, meta: Flow.Meta): Unit < S =
        store.getExecution(eid).map {
            case Present(s) if s.waits.contains(name) =>
                fencedProgress(
                    Clock.nowWith(ts => claimed.recordProgress(name, Flow.Event.InputDischarged(flowId, eid, name, ts)))
                ).unit
            case _ => ()
        }

    def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta): Unit < S =
        if duration <= Duration.Zero then ()
        else
            for
                _   <- guard
                now <- Clock.now
                until = now + duration
                _ <- fenced(
                    claimed.recordWait(
                        name,
                        Flow.Wake.At(until),
                        Flow.Event.SleepStarted(flowId, eid, name, until, now)
                    )
                )
            yield Abort.fail[FlowSuspension](FlowSuspension.Parked(Set(name)))

    /** Joins two branches, where a FAILURE decides the composition immediately.
      *
      * Letting suspension outrank failure here would discard the branch that failed and re-run it from the start on every resume, with
      * its retry schedule and its side effects, until the other branch stopped waiting. A `zip` needs every branch, so once one has
      * failed the result is already unreachable and nothing is lost by saying so at once; the branches that parked have their rows
      * retired by the transition that ends the attempt, which is the same rule a decided race gets. Remembering WHICH branch failed
      * would need durable state, and this needs none.
      */
    def onZip(
        left: Record[Any] < S,
        right: Record[Any] < S,
        ctx: Record[Any],
        isolate: Isolate[Any, Abort[FlowException] & Async, Any]
    ): Record[Any] < S =
        type R = Result[FlowSuspension, Result[FlowException | FlowStoreException, Record[Any]]]
        isolate.capture { state =>
            val isoLeft =
                isolate.isolate(state, Abort.run[FlowSuspension](Abort.run[FlowException | FlowStoreException](left)))
            val isoRight =
                isolate.isolate(state, Abort.run[FlowSuspension](Abort.run[FlowException | FlowStoreException](right)))
            Fiber.internal.foreachIndexed(Chunk.Indexed(isoLeft, isoRight), 2)((_, v) => v).map { fiber =>
                fiber.use { results =>
                    Kyo.foreach(results)(isolate.restore(_)).map { restored =>
                        val lr = restored(0).asInstanceOf[R]
                        val rr = restored(1).asInstanceOf[R]
                        (lr, rr) match
                            case (Result.Success(Result.Success(l)), Result.Success(Result.Success(r))) =>
                                new Record[Any](ctx.toDict ++ l.toDict ++ r.toDict)
                            // A failure decides the zip, ahead of any sibling's suspension: the composition needs every branch,
                            // so its result is already unreachable, and parking on the sibling instead re-runs the failed branch
                            // from the start on every resume until that sibling happens to stop waiting.
                            case (Result.Success(Result.Failure(e)), _) => Abort.fail(e)
                            case (_, Result.Success(Result.Failure(e))) => Abort.fail(e)
                            case (Result.Panic(e), _)                   => Abort.panic(e)
                            case (_, Result.Panic(e))                   => Abort.panic(e)
                            case (Result.Success(Result.Panic(e)), _)   => Abort.panic(e)
                            case (_, Result.Success(Result.Panic(e)))   => Abort.panic(e)
                            // Both branches parked, so the composition is waiting on BOTH sets of conditions and a token
                            // naming one of them makes the other unreachable. Merging is what makes a nested composition's
                            // every parked branch wake on its own condition.
                            case (Result.Failure(l), Result.Failure(r)) => Abort.fail(FlowSuspension.merge(l, r))
                            case (Result.Failure(s), _)                 => Abort.fail[FlowSuspension](s)
                            case (_, Result.Failure(s))                 => Abort.fail[FlowSuspension](s)
                            case _                                      => Abort.panic(new RuntimeException("unreachable: zip result"))
                        end match
                    }
                }
            }
        }
    end onZip

    /** Races two branches, where only a VALUE decides the outcome.
      *
      * A branch that fails does not decide a race the other branch can still win. `race(notify-via-flaky-api, input("manual-ack"))`
      * exists precisely so the acknowledgement can rescue a failed notification, and a failure-wins rule terminally fails the flow
      * while the answer it is waiting for is still deliverable, which deletes the idiom. So a failed branch is treated like a
      * decided-away one: the composition parks on the surviving branches' merged parks, the failure stays recorded in the failing
      * step's own events, and nothing else remembers it. Only a race whose every branch failed fails, with the last failure as its
      * verdict, because from that point no branch can win any longer.
      *
      * Each branch's outcome is recorded as it ends rather than read off the race's own result, and the reason is what the race
      * completes with: the first VALUE, or, if no branch produced one, the LAST error. The first value is the winner and needs
      * nothing else. The last error is one branch's ending standing in for every branch's, which is exactly the choice this rule
      * takes away from the scheduler, so the join re-decides from all of them.
      */
    def onRace(
        left: Record[Any] < S,
        right: Record[Any] < S,
        isolate: Isolate[Any, Abort[FlowException] & Async, Any]
    ): Record[Any] < S =
        type R = Result[FlowSuspension, Result[FlowException | FlowStoreException, Record[Any]]]

        def reraise(outcome: R): Record[Any] < S =
            outcome match
                case Result.Success(Result.Success(record)) => record
                case Result.Failure(suspension)             => Abort.fail[FlowSuspension](suspension)
                case Result.Success(Result.Failure(e))      => Abort.fail(e)
                case Result.Success(Result.Panic(e))        => Abort.panic(e)
                case Result.Panic(e)                        => Abort.panic(e)
                case _ => Abort.panic(new IllegalStateException("a race branch ended with neither a value nor an error"))

        def decide(all: Chunk[R]): Record[Any] < S =
            val parked = all.foldLeft(Maybe.empty[FlowSuspension]) {
                case (acc, Result.Failure(suspension)) => Maybe(acc.fold(suspension)(FlowSuspension.merge(_, suspension)))
                case (acc, _)                          => acc
            }
            parked match
                case Present(suspension) => Abort.fail[FlowSuspension](suspension)
                case Absent =>
                    if all.isEmpty then Abort.panic(new IllegalStateException("a race ended with no branch outcome"))
                    else reraise(all(all.size - 1))
            end match
        end decide

        // The branches run in their own fibers, so the outcome each one ends with has to reach the join somehow; an atomic
        // cell scoped to this one composition is the smallest thing that carries it.
        AtomicRef.init(Chunk.empty[R]).map { outcomes =>
            isolate.capture { state =>
                def branch(b: Record[Any] < S) =
                    isolate.isolate(
                        state,
                        Abort.run[FlowSuspension](Abort.run[FlowException | FlowStoreException](b)).map { outcome =>
                            outcomes.updateAndGet(_.append(outcome)).andThen(reraise(outcome))
                        }
                    )
                Fiber.internal.race(Seq(branch(left), branch(right))).map { fiber =>
                    Abort.run[FlowSuspension](Abort.run[FlowException | FlowStoreException](isolate.restore(fiber.get))).map {
                        case Result.Success(Result.Success(record)) => record
                        case _                                      => outcomes.use(decide)
                    }
                }
            }
        }
    end onRace

    override def checkCancelled: Boolean < S =
        store.getExecution(eid).map {
            case Present(s) => s.cancelRequested
            case _          => false
        }

    override def onCompensationStart(cause: Flow.Cause): Unit < S =
        Clock.nowWith { ts =>
            fencedStatus(
                claimed.updateStatus(Flow.Status.Compensating(cause), Flow.Event.CompensationStarted(flowId, eid, cause, ts))
            )
        }

    override def onCompensationComplete: Unit < S =
        appendEvent(ts => Flow.Event.CompensationCompleted(flowId, eid, ts))

    override def onCompensationFailed(error: Throwable): Unit < S =
        appendEvent(ts => Flow.Event.CompensationFailed(flowId, eid, error.getMessage, ts))

    /** One handler ran to completion, recorded durably so a resumed unwind does not run it a second time.
      *
      * The same shape a step's own completion has, one path down: a valueless progress under a key of the handler's own, refused by
      * write-once if it is already there. It cannot ride the node's own path, because the node's completion already occupies it and the
      * handler's write would be answered already-recorded before it ever meant anything.
      *
      * Only a handler that SUCCEEDED is recorded. One that threw has no completion, so a later attempt runs it again, which is what an
      * unwind that could not finish should do.
      */
    override def onCompensated(name: String): Unit < S =
        fencedProgress(
            Clock.nowWith(ts =>
                claimed.recordProgress(
                    FlowInterpreter.compensatedKey(name),
                    Flow.Event.NodeCompensated(flowId, eid, name, ts)
                )
            )
        ).unit

end StoreInterpreter
