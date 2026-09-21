package kyo.internal.engine

// ConcurrentHashMap shared concurrent map; cross-platform via JS/Native JDK shim
import java.util.concurrent.ConcurrentHashMap
import kyo.*
import scala.annotation.tailrec

/** One inbound request or notification whose handler has been admitted, from admission until its reply is written or abandoned.
  *
  * The request owns its own state cell, created before the handler is forked, so a request-scoped sink can check liveness and a canceller can
  * act without racing the fork:
  *
  * {{{
  * Running  --settle--> Replying --commit--> Done (write the reply)
  * Running  --cancel--> Cancelled --settle--> Replying (only when the policy expects a reply for cancelled requests) or Done
  * Replying --cancel--> Suppressed --commit--> Done (reply dropped; a cancel cannot suppress a reply the policy expects)
  * Running | Cancelled --abort--> Aborted --settle--> Done (never a reply; used by close and by an abandoned dispatch)
  * Replying | Suppressed --abandon--> Done (the reply could not be handed to its writer)
  * }}}
  *
  * A notification has no id and never replies: it settles straight from `Running` to `Done`.
  *
  * A close aborts running requests and counts, in the registry's `replies` tracker, every settled reply not yet handed to its writer. The
  * count ends when the reply is handed to its writer ([[handOff]]) or ended (`commit`, `abandon`). The close waits for the count, so every
  * reply produced before the close is with its writer before the writer stops accepting output. Settle and abort decide on the same state
  * cell, and the count and hand-off on the same count cell, so no reply falls between them.
  */
