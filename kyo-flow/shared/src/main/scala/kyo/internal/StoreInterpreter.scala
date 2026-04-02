package kyo.internal

import kyo.*
import kyo.kernel.Isolate

/** Input metadata extracted from the flow AST at registration time. */
private[kyo] case class InputMeta(name: String, tag: Tag[Any], json: Json[Any], frame: Frame)

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
)(using Frame) extends FlowInterpreter[Async & Abort[FlowException] & Abort[FlowSuspension]]:

    type S = Async & Abort[FlowException] & Abort[FlowSuspension]

    private def withEvent[A](event: Instant => Flow.Event)(body: => A < S): A < S =
        Clock.nowWith(ts => store.appendEvent(eid, event(ts))).andThen(body)

    private def checkClaim: Unit < S =
        store.getExecution(eid).map {
            case Present(s) if s.executor == Maybe(executorId) => ()
            case _                                             => Abort.fail[FlowSuspension](FlowSuspension.ClaimLost)
        }

    private def checkInFlight(name: String, timeout: Duration): Unit < S =
        store.getHistory(eid, Int.MaxValue, 0).map { page =>
            val started = page.events.exists {
                case Flow.Event.StepStarted(_, _, n, _, _) => n == name
                case _                                     => false
            }
            val completed = page.events.exists {
                case Flow.Event.StepCompleted(_, _, n, _) => n == name
                case _                                    => false
            }
            if started && !completed then
                def poll(remaining: Duration): Unit < S =
                    if remaining <= Duration.Zero then ()
                    else
                        Async.sleep(1.second).map { _ =>
                            store.getHistory(eid, Int.MaxValue, 0).map { fresh =>
                                val done = fresh.events.exists {
                                    case Flow.Event.StepCompleted(_, _, n, _) => n == name
                                    case _                                    => false
                                }
                                if done then
                                    Abort.fail[FlowSuspension](FlowSuspension.StepAlreadyCompleted(name))
                                else poll(remaining - 1.second)
                            }
                        }
                poll(timeout)
            else ()
            end if
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

    def getField[V](name: String)(using Tag[V], Json[V]): Maybe[V] < S =
        store.getField[V](eid, name)

    def onOutput[V](name: String, computation: V < Sync, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]): V < S =
        for
            _   <- checkClaim
            _   <- checkInFlight(name, meta.timeout)
            ts  <- Clock.now
            _   <- store.appendEvent(eid, Flow.Event.StepStarted(flowId, eid, name, executorId, ts))
            v   <- withTimeoutAndRetry(name, computation, meta)
            _   <- store.putField[V](eid, name, v)
            ts2 <- Clock.now
            _   <- store.updateStatus(eid, Flow.Status.Running, Flow.Event.StepCompleted(flowId, eid, name, ts2))
        yield v

    def onStep(name: String, computation: Unit < Sync, frame: Frame, meta: Flow.Meta): Unit < S =
        for
            _   <- checkClaim
            _   <- checkInFlight(name, meta.timeout)
            ts  <- Clock.now
            _   <- store.appendEvent(eid, Flow.Event.StepStarted(flowId, eid, name, executorId, ts))
            _   <- withTimeoutAndRetry(name, computation, meta)
            ts2 <- Clock.now
            _   <- store.updateStatus(eid, Flow.Status.Running, Flow.Event.StepCompleted(flowId, eid, name, ts2))
        yield ()

    def onInput[V](name: String, frame: Frame, meta: Flow.Meta)(using Tag[V], Json[V]): V < S =
        store.getField[V](eid, name).map {
            case Present(v) => v
            case _ =>
                store.getExecution(eid).map {
                    case Present(s) if s.status == Flow.Status.Cancelled =>
                        Abort.fail[FlowException](FlowCancelledException(eid.value))
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
        type R = Result[FlowSuspension, Result[FlowException, Record[Any]]]
        isolate.capture { state =>
            val isoLeft  = isolate.isolate(state, Abort.run[FlowSuspension](Abort.run[FlowException](left)))
            val isoRight = isolate.isolate(state, Abort.run[FlowSuspension](Abort.run[FlowException](right)))
            Fiber.foreachIndexed(Seq(isoLeft, isoRight))((_, v) => v).map { fiber =>
                fiber.use { results =>
                    Kyo.foreach(results)(isolate.restore(_)).map { restored =>
                        val lr = restored(0).asInstanceOf[R]
                        val rr = restored(1).asInstanceOf[R]
                        (lr, rr) match
                            case (Result.Success(Result.Success(l)), Result.Success(Result.Success(r))) =>
                                new Record[Any](ctx.toDict ++ l.toDict ++ r.toDict)
                            case (Result.Failure(s), _)                 => Abort.fail[FlowSuspension](s)
                            case (_, Result.Failure(s))                 => Abort.fail[FlowSuspension](s)
                            case (Result.Success(Result.Failure(e)), _) => Abort.fail[FlowException](e)
                            case (_, Result.Success(Result.Failure(e))) => Abort.fail[FlowException](e)
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
