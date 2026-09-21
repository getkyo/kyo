package kyo.internal.engine

import kyo.*

/** Drives the single-envelope pipeline directly, with no transport and no handler engine: envelopes go in through `admit` or `dispatch`, and
  * request-scoped notifications come out through a recording environment.
  */
class InboundPipelineTest extends kyo.JsonRpcTest:

    // Unsafe: settlement callbacks and request transitions are the unsafe-tier hooks the pipeline hands its callers.
    import AllowUnsafe.embrace.danger

    case class Req(n: Int) derives Schema, CanEqual
    case class Resp(n: Int) derives Schema, CanEqual
    case class Note(text: String) derives Schema, CanEqual
    case class TokenReq(n: Int, workDoneToken: Maybe[String] = Absent) derives Schema, CanEqual
    case class CancelParams(id: JsonRpcId) derives Schema, CanEqual

    private val echo = JsonRpcRoute.request[Req, Resp]("echo")((req, _) => Resp(req.n))

    private def params(n: Int): Maybe[Structure.Value] = Present(Structure.encode(Req(n)))

    private def request(id: Long, method: String, n: Int = 1): JsonRpcRequest =
        JsonRpcRequest(JsonRpcId.Num(id), method, params(n), Absent)

    private def resultResponse(id: Long, n: Int): JsonRpcResponse =
        JsonRpcResponse(JsonRpcId.Num(id), Present(Structure.encode(Resp(n))), Absent, Absent)

    private def pipeline(routes: Seq[JsonRpcRoute[?, ?, ?]], config: JsonRpcHandler.Config = JsonRpcHandler.Config.default)(using
        Frame
    ): InboundPipeline < Sync =
        Sync.defer(InboundPipeline.init(routes, config))

    /** An environment that records every notification it is asked to emit, live while `live` is true. */
    final private class Recording(val emitted: AtomicRef[Chunk[JsonRpcNotification]], val live: AtomicBoolean):
        def env(using Frame): InboundPipeline.Environment =
            new InboundPipeline.Environment(n => emitted.updateAndGet(_.append(n)).unit, live.get)

    private def recording(using Frame): Recording < Sync =
        for
            emitted <- AtomicRef.init(Chunk.empty[JsonRpcNotification])
            live    <- AtomicBoolean.init(true)
        yield new Recording(emitted, live)

    private val cancellation = JsonRpcCancellationPolicy(
        cancelMethod = "$/cancelRequest",
        encodeParams = (id, _) => f ?=> Sync.defer(Structure.encode(CancelParams(id)))(using f),
        decodeParams = sv =>
            f ?=>
                Sync.defer {
                    Structure.decode[CancelParams](sv)(using summon[Schema[CancelParams]], f) match
                        case Result.Success(p) => Present(p.id)
                        case _                 => Absent
                }(using f),
        expectReplyForCancelledRequest = false,
        cancelledError = Absent,
        protectedMethods = Set("initialize")
    )

    private def cancelNotification(id: Long): JsonRpcNotification =
        JsonRpcNotification("$/cancelRequest", Present(Structure.encode(CancelParams(JsonRpcId.Num(id)))), Absent)

    private val progressPolicy = JsonRpcProgressPolicy(
        progressMethod = "$/progress",
        extractInboundToken = p => JsonRpcProgressPolicy.field(p, "token"),
        extractRequestToken = p => JsonRpcProgressPolicy.field(p, "workDoneToken"),
        stampOutboundToken = (p, t) => JsonRpcProgressPolicy.merge(p, Structure.Value.Record(Chunk("workDoneToken" -> t))),
        encodeProgressParams = (t, v) => Structure.Value.Record(Chunk("token" -> t, "value" -> v)),
        extractProgressValue = p => JsonRpcProgressPolicy.field(p, "value"),
        enforceMonotonic = false
    )

    private def gate(decision: JsonRpcMessageGate.Decision): JsonRpcMessageGate =
        new JsonRpcMessageGate:
            def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync = Sync.defer(decision)

    /** A route named `method` whose handler records that it ran, captures its context, and then parks until interrupted. */
    final private class Parked(
        val method: String,
        val entered: Latch,
        val stopped: Latch,
        val context: AtomicRef[Maybe[JsonRpcRoute.Context]]
    ):
        def route(using Frame): JsonRpcRoute[Req, Resp, Nothing] =
            JsonRpcRoute.request[Req, Resp](method) { (_, ctx) =>
                Sync.ensure(stopped.release) {
                    context.set(Present(ctx)).andThen(entered.release).andThen(Async.never[Resp])
                }
            }
        def cancelledDone(using Frame): Boolean < Sync =
            context.get.map {
                case Present(ctx) => ctx.cancelled.done
                case Absent       => false
            }
    end Parked

    private def parked(method: String)(using Frame): Parked < Sync =
        for
            entered <- Latch.init(1)
            stopped <- Latch.init(1)
            context <- AtomicRef.init[Maybe[JsonRpcRoute.Context]](Absent)
        yield new Parked(method, entered, stopped, context)

    private def admitted(admission: InboundPipeline.Admission): InboundRequest =
        admission match
            case InboundPipeline.Admission.Dispatched(request) => request
            case other                                         => throw new AssertionError(s"expected a dispatched request, got $other")

    "requests and notifications" - {

        "a request is answered with its handler's result" in {
            for
                rec     <- recording
                p       <- pipeline(Seq(echo))
                outcome <- p.dispatch(request(1, "echo", 7), rec.env)
            yield
                assert(outcome == InboundPipeline.Outcome.Reply(resultResponse(1, 7)))
                assert(p.registry.size == 0)
        }

        "a request's extras are carried to its response" in {
            val extras = Structure.Value.Record(Chunk("session" -> Structure.Value.Str("s1")))
            for
                rec     <- recording
                p       <- pipeline(Seq(echo))
                outcome <- p.dispatch(JsonRpcRequest(JsonRpcId.Str("a"), "echo", params(3), Present(extras)), rec.env)
            yield assert(
                outcome == InboundPipeline.Outcome.Reply(JsonRpcResponse(
                    JsonRpcId.Str("a"),
                    Present(Structure.encode(Resp(3))),
                    Absent,
                    Present(extras)
                ))
            )
            end for
        }

        "a notification runs its handler and produces no reply" in {
            for
                seen <- AtomicRef.init(Chunk.empty[Note])
                route = JsonRpcRoute.notification[Note]("note")((note, _) => seen.updateAndGet(_.append(note)).unit)
                rec     <- recording
                p       <- pipeline(Seq(route))
                outcome <- p.dispatch(JsonRpcNotification("note", Present(Structure.encode(Note("hi"))), Absent), rec.env)
                notes   <- seen.get
            yield
                assert(outcome == InboundPipeline.Outcome.NoReply)
                assert(notes == Chunk(Note("hi")))
                assert(p.registry.size == 0)
        }

        "admit registers a request before it returns and reports the reply through onSettle" in {
            for
                gateLatch <- Latch.init(1)
                route = JsonRpcRoute.request[Req, Resp]("held")((req, _) => gateLatch.await.andThen(Resp(req.n)))
                rec       <- recording
                p         <- pipeline(Seq(route))
                settled   <- Fiber.Promise.init[InboundPipeline.Settlement, Any]
                admission <- p.admit(request(4, "held", 4), rec.env, (_, s) => settled.unsafe.completeDiscard(Result.succeed(s)))
                inflow = admitted(admission)
                registered <- Sync.defer(p.registry.get(JsonRpcId.Num(4L)))
                _          <- gateLatch.release
                settlement <- settled.get
                written    <- Sync.defer(inflow.commit())
            yield
                assert(registered.exists(_ eq inflow))
                assert(settlement == InboundPipeline.Settlement.Reply(resultResponse(4, 4)))
                assert(written)
                assert(p.registry.size == 0)
        }
    }

    "gate" - {

        "a request the gate rejects is answered with the gate's response and runs no handler" in {
            val rejection = JsonRpcResponse(JsonRpcId.Num(1L), Absent, Present(JsonRpcCustomError(-32002, "not initialized")), Absent)
            for
                ran <- AtomicBoolean.init(false)
                route = JsonRpcRoute.request[Req, Resp]("echo")((req, _) => ran.set(true).andThen(Resp(req.n)))
                rec     <- recording
                p       <- pipeline(Seq(route), JsonRpcHandler.Config.default.gate(gate(JsonRpcMessageGate.Decision.Reject(rejection))))
                outcome <- p.dispatch(request(1, "echo"), rec.env)
                didRun  <- ran.get
            yield
                assert(outcome == InboundPipeline.Outcome.Reply(rejection))
                assert(!didRun)
            end for
        }

        "a notification the gate rejects runs no handler and produces no reply" in {
            val rejection = JsonRpcResponse(JsonRpcId.Num(0L), Absent, Present(JsonRpcCustomError(-32002, "not initialized")), Absent)
            for
                ran <- AtomicBoolean.init(false)
                route = JsonRpcRoute.notification[Note]("note")((_, _) => ran.set(true))
                rec     <- recording
                p       <- pipeline(Seq(route), JsonRpcHandler.Config.default.gate(gate(JsonRpcMessageGate.Decision.Reject(rejection))))
                outcome <- p.dispatch(JsonRpcNotification("note", Present(Structure.encode(Note("x"))), Absent), rec.env)
                didRun  <- ran.get
            yield
                assert(outcome == InboundPipeline.Outcome.NoReply)
                assert(!didRun)
            end for
        }

        "a request the gate drops runs no handler and produces no reply" in {
            for
                ran <- AtomicBoolean.init(false)
                route = JsonRpcRoute.request[Req, Resp]("echo")((req, _) => ran.set(true).andThen(Resp(req.n)))
                rec     <- recording
                p       <- pipeline(Seq(route), JsonRpcHandler.Config.default.gate(gate(JsonRpcMessageGate.Decision.Drop)))
                outcome <- p.dispatch(request(1, "echo"), rec.env)
                didRun  <- ran.get
            yield
                assert(outcome == InboundPipeline.Outcome.NoReply)
                assert(!didRun)
        }

        "the gate never sees a cancel notification" in {
            for
                seen <- AtomicRef.init(Chunk.empty[JsonRpcEnvelope])
                recordingGate = new JsonRpcMessageGate:
                    def beforeDispatch(env: JsonRpcEnvelope)(using Frame): JsonRpcMessageGate.Decision < Sync =
                        seen.updateAndGet(_.append(env)).andThen(JsonRpcMessageGate.Decision.Allow)
                rec     <- recording
                p       <- pipeline(Seq(echo), JsonRpcHandler.Config.default.cancellation(cancellation).gate(recordingGate))
                outcome <- p.dispatch(cancelNotification(1), rec.env)
                gated   <- seen.get
            yield
                assert(outcome == InboundPipeline.Outcome.NoReply)
                assert(gated.isEmpty, s"the gate saw $gated")
        }
    }

    "unknown methods" - {

        "an unknown request is answered with method not found under ReplyMethodNotFound" in {
            for
                rec     <- recording
                p       <- pipeline(Seq(echo))
                outcome <- p.dispatch(request(9, "missing"), rec.env)
            yield outcome match
                case InboundPipeline.Outcome.Reply(JsonRpcResponse(
                        JsonRpcId.Num(9L),
                        Absent,
                        Present(error: JsonRpcMethodNotFoundError),
                        Absent
                    )) =>
                    assert(error.method == "missing" && error.available == Chunk("echo"))
                case other => fail(s"expected a method-not-found reply, got $other")
        }

        "an unknown request produces no reply under Drop" in {
            val policy = JsonRpcUnknownMethodPolicy.minimal.copy(onUnknownRequest = JsonRpcUnknownMethodPolicy.UnknownAction.Drop)
            for
                rec     <- recording
                p       <- pipeline(Seq(echo), JsonRpcHandler.Config.default.unknownMethod(policy))
                outcome <- p.dispatch(request(9, "missing"), rec.env)
            yield assert(outcome == InboundPipeline.Outcome.NoReply)
            end for
        }

        "an unknown request is a violation carrying method not found under Reject" in {
            val policy = JsonRpcUnknownMethodPolicy.minimal.copy(onUnknownRequest = JsonRpcUnknownMethodPolicy.UnknownAction.Reject)
            for
                rec     <- recording
                p       <- pipeline(Seq(echo), JsonRpcHandler.Config.default.unknownMethod(policy))
                outcome <- p.dispatch(request(9, "missing"), rec.env)
            yield outcome match
                case InboundPipeline.Outcome.Violation(Present(JsonRpcResponse(
                        JsonRpcId.Num(9L),
                        Absent,
                        Present(_: JsonRpcMethodNotFoundError),
                        Absent
                    ))) =>
                    succeed
                case other => fail(s"expected a violation carrying method not found, got $other")
            end for
        }

        "an unknown notification is a violation with no reply under Reject, unless its method is ignored" in {
            val strict  = JsonRpcUnknownMethodPolicy.strict
            val ignored = strict.copy(ignoreUnknownNotification = _.startsWith("$/"))
            for
                rec        <- recording
                p          <- pipeline(Seq(echo), JsonRpcHandler.Config.default.unknownMethod(ignored))
                rejected   <- p.dispatch(JsonRpcNotification("missing", Absent, Absent), rec.env)
                wasIgnored <- p.dispatch(JsonRpcNotification("$/ping", Absent, Absent), rec.env)
            yield
                assert(rejected == InboundPipeline.Outcome.Violation(Absent))
                assert(wasIgnored == InboundPipeline.Outcome.NoReply)
            end for
        }
    }

    "handler results" - {

        "a Halt is answered with the halt's response verbatim" in {
            val halted = JsonRpcResponse(JsonRpcId.Num(1L), Present(Structure.Value.Str("halted")), Absent, Absent)
            val route  = JsonRpcRoute.request[Req, Resp]("halt")((_, _) => JsonRpcResponse.halt(halted))
            for
                rec     <- recording
                p       <- pipeline(Seq(route))
                outcome <- p.dispatch(request(1, "halt"), rec.env)
            yield assert(outcome == InboundPipeline.Outcome.Reply(halted))
            end for
        }

        "a JsonRpcError abort is answered as an error response" in {
            val error = JsonRpcCustomError(-32001, "denied")
            val route = JsonRpcRoute.request[Req, Resp]("deny")((_, _) => Abort.fail(error))
            for
                rec     <- recording
                p       <- pipeline(Seq(route))
                outcome <- p.dispatch(request(1, "deny"), rec.env)
            yield assert(outcome == InboundPipeline.Outcome.Reply(JsonRpcResponse(JsonRpcId.Num(1L), Absent, Present(error), Absent)))
            end for
        }

        "a domain abort mapped by the route is answered with the mapped code" in {
            case class Denied(reason: String) derives Schema, CanEqual
            val route = JsonRpcRoute.request[Req, Resp]("deny")((_, _) => Abort.fail(Denied("nope"))).error[Denied](-32010, "denied")
            for
                rec     <- recording
                p       <- pipeline(Seq(route))
                outcome <- p.dispatch(request(1, "deny"), rec.env)
            yield outcome match
                case InboundPipeline.Outcome.Reply(JsonRpcResponse(JsonRpcId.Num(1L), Absent, Present(error), Absent)) =>
                    assert(error.code == -32010)
                    assert(error.data == Present(Structure.encode(Denied("nope"))))
                case other => fail(s"expected a mapped error reply, got $other")
            end for
        }

        "a panic is answered with a handler panic error" in {
            val route = JsonRpcRoute.request[Req, Resp]("explode")((_, _) => Sync.defer[Resp, Any](throw new RuntimeException("boom")))
            for
                rec     <- recording
                p       <- pipeline(Seq(route))
                outcome <- p.dispatch(request(1, "explode"), rec.env)
            yield outcome match
                case InboundPipeline.Outcome.Reply(JsonRpcResponse(
                        JsonRpcId.Num(1L),
                        Absent,
                        Present(error: JsonRpcHandlerPanicError),
                        Absent
                    )) =>
                    assert(error.method == "explode" && error.cause.getMessage == "boom")
                case other => fail(s"expected a handler panic reply, got $other")
            end for
        }

        "a request reusing an id already in flight is answered with invalid request and runs no second handler" in {
            for
                handler   <- parked("wait")
                rec       <- recording
                p         <- pipeline(Seq(handler.route))
                admission <- p.admit(request(1, "wait"), rec.env, (_, _) => ())
                _         <- handler.entered.await
                duplicate <- p.dispatch(request(1, "wait"), rec.env)
                _         <- Sync.defer(admitted(admission).abort())
                _         <- handler.stopped.await
            yield duplicate match
                case InboundPipeline.Outcome.Reply(JsonRpcResponse(
                        JsonRpcId.Num(1L),
                        Absent,
                        Present(_: JsonRpcInvalidRequestError),
                        Absent
                    )) =>
                    succeed
                case other => fail(s"expected an invalid-request reply, got $other")
        }
    }

    "request context" - {

        "progress reported by the handler is emitted before the reply" in {
            val route = JsonRpcRoute.request[TokenReq, Resp]("work") { (req, ctx) =>
                Abort.run[Closed](ctx.progress(Structure.Value.Str("half"))).andThen(Resp(req.n))
            }
            val envelope = JsonRpcRequest(JsonRpcId.Num(1L), "work", Present(Structure.encode(TokenReq(5, Present("tok")))), Absent)
            for
                rec     <- recording
                p       <- pipeline(Seq(route), JsonRpcHandler.Config.default.progress(progressPolicy))
                outcome <- p.dispatch(envelope, rec.env)
                emitted <- rec.emitted.get
            yield
                assert(outcome == InboundPipeline.Outcome.Reply(resultResponse(1, 5)))
                assert(emitted == Chunk(JsonRpcNotification(
                    "$/progress",
                    Present(Structure.Value.Record(Chunk("token" -> Structure.Value.Str("tok"), "value" -> Structure.Value.Str("half")))),
                    Absent
                )))
            end for
        }

        "notify emits a notification stamped with the request's extras" in {
            val extras = Structure.Value.Record(Chunk("session" -> Structure.Value.Str("s1")))
            val route  = JsonRpcRoute.request[Req, Resp]("talk") { (req, ctx) =>
                Abort.run[Closed](ctx.notify("log", Note("working"))).andThen(Resp(req.n))
            }
            for
                rec     <- recording
                p       <- pipeline(Seq(route))
                outcome <- p.dispatch(JsonRpcRequest(JsonRpcId.Num(1L), "talk", params(2), Present(extras)), rec.env)
                emitted <- rec.emitted.get
            yield
                assert(outcome == InboundPipeline.Outcome.Reply(JsonRpcResponse(
                    JsonRpcId.Num(1L),
                    Present(Structure.encode(Resp(2))),
                    Absent,
                    Present(extras)
                )))
                assert(emitted == Chunk(JsonRpcNotification("log", Present(Structure.encode(Note("working"))), Present(extras))))
            end for
        }

        "a notification handler can notify too" in {
            val route = JsonRpcRoute.notification[Note]("note")((note, ctx) => Abort.run[Closed](ctx.notify("echoed", note)).unit)
            for
                rec     <- recording
                p       <- pipeline(Seq(route))
                outcome <- p.dispatch(JsonRpcNotification("note", Present(Structure.encode(Note("n1"))), Absent), rec.env)
                emitted <- rec.emitted.get
            yield
                assert(outcome == InboundPipeline.Outcome.NoReply)
                assert(emitted == Chunk(JsonRpcNotification("echoed", Present(Structure.encode(Note("n1"))), Absent)))
            end for
        }

        "meta carries the params' _meta member, and is Absent without one" in {
            val meta = Structure.Value.Record(Chunk("progressToken" -> Structure.Value.Str("t1")))
            for
                captured <- AtomicRef.init(Chunk.empty[Maybe[Structure.Value]])
                route =
                    JsonRpcRoute.request[Req, Resp]("meta")((req, ctx) => captured.updateAndGet(_.append(ctx.meta)).andThen(Resp(req.n)))
                rec <- recording
                p   <- pipeline(Seq(route))
                withMeta = Structure.Value.Record(Chunk("n" -> Structure.Value.Integer(1L), "_meta" -> meta))
                _     <- p.dispatch(JsonRpcRequest(JsonRpcId.Num(1L), "meta", Present(withMeta), Absent), rec.env)
                _     <- p.dispatch(JsonRpcRequest(JsonRpcId.Num(2L), "meta", params(2), Absent), rec.env)
                metas <- captured.get
            yield assert(metas == Chunk(Present(meta), Absent))
            end for
        }

        "request-scoped notifications are dropped while the environment is not live" in {
            val route = JsonRpcRoute.request[TokenReq, Resp]("work") { (req, ctx) =>
                Abort.run[Closed](ctx.progress(Structure.Value.Str("half")))
                    .andThen(Abort.run[Closed](ctx.notify("log", Note("x"))))
                    .andThen(Resp(req.n))
            }
            val envelope = JsonRpcRequest(JsonRpcId.Num(1L), "work", Present(Structure.encode(TokenReq(5, Present("tok")))), Absent)
            for
                rec     <- recording
                _       <- rec.live.set(false)
                p       <- pipeline(Seq(route), JsonRpcHandler.Config.default.progress(progressPolicy))
                outcome <- p.dispatch(envelope, rec.env)
                emitted <- rec.emitted.get
            yield
                assert(outcome == InboundPipeline.Outcome.Reply(resultResponse(1, 5)))
                assert(emitted.isEmpty, s"expected nothing emitted, got $emitted")
            end for
        }

        "request-scoped notifications are dropped once the request has been answered" in {
            for
                captured <- AtomicRef.init[Maybe[JsonRpcRoute.Context]](Absent)
                route = JsonRpcRoute.request[Req, Resp]("once")((req, ctx) => captured.set(Present(ctx)).andThen(Resp(req.n)))
                rec     <- recording
                p       <- pipeline(Seq(route))
                outcome <- p.dispatch(request(1, "once"), rec.env)
                ctx     <- captured.get
                _       <- ctx match
                    case Present(c) => Abort.run[Closed](c.notify("late", Note("after")))
                    case Absent     => Kyo.unit
                emitted <- rec.emitted.get
            yield
                assert(outcome == InboundPipeline.Outcome.Reply(resultResponse(1, 1)))
                assert(ctx.nonEmpty)
                assert(emitted.isEmpty, s"a notification followed its reply: $emitted")
        }

        "an emitter that fails with Closed does not fail the handler" in {
            val route = JsonRpcRoute.request[Req, Resp]("talk") { (req, ctx) =>
                ctx.notify("log", Note("x")).andThen(Resp(req.n))
            }
            val closedEnv = new InboundPipeline.Environment(_ => Abort.fail(new Closed("peer", summon[Frame])), Sync.defer(true))
            for
                p       <- pipeline(Seq(route))
                outcome <- p.dispatch(request(1, "talk", 6), closedEnv)
            yield assert(outcome == InboundPipeline.Outcome.Reply(resultResponse(1, 6)))
            end for
        }
    }

    "cancellation" - {

        "cancelling an admitted request completes ctx.cancelled, interrupts its handler, and suppresses its reply" in {
            for
                handler   <- parked("wait")
                rec       <- recording
                p         <- pipeline(Seq(handler.route))
                settled   <- Fiber.Promise.init[InboundPipeline.Settlement, Any]
                admission <- p.admit(request(1, "wait"), rec.env, (_, s) => settled.unsafe.completeDiscard(Result.succeed(s)))
                _         <- handler.entered.await
                cancelled <- Sync.defer(admitted(admission).cancel())
                _         <- handler.stopped.await
                done      <- handler.cancelledDone
                result    <- settled.get
            yield
                assert(cancelled)
                assert(done, "ctx.cancelled was not completed")
                assert(result == InboundPipeline.Settlement.NoReply)
                assert(p.registry.size == 0)
        }

        "a peer cancel notification cancels its target request" in {
            for
                handler <- parked("wait")
                rec     <- recording
                p       <- pipeline(Seq(handler.route), JsonRpcHandler.Config.default.cancellation(cancellation))
                settled <- Fiber.Promise.init[InboundPipeline.Settlement, Any]
                _       <- p.admit(request(3, "wait"), rec.env, (_, s) => settled.unsafe.completeDiscard(Result.succeed(s)))
                _       <- handler.entered.await
                outcome <- p.dispatch(cancelNotification(3), rec.env)
                _       <- handler.stopped.await
                done    <- handler.cancelledDone
                result  <- settled.get
            yield
                assert(outcome == InboundPipeline.Outcome.NoReply)
                assert(done)
                assert(result == InboundPipeline.Settlement.NoReply)
        }

        "a peer cancel notification for a protected method leaves the request running" in {
            for
                handler   <- parked("initialize")
                rec       <- recording
                p         <- pipeline(Seq(handler.route), JsonRpcHandler.Config.default.cancellation(cancellation))
                admission <- p.admit(request(3, "initialize"), rec.env, (_, _) => ())
                inflow = admitted(admission)
                _       <- handler.entered.await
                outcome <- p.dispatch(cancelNotification(3), rec.env)
                running <- Sync.defer(inflow.isRunning())
                done    <- handler.cancelledDone
                _       <- Sync.defer(inflow.abort())
                _       <- handler.stopped.await
            yield
                assert(outcome == InboundPipeline.Outcome.NoReply)
                assert(running, "a peer cancel stopped a protected request")
                assert(!done)
        }

        "a cancelled request under an expected reply lets its handler finish and replies" in {
            val policy = cancellation.copy(expectReplyForCancelledRequest = true)
            for
                entered <- Latch.init(1)
                route = JsonRpcRoute.request[Req, Resp]("finish") { (req, ctx) =>
                    entered.release.andThen(ctx.cancelled.get).andThen(Resp(req.n * 10))
                }
                rec     <- recording
                p       <- pipeline(Seq(route), JsonRpcHandler.Config.default.cancellation(policy))
                fiber   <- Fiber.initUnscoped(p.dispatch(request(2, "finish", 4), rec.env))
                _       <- entered.await
                _       <- Sync.defer(p.registry.get(JsonRpcId.Num(2L)).foreach(request => discard(request.cancel())))
                outcome <- fiber.get
            yield assert(outcome == InboundPipeline.Outcome.Reply(resultResponse(2, 40)))
            end for
        }

        "a cancel that lands after the reply settled suppresses it" in {
            for
                rec       <- recording
                p         <- pipeline(Seq(echo))
                settled   <- Fiber.Promise.init[InboundPipeline.Settlement, Any]
                admission <- p.admit(request(1, "echo", 8), rec.env, (_, s) => settled.unsafe.completeDiscard(Result.succeed(s)))
                inflow = admitted(admission)
                result    <- settled.get
                cancelled <- Sync.defer(inflow.cancel())
                written   <- Sync.defer(inflow.commit())
            yield
                assert(result == InboundPipeline.Settlement.Reply(resultResponse(1, 8)))
                assert(cancelled)
                assert(!written, "a reply cancelled before it was written must be dropped")
                assert(p.registry.size == 0)
        }

        "interrupting a dispatch caller aborts its request" in {
            for
                handler <- parked("wait")
                rec     <- recording
                p       <- pipeline(Seq(handler.route))
                caller  <- Fiber.initUnscoped(p.dispatch(request(1, "wait"), rec.env))
                _       <- handler.entered.await
                _       <- caller.interrupt
                _       <- handler.stopped.await
                done    <- handler.cancelledDone
                _       <- assertEventually(Sync.defer(p.registry.size == 0))
            yield assert(done, "ctx.cancelled was not completed when the caller went away")
        }

        "a closing registry ignores new envelopes and runs no handler" in {
            for
                ran <- AtomicBoolean.init(false)
                route = JsonRpcRoute.request[Req, Resp]("echo")((req, _) => ran.set(true).andThen(Resp(req.n)))
                rec     <- recording
                p       <- pipeline(Seq(route))
                _       <- Sync.defer(p.registry.closeAll())
                outcome <- p.dispatch(request(1, "echo"), rec.env)
                didRun  <- ran.get
            yield
                assert(outcome == InboundPipeline.Outcome.NoReply)
                assert(!didRun)
        }
    }

end InboundPipelineTest