final private[kyo] class InboundRequest private[engine] (
    val id: Maybe[JsonRpcId],
    val method: String,
    val extras: Maybe[Structure.Value],
    // Unsafe: completed from whichever fiber cancels the request (peer cancel, local cancel, close); no safe equivalent outside a fiber.
    private[engine] val cancelled: Promise.Unsafe[Unit, Sync],
    private[engine] val expectReplyWhenCancelled: Boolean,
    private[engine] val seq: Long,
    registry: InboundRegistry,
    state: AtomicRef.Unsafe[InboundRequest.State],
    handler: AtomicRef.Unsafe[Maybe[Fiber.Unsafe[Structure.Value, Abort[JsonRpcError | JsonRpcResponse.Halt]]]],
    interruptRequested: AtomicBoolean.Unsafe,
    // Whether a close counted this request's reply in `registry.replies`: InboundRequest.NotCounted, Counted, or Released (handed off).
    replyCount: AtomicInt.Unsafe
):
    import InboundRequest.State

    /** Whether the handler is still running and its request has not been cancelled. */
    def isRunning()(using AllowUnsafe): Boolean = state.get() == State.Running

    /** Whether the handler has settled with a reply that is waiting to be written. */
    def isReplyPending()(using AllowUnsafe): Boolean =
        val current = state.get()
        current == State.Replying || current == State.Suppressed

    /** Publishes the forked handler fiber, interrupting it at once if a cancellation that interrupts landed before the fork. */
    private[engine] def attach(fiber: Fiber.Unsafe[Structure.Value, Abort[JsonRpcError | JsonRpcResponse.Halt]])(using
        AllowUnsafe,
        Frame
    ): Unit =
        handler.set(Present(fiber))
        // Read after publishing: a concurrent interrupt request either sees the fiber or is seen here.
        if interruptRequested.get() then interruptHandler()
    end attach

    /** Cancels the request on behalf of its peer or the local owner: completes `ctx.cancelled`, interrupts the handler unless the policy
      * expects a reply for cancelled requests, and suppresses a reply that is already queued on the same rule.
      *
      * @return
      *   true when the cancellation took effect
      */
    def cancel()(using AllowUnsafe, Frame): Boolean =
        @tailrec def loop(): Boolean =
            state.get() match
                case State.Running =>
                    if !state.compareAndSet(State.Running, State.Cancelled) then loop()
                    else
                        cancelled.completeUnitDiscard()
                        if !expectReplyWhenCancelled then requestInterrupt()
                        true
                case State.Replying =>
                    if expectReplyWhenCancelled then false
                    else if !state.compareAndSet(State.Replying, State.Suppressed) then loop()
                    else true
                case _ => false
        loop()
    end cancel

    /** Stops the request with no reply regardless of policy: completes `ctx.cancelled` and interrupts the handler. A reply that is already
      * queued is left to be written.
      *
      * @return
      *   true when the request was still running (or cancelled but still running) and is now aborted
      */
    def abort()(using AllowUnsafe, Frame): Boolean =
        @tailrec def loop(): Boolean =
            val current = state.get()
            current match
                case State.Running | State.Cancelled =>
                    if !state.compareAndSet(current, State.Aborted) then loop()
                    else
                        cancelled.completeUnitDiscard()
                        requestInterrupt()
                        true
                case _ => false
            end match
        end loop
        loop()
    end abort

    /** Settles the request with its handler's result and decides what, if anything, to reply. */
    private[engine] def settle(
        result: Result[JsonRpcError | JsonRpcResponse.Halt, Structure.Value < Any]
    )(using AllowUnsafe, Frame): InboundPipeline.Settlement =
        @tailrec def loop(): InboundPipeline.Settlement =
            val current = state.get()
            current match
                case State.Running | State.Cancelled =>
                    id match
                        case Present(requestId) if current == State.Running || expectReplyWhenCancelled =>
                            if !state.compareAndSet(current, State.Replying) then loop()
                            else InboundPipeline.Settlement.Reply(responseFor(requestId, result))
                        case _ =>
                            if !state.compareAndSet(current, State.Done) then loop()
                            else
                                registry.remove(this)
                                InboundPipeline.Settlement.NoReply
                case State.Aborted =>
                    if !state.compareAndSet(State.Aborted, State.Done) then loop()
                    else
                        registry.remove(this)
                        InboundPipeline.Settlement.NoReply
                case _ => InboundPipeline.Settlement.NoReply
            end match
        end loop
        loop()
    end settle

    /** Records that the settled reply is now with its writer (in the writer's queue). Ends its count in `registry.replies` when a close counted
      * it, and otherwise keeps a later close from counting it. Idempotent, and a no-op for a request that has not settled with a reply.
      */
    private[engine] def handOff()(using AllowUnsafe): Unit =
        @tailrec def loop(): Unit =
            val current = state.get()
            if current == State.Running || current == State.Cancelled then ()
            else
                replyCount.get() match
                    case InboundRequest.NotCounted =>
                        if !replyCount.compareAndSet(InboundRequest.NotCounted, InboundRequest.Released) then loop()
                    case InboundRequest.Counted =>
                        if !replyCount.compareAndSet(InboundRequest.Counted, InboundRequest.Released) then loop()
                        else registry.replies.release()
                    case _ => ()
            end if
        end loop
        loop()
    end handOff

    /** Counts a reply that is pending and not yet handed to its writer in `registry.replies`, for a close to wait on. */
    private[engine] def countPendingReply()(using AllowUnsafe): Unit =
        if isReplyPending() then
            // Acquired before the transition that publishes the count, so a hand-off that sees Counted always has a unit to release.
            registry.replies.acquire()
            if !replyCount.compareAndSet(InboundRequest.NotCounted, InboundRequest.Counted) then registry.replies.release()
    end countPendingReply

    /** The late cancel check, made at the moment a settled reply is written: removes the request and answers whether to write the reply. */
    def commit()(using AllowUnsafe): Boolean = finishReply(written = true)

    /** Drops a settled reply that could not be handed to its writer, so the request does not stay registered. A no-op unless a reply is
      * pending.
      */
    private[engine] def abandon()(using AllowUnsafe): Unit = discard(finishReply(written = false))

    // Ends a pending reply: true only when it was written and no cancellation suppressed it.
    private def finishReply(written: Boolean)(using AllowUnsafe): Boolean =
        @tailrec def loop(): Boolean =
            val current = state.get()
            current match
                case State.Replying | State.Suppressed =>
                    if !state.compareAndSet(current, State.Done) then loop()
                    else
                        registry.remove(this)
                        handOff()
                        written && current == State.Replying
                case _ => false
            end match
        end loop
        loop()
    end finishReply

    private def requestInterrupt()(using AllowUnsafe, Frame): Unit =
        interruptRequested.set(true)
        interruptHandler()

    private def interruptHandler()(using AllowUnsafe, Frame): Unit =
        handler.get().foreach(_.interruptDiscard(Result.Panic(Interrupted(summon[Frame]))))

    private def responseFor(
        requestId: JsonRpcId,
        result: Result[JsonRpcError | JsonRpcResponse.Halt, Structure.Value < Any]
    )(using Frame): JsonRpcResponse =
        result match
            case Result.Success(value)                      => JsonRpcResponse(requestId, Present(value.eval), Absent, extras)
            case Result.Failure(halt: JsonRpcResponse.Halt) => halt.response
            case Result.Failure(error: JsonRpcError)        => JsonRpcResponse(requestId, Absent, Present(error), extras)
            case Result.Panic(t) => JsonRpcResponse(requestId, Absent, Present(JsonRpcHandlerPanicError(method, t)), extras)
    end responseFor

end InboundRequest

private[kyo] object InboundRequest:

    private[engine] enum State derives CanEqual:
        case Running, Cancelled, Replying, Suppressed, Aborted, Done

    // Values of a request's reply count: not counted, counted in `replies` by a close, handed off (released or never to be counted).
    private[engine] inline val NotCounted = 0
    private[engine] inline val Counted    = 1
    private[engine] inline val Released   = 2
end InboundRequest

/** The in-flight table of inbound work for one scope of ids: a connection, a session, or a single dispatched request.
  *
  * Requests are indexed by id so a cancellation by id can find them; notification handlers are indexed by a sequence number so a close can
  * stop them. Once `closeAll` runs the registry admits nothing new.
  */
