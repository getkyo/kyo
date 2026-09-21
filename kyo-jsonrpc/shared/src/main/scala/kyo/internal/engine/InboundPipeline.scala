package kyo.internal.engine

import kyo.*

/** Answers inbound JSON-RPC requests and notifications one envelope at a time, with no transport of its own.
  *
  * The pipeline is the single place that turns an inbound message into handler work and a reply. For each envelope it applies, in order, the
  * cancellation intercept (a cancel notification cancels its target request and goes no further), the message gate, the unknown-method policy,
  * the in-flight registration (refusing an id already in flight), the construction of the handler's [[JsonRpcRoute.Context]], and the fork of
  * the handler. When the handler finishes, its result is mapped to a response (success, `Halt`, `JsonRpcError`, panic) and the race between
  * that reply and a cancellation is settled by the request's state machine ([[InboundRequest]]).
  *
  * Two kinds of caller share it, so both get identical semantics:
  *   - the long-lived [[JsonRpcHandler]] engine calls [[admit]] from its reader fiber and writes settled replies through its writer, which makes
  *     the late cancel check ([[InboundRequest.commit]]) at the moment of writing;
  *   - a caller answering one envelope with no connection (an HTTP POST) calls [[dispatch]], which waits for the reply and commits it.
  *
  * The request environment supplies how notifications tied to the request are emitted and whether its peer can still receive them. Cancellation
  * control is on the admitted [[InboundRequest]], on the [[InboundRegistry]] by id, and, for [[dispatch]], by interrupting the calling fiber.
  *
  * @see [[InboundRegistry]]
  * @see [[JsonRpcRoute.Context]]
  */
