package kyo.internal

import kyo.*
import kyo.kernel.Isolate

/** Input metadata extracted from the flow AST at registration time. */
private[kyo] case class InputMeta(name: String, tag: Tag[Any], schema: Schema[Any], frame: Frame)

/** Wraps the entire Flow.run computation to handle custom effects.
  *
  * The identity-typed `erased` method slots into any effect context. At runtime the implementation handles effects pending inside the
  * computation via type erasure.
  */
abstract private[kyo] class FlowRunner:
    def erased[V, S](v: V < S)(using Frame): V < S

private[kyo] object FlowRunner:
    def apply[S](f: [V] => V < S => V < (Async & Scope & Abort[FlowException])): FlowRunner =
        new FlowRunner:
            def erased[V, S2](v: V < S2)(using Frame): V < S2 =
                f(v.asInstanceOf[V < S]).asInstanceOf[V < S2]
end FlowRunner

private[kyo] case class FlowDefinition(
    id: Flow.Id.Workflow,
    flow: Flow[?, ?, ?],
    runner: Maybe[FlowRunner],
    inputs: Seq[InputMeta],
    meta: FlowEngine.WorkflowInfo,
    schema: WorkflowSchema
)

private[kyo] class StoreInterpreter(
    store: FlowStore,
    eid: Flow.Id.Execution,
    flowId: Flow.Id.Workflow,
    executorId: Flow.Id.Executor,
    defn: FlowDefinition
)(using Frame) extends FlowInterpreter[Async & Abort[FlowException] & Abort[FlowSuspension] & Abort[FlowStoreException]]:

    // The store's own error channel is part of the interpreter's row: a store failure raised while an execution is running is a failure
    // of that execution, and the engine discharges it where it discharges the flow's own.
    type S = Async & Abort[FlowException] & Abort[FlowSuspension] & Abort[FlowStoreException]

    private def withEvent[A](event: Instant => Flow.Event)(body: => A < S): A < S =
        Clock.nowWith(ts => store.appendEvent(eid, event(ts))).andThen(body)

    /** Refuses to go on unless this executor still holds the claim.
      *
      * Run before a step and again before its result is written. The second check is what keeps an executor whose lease lapsed mid-step
      * from overwriting the work of the executor that took the execution over: it computed its value under a claim it no longer has, so
      * the value is stale by the time it is ready, and the store has no way to tell it apart from the live executor's.
      *
      * A node whose `StepStarted` has no completion after it is re-executed rather than waited on, which is the other half of the same
      * fact: an execution is only handed to this executor once no one else holds a live lease on it, so a start with no completion is a
      * record of an executor that is gone, never of one still working. That is the recovery contract steps are required to be idempotent
      * for.
      */
    private def checkClaim: Unit < S =
        store.getExecution(eid).map {
            case Present(s) if s.executor == Maybe(executorId) => ()
            case _                                             => Abort.fail[FlowSuspension](FlowSuspension.ClaimLost)
        }

    /** Run a computation with timeout and retry according to the step's Meta. */
    private def withTimeoutAndRetry[V](name: String, computation: V < Sync, meta: Flow.Meta): V < S =
        val withTimeout: V < S =
            if meta.timeout == Duration.Infinity then computation
            else
                Abort.recover[Timeout] { _ =>
                    Clock.nowWith { ts =>
                        store.appendEvent(eid, Flow.Event.StepTimedOut(flowId, eid, name, meta.timeout, ts))
                    }.andThen(Abort.panic(new RuntimeException(s"Step '$name' timed out after ${meta.timeout.show}")))
                }(Async.timeout(meta.timeout)(computation))
        meta.retry match
            case Absent => withTimeout
            case Present(schedule) =>
                def attempt(sched: Schedule, attemptNum: Int): V < S =
                    Abort.run[Throwable](withTimeout).map {
                        case Result.Success(v) => v
                        case result =>
                            Clock.nowWith { now =>
                                sched.next(now) match
                                    case Present((delay, nextSched)) =>
                                        val error = result match
                                            case Result.Failure(e) => e.getMessage
                                            case Result.Panic(e)   => e.getMessage
                                            case _                 => "unknown"
                                        store.appendEvent(eid, Flow.Event.StepRetried(flowId, eid, name, error, attemptNum, delay, now))
                                            .andThen(Async.sleep(delay))
                                            .andThen(attempt(nextSched, attemptNum + 1))
                                    case _ =>
                                        result match
                                            case Result.Failure(e) => Abort.panic(e)
                                            case Result.Panic(e)   => Abort.panic(e)
                                            case _                 => Abort.panic(new RuntimeException("retry exhausted"))
                            }
                    }
                attempt(schedule, 1)
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
            _   <- checkClaim
            ts  <- Clock.now
            _   <- store.appendEvent(eid, Flow.Event.StepStarted(flowId, eid, name, executorId, ts))
            v   <- withTimeoutAndRetry(name, computation, meta)
            _   <- checkClaim
            _   <- store.putField[V](eid, name, v)
            ts2 <- Clock.now
            _   <- store.updateStatus(eid, Flow.Status.Running, Flow.Event.StepCompleted(flowId, eid, name, ts2))
        yield v

    def onStep(name: String, computation: Unit < Sync, frame: Frame, meta: Flow.Meta): Unit < S =
        for
            _   <- checkClaim
            ts  <- Clock.now
            _   <- store.appendEvent(eid, Flow.Event.StepStarted(flowId, eid, name, executorId, ts))
            _   <- withTimeoutAndRetry(name, computation, meta)
            _   <- checkClaim
            ts2 <- Clock.now
            _   <- store.updateStatus(eid, Flow.Status.Running, Flow.Event.StepCompleted(flowId, eid, name, ts2))
        yield ()

    def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Schema[V]): V < S =
        store.getField[V](eid, name).map {
            case Present(v) => v
            case _ =>
                store.getExecution(eid).map {
                    case Present(s) if s.status == Flow.Status.Cancelled =>
                        Abort.fail(FlowCancelledException(eid.value))
                    case _ =>
                        Clock.nowWith { now =>
                            store.updateStatus(
                                eid,
                                Flow.Status.WaitingForInput(name),
                                Flow.Event.InputWaiting(flowId, eid, name, now)
                            ).map(_ =>
                                Abort.fail[FlowSuspension](FlowSuspension.WaitingForInput(name))
                            )
                        }
                }
        }

    def onSleep(name: String, duration: Duration, frame: Frame, meta: Flow.Meta): Unit < S =
        if duration <= Duration.Zero then ()
        else
            for
                _   <- checkClaim
                now <- Clock.now
                until = now + duration
                _ <- store.updateStatus(
                    eid,
                    Flow.Status.Sleeping(name, until),
                    Flow.Event.SleepStarted(flowId, eid, name, until, now)
                )
            yield Abort.fail[FlowSuspension](FlowSuspension.Sleeping(name, until))

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
                            case (Result.Failure(s), _)                 => Abort.fail[FlowSuspension](s)
                            case (_, Result.Failure(s))                 => Abort.fail[FlowSuspension](s)
                            case (Result.Success(Result.Failure(e)), _) => Abort.fail(e)
                            case (_, Result.Success(Result.Failure(e))) => Abort.fail(e)
                            case (Result.Panic(e), _)                   => Abort.panic(e)
                            case (_, Result.Panic(e))                   => Abort.panic(e)
                            case (Result.Success(Result.Panic(e)), _)   => Abort.panic(e)
                            case (_, Result.Success(Result.Panic(e)))   => Abort.panic(e)
                            case _                                      => Abort.panic(new RuntimeException("unreachable: zip result"))
                        end match
                    }
                }
            }
        }
    end onZip

    def onRace(
        left: Record[Any] < S,
        right: Record[Any] < S,
        isolate: Isolate[Any, Abort[FlowException] & Async, Any]
    ): Record[Any] < S =
        isolate.capture { state =>
            Fiber.internal.race(Seq(
                isolate.isolate(state, left),
                isolate.isolate(state, right)
            )).map(fiber => isolate.restore(fiber.get))
        }

    override def checkCancelled: Boolean < S =
        store.getExecution(eid).map {
            case Present(s) => s.status == Flow.Status.Cancelled
            case _          => false
        }

    override def onCompensationStart: Unit < S =
        Clock.nowWith { ts =>
            store.updateStatus(eid, Flow.Status.Compensating, Flow.Event.CompensationStarted(flowId, eid, ts))
        }

    override def onCompensationComplete: Unit < S =
        withEvent(ts => Flow.Event.CompensationCompleted(flowId, eid, ts))(())

    override def onCompensationFailed(error: Throwable): Unit < S =
        withEvent(ts => Flow.Event.CompensationFailed(flowId, eid, error.getMessage, ts))(())

end StoreInterpreter