final private[kyo] class InboundRegistry private (
    requests: ConcurrentHashMap[JsonRpcId, InboundRequest],
    notifications: ConcurrentHashMap[java.lang.Long, InboundRequest],
    nextSeq: AtomicLong.Unsafe,
    closing: AtomicBoolean.Unsafe,
    /** Replies that `closeAll` found settled but not yet handed to their writer, until each is handed off, written, or abandoned. */
    val replies: WorkTracker
):

    /** Creates and registers a request. A request whose id is already in flight is refused as a duplicate, and nothing is registered once
      * the registry is closing.
      */
    private[engine] def registerRequest(
        id: JsonRpcId,
        method: String,
        extras: Maybe[Structure.Value],
        expectReplyWhenCancelled: Boolean
    )(using AllowUnsafe): InboundRegistry.Registration =
        val request = newRequest(Present(id), method, extras, expectReplyWhenCancelled, seq = 0L)
        if requests.putIfAbsent(id, request) != null then InboundRegistry.Registration.Duplicate
        else if closing.get() then
            // A close that began after admission checked the flag must not miss this request: undo the registration instead. A close that
            // sets the flag after this read iterates the map after the put, so it sees the request.
            discard(requests.remove(id, request))
            InboundRegistry.Registration.Closing
        else InboundRegistry.Registration.Registered(request)
        end if
    end registerRequest

    /** Creates and registers a notification handler, or returns Absent when the registry is closing. */
    private[engine] def registerNotification(method: String, extras: Maybe[Structure.Value])(using AllowUnsafe): Maybe[InboundRequest] =
        val request = newRequest(Absent, method, extras, expectReplyWhenCancelled = false, seq = nextSeq.incrementAndGet())
        discard(notifications.put(request.seq, request))
        if closing.get() then
            discard(notifications.remove(request.seq, request))
            Absent
        else Present(request)
        end if
    end registerNotification

    /** Whether a request with `id` is in flight. */
    private[engine] def contains(id: JsonRpcId): Boolean = requests.containsKey(id)

    /** The in-flight request with `id`, if any. */
    def get(id: JsonRpcId): Maybe[InboundRequest] = Maybe(requests.get(id))

    /** Stops admitting inbound work and aborts every running request and notification handler. Replies already settled are left to be written:
      * each one not yet handed to its writer is counted in `replies`, which completes its idle wait once all of them have been.
      */
    def closeAll()(using AllowUnsafe, Frame): Unit =
        closing.set(true)
        // abort returns false only once the request has left Running and Cancelled, so a settle racing it has either lost (no reply) or
        // already published its reply, which is then counted here.
        requests.forEach((_, request) => if !request.abort() then request.countPendingReply())
        notifications.forEach((_, request) => discard(request.abort()))
    end closeAll

    /** Whether `closeAll` has run. */
    def isClosing()(using AllowUnsafe): Boolean = closing.get()

    /** Number of registered requests and notification handlers. */
    def size: Int = requests.size + notifications.size

    /** The registered requests (not notification handlers), as a point-in-time copy. */
    def requestsSnapshot: Chunk[InboundRequest] =
        val builder = Chunk.newBuilder[InboundRequest]
        requests.forEach((_, request) => discard(builder += request))
        builder.result()
    end requestsSnapshot

    private[engine] def remove(request: InboundRequest): Unit =
        request.id match
            case Present(id) => discard(requests.remove(id, request))
            case Absent      => discard(notifications.remove(request.seq, request))

    private def newRequest(
        id: Maybe[JsonRpcId],
        method: String,
        extras: Maybe[Structure.Value],
        expectReplyWhenCancelled: Boolean,
        seq: Long
    )(using AllowUnsafe): InboundRequest =
        new InboundRequest(
            id,
            method,
            extras,
            Promise.Unsafe.init[Unit, Sync](),
            expectReplyWhenCancelled,
            seq,
            this,
            AtomicRef.Unsafe.init[InboundRequest.State](InboundRequest.State.Running),
            AtomicRef.Unsafe.init[Maybe[Fiber.Unsafe[Structure.Value, Abort[JsonRpcError | JsonRpcResponse.Halt]]]](Absent),
            AtomicBoolean.Unsafe.init(false),
            AtomicInt.Unsafe.init(InboundRequest.NotCounted)
        )

end InboundRegistry

private[kyo] object InboundRegistry:

    /** The outcome of registering a request. */
    private[engine] enum Registration derives CanEqual:
        case Registered(request: InboundRequest)
        case Duplicate
        case Closing
    end Registration

    /** An empty registry. */
    def init()(using AllowUnsafe): InboundRegistry =
        new InboundRegistry(
            new ConcurrentHashMap[JsonRpcId, InboundRequest](),
            new ConcurrentHashMap[java.lang.Long, InboundRequest](),
            AtomicLong.Unsafe.init(0L),
            AtomicBoolean.Unsafe.init(false),
            WorkTracker.init()
        )
end InboundRegistry