final private[kyo] class InboundPipeline(
    routes: Map[String, JsonRpcRoute[?, ?, ?]],
    config: JsonRpcHandler.Config,
    val registry: InboundRegistry
):
    import InboundPipeline.*

    private val expectReplyWhenCancelled: Boolean = config.cancellation.exists(_.expectReplyForCancelledRequest)

    // Dispatch order for inbound routes. A peer's notifications carry state changes whose order matters (an LSP didChange edit, a
    // streamed delta before the message that ends the stream), so each notification handler runs after the one before it completes, and
    // a request handler starts after every notification admitted before it. Requests run concurrently with each other and hold up
    // nothing after them. The cell holds the completion of the latest admitted notification. It is swapped atomically rather than read
    // and then set, because `dispatch` admits from whichever fiber calls it, and two admissions may race where a reader fiber's never do.
    // Handlers wait on a masked view of it: waiting on a fiber links the waiter's interrupt to it, and a cancelled request must not
    // interrupt an earlier notification's handler.
    // Unsafe: built with the pipeline and written from `admit`, which is Sync-only and has no Async to wait in.
    private val notificationTail = AtomicRef.Unsafe.init[Fiber[Unit, Any]](Fiber.unit)(using AllowUnsafe.embrace.danger)

    /** Admits one inbound envelope. `Sync`-only and never parks, so a reader fiber can call it for every frame and see the registration take
      * effect before it reads the next one.
      *
      * `onSettle` is invoked exactly once for every `Dispatched` admission, from the handler fiber's completion, with the reply decision. A
      * `Reply` must end in [[InboundRequest.commit]] (when writing it) or [[InboundRequest.abandon]] (when it cannot be written): until then
      * the request stays registered, and a close waits for it in [[InboundRegistry.replies]] until [[InboundRequest.handOff]] or either end.
      */
    def admit(
        envelope: JsonRpcRequest | JsonRpcNotification,
        env: Environment,
        onSettle: (InboundRequest, Settlement) => Unit
    )(using Frame): Admission < Sync =
        Sync.Unsafe.defer(registry.isClosing()).map { closing =>
            if closing then Admission.Ignored
            else
                envelope match
                    case notification: JsonRpcNotification =>
                        config.cancellation match
                            case Present(policy) if notification.method == policy.cancelMethod =>
                                cancelFromPeer(notification, policy).andThen(Admission.Ignored)
                            case _ =>
                                gated(notification) {
                                    case JsonRpcMessageGate.Decision.Allow     => admitNotification(notification, env, onSettle)
                                    case JsonRpcMessageGate.Decision.Reject(_) =>
                                        // A notification has no id to reply to: log and drop.
                                        Log.warn(s"kyo-jsonrpc: gate rejected notification method '${notification.method}'")
                                            .andThen(Admission.Ignored)
                                    case JsonRpcMessageGate.Decision.Drop => Admission.Ignored
                                }
                    case request: JsonRpcRequest =>
                        gated(request) {
                            case JsonRpcMessageGate.Decision.Allow            => admitRequest(request, env, onSettle)
                            case JsonRpcMessageGate.Decision.Reject(response) => Admission.Respond(response)
                            case JsonRpcMessageGate.Decision.Drop             => Admission.Ignored
                        }
        }
    end admit

    /** Answers one inbound envelope and returns what to send back.
      *
      * Interrupting the calling fiber while the handler runs aborts the request: its `ctx.cancelled` completes, the handler is interrupted,
      * and no reply is produced. This is how a caller maps "the peer went away" (a closed HTTP stream) to cancellation.
      */
    def dispatch(envelope: JsonRpcRequest | JsonRpcNotification, env: Environment)(using Frame): Outcome < Async =
        Fiber.Promise.init[Settlement, Any].map { settled =>
            admit(
                envelope,
                env,
                // Unsafe: completes the settlement promise from the handler fiber's completion callback.
                (_, settlement) => settled.unsafe.completeDiscard(Result.succeed(settlement))(using AllowUnsafe.embrace.danger)
            ).map {
                case Admission.Ignored            => Outcome.NoReply
                case Admission.Respond(response)  => Outcome.Reply(response)
                case Admission.Violation(reply)   => Outcome.Violation(reply)
                case Admission.Dispatched(inflow) =>
                    // Unsafe: request transitions from the ensure finalizer. Both are no-ops once the reply was committed, so they only act when
                    // the caller is interrupted: a running request is aborted, and a reply settled but not yet committed is abandoned.
                    Sync.ensure(Sync.Unsafe.defer(if !inflow.abort() then inflow.abandon())) {
                        settled.get.map {
                            case Settlement.Reply(response) =>
                                // Unsafe: the late cancel check at the moment the reply is handed to the caller.
                                Sync.Unsafe.defer(if inflow.commit() then Outcome.Reply(response) else Outcome.NoReply)
                            case Settlement.NoReply => Outcome.NoReply
                        }
                    }
            }
        }
    end dispatch

    private def gated(envelope: JsonRpcEnvelope)(decide: JsonRpcMessageGate.Decision => Admission < Sync)(using Frame): Admission < Sync =
        config.gate match
            case Absent        => decide(JsonRpcMessageGate.Decision.Allow)
            case Present(gate) => gate.beforeDispatch(envelope).map(decide)

    private def admitRequest(
        request: JsonRpcRequest,
        env: Environment,
        onSettle: (InboundRequest, Settlement) => Unit
    )(using Frame): Admission < Sync =
        // stdlib Map.get() returns scala.Option; match arms are interop at protocol dispatch boundary
        routes.get(request.method) match
            case Some(route) =>
                // Unsafe: registration must complete before admit returns, so a cancel or a progress report racing the fork sees the request.
                Sync.Unsafe.defer {
                    registry.registerRequest(request.id, request.method, request.extras, expectReplyWhenCancelled) match
                        case InboundRegistry.Registration.Duplicate =>
                            Kyo.lift(Admission.Respond(JsonRpcResponse(request.id, Absent, Present(duplicateId(request.id)), Absent)))
                        case InboundRegistry.Registration.Closing =>
                            Kyo.lift(Admission.Ignored)
                        case InboundRegistry.Registration.Registered(inflow) =>
                            val ctx = context(inflow, request.params, env, withProgress = true)
                            start(inflow, route, request.params, ctx, onSettle).andThen(Admission.Dispatched(inflow))
                }
            case None =>
                config.unknownMethod.onUnknownRequest match
                    case JsonRpcUnknownMethodPolicy.UnknownAction.ReplyMethodNotFound =>
                        Admission.Respond(methodNotFound(request))
                    case JsonRpcUnknownMethodPolicy.UnknownAction.Drop =>
                        Admission.Ignored
                    case JsonRpcUnknownMethodPolicy.UnknownAction.Reject =>
                        // Answer first so the caller is not left waiting, then the connection closes.
                        Admission.Violation(Present(methodNotFound(request)))
        end match
    end admitRequest

    private def admitNotification(
        notification: JsonRpcNotification,
        env: Environment,
        onSettle: (InboundRequest, Settlement) => Unit
    )(using Frame): Admission < Sync =
        // stdlib Map.get() returns scala.Option; match arms are interop at protocol dispatch boundary
        routes.get(notification.method) match
            case Some(route) =>
                // Unsafe: registration for the notification handler, mirroring a request, so a close can stop it.
                Sync.Unsafe.defer {
                    registry.registerNotification(notification.method, notification.extras) match
                        case Absent          => Kyo.lift(Admission.Ignored)
                        case Present(inflow) =>
                            val ctx = context(inflow, notification.params, env, withProgress = false)
                            start(inflow, route, notification.params, ctx, onSettle).andThen(Admission.Dispatched(inflow))
                }
            case None =>
                if config.unknownMethod.ignoreUnknownNotification(notification.method) then Admission.Ignored
                else
                    config.unknownMethod.onUnknownNotification match
                        case JsonRpcUnknownMethodPolicy.UnknownAction.Drop                => Admission.Ignored
                        case JsonRpcUnknownMethodPolicy.UnknownAction.ReplyMethodNotFound => Admission.Ignored
                        case JsonRpcUnknownMethodPolicy.UnknownAction.Reject              =>
                            Log.warn(s"kyo-jsonrpc: unknown notification method '${notification.method}' rejected")
                                .andThen(Admission.Violation(Absent))
        end match
    end admitNotification

    // Forks the handler from the admitting fiber, so it inherits that fiber's context, then publishes the fiber to the request and wires the
    // settlement to its completion.
    private def start(
        inflow: InboundRequest,
        route: JsonRpcRoute[?, ?, ?],
        params: Maybe[Structure.Value],
        ctx: JsonRpcRoute.Context,
        onSettle: (InboundRequest, Settlement) => Unit
    )(using Frame): Unit < Sync =
        val handle = InboundPipeline.answering.let(inflow.id)(route.handle(params.getOrElse(Structure.Value.Null), ctx))
        // Unsafe: the tail is read or swapped here, inside `admit`, so the order is the order of admission.
        Sync.Unsafe.defer {
            inflow.id match
                case Present(_) =>
                    // A request: starts once every notification admitted before it has been handled.
                    val preceding = notificationTail.get().unsafe.mask().safe
                    (preceding.getResult.andThen(handle), Absent)
                case Absent =>
                    // A notification: runs once the previous one has completed, however it completed, so a failed handler does not
                    // stop the ones after it. Its own completion becomes the tail before it is forked.
                    val done     = Promise.Unsafe.init[Unit, Any]()
                    val previous = notificationTail.getAndSet(done.safe).unsafe.mask().safe
                    (previous.getResult.andThen(reported(inflow.method, handle)), Present(done))
        }.map { (ordered, completion) =>
            Fiber.initUnscoped(ordered).map { fiber =>
                // Unsafe: attach and the completion hook act on the fiber from outside it; no safe equivalent in Fiber public API.
                Sync.Unsafe.defer {
                    inflow.attach(fiber.unsafe)
                    fiber.unsafe.onComplete { result =>
                        // Completed from the hook rather than inside the fiber, so an interrupt that lands before the handler starts
                        // still releases whatever waits behind this notification.
                        completion.foreach(_.completeUnitDiscard())
                        onSettle(inflow, inflow.settle(result))
                    }
                }
            }
        }
    end start

    // A notification has no reply to carry a failure back on, so a handler that did not complete is reported here, which is the only
    // place it can surface. The outcome is passed through unchanged for the request's own settlement.
    private def reported(
        method: String,
        handle: Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])
    )(using Frame): Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt]) =
        Abort.run[JsonRpcError | JsonRpcResponse.Halt](handle).map { outcome =>
            val report =
                if outcome.isSuccess then Kyo.unit
                else Log.warn(s"kyo-jsonrpc: the handler for notification '$method' did not complete: $outcome")
            report.andThen(Abort.get(outcome))
        }

    private def context(
        inflow: InboundRequest,
        params: Maybe[Structure.Value],
        env: Environment,
        withProgress: Boolean
    )(using Frame, AllowUnsafe): JsonRpcRoute.Context =
        // Unsafe: the request's own state read; request-scoped output stops once it settles or is cancelled.
        val isLive: Boolean < Sync =
            Sync.Unsafe.defer(inflow.isRunning()).map(running => if running then env.isLive else false)
        val progressSink =
            if withProgress then ProgressEngine.buildProgressSink(params, inflow.extras, config.progress, env.emit, isLive)
            else Absent
        val notificationSink: JsonRpcNotification => Unit < (Async & Abort[Closed]) =
            notification => isLive.map(live => if live then Abort.run[Closed](env.emit(notification)).unit else Kyo.unit)
        new JsonRpcRoute.Context(
            inflow.cancelled.safe,
            inflow.id,
            inflow.extras,
            JsonRpcRoute.Context.metaOf(params),
            progressSink,
            Present(notificationSink)
        )
    end context

    private def cancelFromPeer(notification: JsonRpcNotification, policy: JsonRpcCancellationPolicy)(using Frame): Unit < Sync =
        CancellationEngine.extractCancelId(policy, notification.params).map {
            case Absent      => Log.warn("kyo-jsonrpc: inbound cancel notification missing id, dropping")
            case Present(id) =>
                registry.get(id) match
                    case Absent =>
                        Log.warn(s"kyo-jsonrpc: inbound cancel for unknown id $id, dropping")
                    case Present(inflow) if policy.protectedMethods.contains(inflow.method) =>
                        Log.warn(s"kyo-jsonrpc: inbound cancel refused for protected method ${inflow.method}, no-op")
                    case Present(inflow) =>
                        // Unsafe: cancellation completes ctx.cancelled and may interrupt the handler from the reader fiber.
                        Sync.Unsafe.defer(discard(inflow.cancel()))
        }

    private def methodNotFound(request: JsonRpcRequest)(using Frame): JsonRpcResponse =
        JsonRpcResponse(
            request.id,
            Absent,
            Present(JsonRpcMethodNotFoundError(request.method, Chunk.from(routes.keys))),
            Absent
        )

    private def duplicateId(id: JsonRpcId)(using Frame): JsonRpcError =
        JsonRpcInvalidRequestError(Structure.Value.Str(s"request id $id is already in flight"), Chunk.empty)

end InboundPipeline

private[kyo] object InboundPipeline:

    /** The inbound request the running handler answers, bound for as long as its handler runs.
      *
      * What that handler sends meanwhile, from any fiber it starts, is read against it when it is queued for the writer, so
      * the transport learns which request each such message belongs to ([[kyo.JsonRpcTransport.send]]).
      */
    val answering: Local[Maybe[JsonRpcId]] = Local.init(Absent)

    /** Builds a pipeline over `routes` with a fresh, empty registry. */
    def init(routes: Seq[JsonRpcRoute[?, ?, ?]], config: JsonRpcHandler.Config)(using AllowUnsafe): InboundPipeline =
        new InboundPipeline(routes.map(route => route.name -> route).toMap, config, InboundRegistry.init())

    /** What a request's handler may use to reach the request's peer while it runs.
      *
      * @param emit
      *   delivers a notification tied to the request (progress, logs); a `Closed` means the peer can no longer receive it
      * @param isLive
      *   whether the peer can still receive request-scoped notifications; consulted before each one
      */
    final class Environment(
        val emit: JsonRpcNotification => Unit < (Async & Abort[Closed]),
        val isLive: Boolean < Sync
    )

    /** The result of admitting one inbound envelope. */
    enum Admission derives CanEqual:
        /** Answered without running a handler: a gate rejection, an unknown method, or an id already in flight. */
        case Respond(response: JsonRpcResponse)

        /** A handler is running; its reply decision arrives through `onSettle`. */
        case Dispatched(request: InboundRequest)

        /** Nothing to send and nothing running: a gate drop, a cancel notification, an ignored notification, or a closing registry. */
        case Ignored

        /** The unknown-method policy classified the message as a protocol violation: send the reply when present, then close the connection. */
        case Violation(response: Maybe[JsonRpcResponse])
    end Admission

    /** What a settled handler asks to have written. */
    enum Settlement derives CanEqual:
        /** Write the reply unless a cancellation suppresses it before it is written; [[InboundRequest.commit]] decides at the moment of
          * writing, and [[InboundRequest.abandon]] ends it when it cannot be handed to its writer. Under a policy that expects a reply for
          * cancelled requests no cancellation suppresses it, including the cancellation that preceded the settlement.
          */
        case Reply(response: JsonRpcResponse)

        /** Nothing to write. */
        case NoReply
    end Settlement

    /** The answer [[InboundPipeline.dispatch]] returns for one envelope. */
    enum Outcome derives CanEqual:
        case Reply(response: JsonRpcResponse)
        case NoReply
        case Violation(response: Maybe[JsonRpcResponse])
    end Outcome

end InboundPipeline
