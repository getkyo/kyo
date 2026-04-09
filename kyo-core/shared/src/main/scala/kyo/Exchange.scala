package kyo

import java.util.concurrent.ConcurrentHashMap

/** A request/response multiplexer over a shared bidirectional connection.
  *
  * WARNING: This is an advanced primitive intended for building protocol clients (HTTP/2, WebSocket, JSON-RPC, etc.). Application code
  * should typically use higher-level abstractions.
  *
  * Protocols like HTTP/2 and WebSocket multiplex many in-flight requests over a single connection. Each outgoing request is tagged with a
  * unique ID, and incoming responses carry that ID so they can be routed back to the right caller. Without Exchange, every protocol client
  * must re-implement this bookkeeping: a concurrent map of pending promises keyed by ID, background fiber(s) to drain the incoming stream
  * and dispatch responses, cleanup logic when the connection breaks or is closed, and careful handling of races between new requests and
  * shutdown. Exchange encapsulates all of that into a single reusable primitive.
  *
  * You call `apply(request)`; Exchange assigns an ID, encodes the request, sends it, and suspends until the matching response arrives. A
  * background reader fiber drains the `receive` stream and routes each decoded message by ID. Callers never see IDs.
  *
  * Callbacks supplied at construction:
  *   - `nextId`: produces a fresh request ID, re-evaluated on every `apply` call. Callers control the strategy (sequential counter,
  *     odd-only for HTTP/2, UUIDs, etc.). `Sync` — may use mutable counters but must not park.
  *   - `encode`: stamps the ID into the request and serializes to the wire format. `Sync` — serialization may mutate shared state (e.g.
  *     HPACK dynamic tables) but must not park.
  *   - `send`: transmits a wire message over the connection. `Async & Abort[E]` — may park waiting for write-buffer space, and may fail
  *     with a typed transport error. A send failure closes the Exchange and fails all pending requests with `E`.
  *   - `receive`: stream of incoming wire messages. `Async & Abort[E]` — I/O stream from the connection. Stream end or `Abort[E]` closes
  *     the Exchange.
  *   - `decode`: classifies each incoming message as `Message.Response` (routed by ID), `Message.Push` (buffered as an unsolicited event),
  *     or `Message.Skip` (ignored). `Sync` only — this is critical: `decode` runs on the single reader fiber that drives all response
  *     dispatch. If `decode` could park (Async), every in-flight request would stall until it resumes. For protocols with no unsolicited
  *     messages, set `Event = Nothing` and return only `Message.Response` or `Message.Skip`.
  *
  * Error semantics: a transport error (`E`) permanently closes the Exchange and fails all pending requests with that error; an orderly
  * shutdown (explicit `close()` or clean stream end) fails them with `Closed`. Await `awaitDone` to discover which occurred — it suspends
  * until termination then raises `Abort[E]` or `Abort[Closed]`, enabling reconnection loops.
  *
  * Unsolicited events: consume events via `events`; the reader fiber parks when the buffer is full, providing backpressure. Use
  * `Event = Nothing` when the protocol has no unsolicited messages.
  *
  * Factory methods:
  *   - `init(encode, send, receive, decode)`: sequential `Int` IDs, scoped. The simplest entry point.
  *   - `init(nextId, encode, send, receive, decode, eventCapacity)`: custom ID type, with unsolicited events, scoped.
  *   - `initUnscoped(...)`: same variants as above but without Scope management — caller must call `close()` manually.
  *
  * `Id` and `Wire` appear only in the factory signatures and are erased from the `Exchange` opaque type.
  *
  * @tparam Req
  *   The request type
  * @tparam Resp
  *   The response type
  * @tparam Event
  *   The type of unsolicited events; use `Nothing` when the protocol has no unsolicited messages
  * @tparam E
  *   The transport-level error type; raised on all pending requests when the connection breaks
  */
opaque type Exchange[Req, Resp, Event, E] = Exchange.Unsafe[?, ?, Req, Resp, Event, E]

object Exchange:

    /** Classifies an incoming wire message. */
    enum Message[+Id, +Resp, +Event]:
        /** A response that matches a pending request. */
        case Response(id: Id, value: Resp)

        /** An unsolicited event message. */
        case Push(value: Event)

        /** A message to be silently ignored. */
        case Skip
    end Message

    // ── Init (scoped, sequential Int IDs) ───────────────────────────────────

    /** Creates a scoped Exchange using automatically assigned sequential `Int` IDs.
      *
      * IDs start at 0 and increment by 1 per request. No `nextId` callback is required. For protocols with no unsolicited messages, set
      * `Event = Nothing` and return only `Message.Response` or `Message.Skip` from `decode`.
      *
      * @param encode
      *   Encodes a request with its auto-assigned `Int` ID into a wire message. Called once per `apply`.
      * @param send
      *   Sends a wire message over the connection. Fails with `Abort[E]` on transport error, which closes the Exchange.
      * @param receive
      *   Incoming wire messages from the connection. When the stream ends or fails, all pending requests are failed.
      * @param decode
      *   Classifies each incoming wire message as a Response, Push event, or Skip. Must not park (called from the reader fiber).
      */
    def init[Req, Resp, Wire, Event, E](
        encode: (Int, Req) => Wire < Sync,
        send: Wire => Unit < (Async & Abort[E]),
        receive: Stream[Wire, Async & Abort[E]],
        decode: Wire => Exchange.Message[Int, Resp, Event] < Sync
    )(using Frame, ConcreteTag[E], Tag[Emit[Chunk[Wire]]]): Exchange[Req, Resp, Event, E] < (Sync & Scope) =
        Scope.acquireRelease(initUnscoped(encode, send, receive, decode))(ex => Sync.Unsafe.defer(ex.close()))

    // ── Init (scoped) ────────────────────────────────────────────────────────

    /** Creates a scoped Exchange with unsolicited event support.
      *
      * For protocols with no unsolicited messages, set `Event = Nothing` and return only `Message.Response` or `Message.Skip` from
      * `decode`.
      *
      * @param nextId
      *   Produces the next request ID (re-evaluated per `apply` call). Must yield unique IDs for the lifetime of this Exchange.
      * @param encode
      *   Encodes a request with its assigned ID into a wire message. Called once per `apply`.
      * @param send
      *   Sends a wire message over the connection. Fails with `Abort[E]` on transport error, which closes the Exchange.
      * @param receive
      *   Incoming wire messages from the connection. When the stream ends or fails, all pending requests are failed.
      * @param decode
      *   Classifies each incoming wire message as a Response, Push event, or Skip. Must not park (called from the reader fiber).
      * @param eventCapacity
      *   Buffer size for the internal event channel. When full, the reader fiber parks (backpressure). Default: 16.
      */
    def init[Id, Req, Resp, Wire, Event, E](
        nextId: => Id < Sync,
        encode: (Id, Req) => Wire < Sync,
        send: Wire => Unit < (Async & Abort[E]),
        receive: Stream[Wire, Async & Abort[E]],
        decode: Wire => Exchange.Message[Id, Resp, Event] < Sync,
        eventCapacity: Int = 16
    )(using Frame, ConcreteTag[E], Tag[Emit[Chunk[Wire]]]): Exchange[Req, Resp, Event, E] < (Sync & Scope) =
        Scope.acquireRelease(initUnscoped(nextId, encode, send, receive, decode, eventCapacity))(ex => Sync.Unsafe.defer(ex.close()))

    // ── InitUnscoped (sequential Int IDs) ────────────────────────────────────

    /** Creates an unscoped Exchange using automatically assigned sequential `Int` IDs. Caller is responsible for calling `close`.
      *
      * IDs start at 0 and increment by 1 per request. No `nextId` callback is required. For protocols with no unsolicited messages, set
      * `Event = Nothing` and return only `Message.Response` or `Message.Skip` from `decode`.
      *
      * @param encode
      *   Encodes a request with its auto-assigned `Int` ID into a wire message. Called once per `apply`.
      * @param send
      *   Sends a wire message over the connection. Fails with `Abort[E]` on transport error, which closes the Exchange.
      * @param receive
      *   Incoming wire messages from the connection. When the stream ends or fails, all pending requests are failed.
      * @param decode
      *   Classifies each incoming wire message as a Response, Push event, or Skip. Must not park (called from the reader fiber).
      */
    def initUnscoped[Req, Resp, Wire, Event, E](
        encode: (Int, Req) => Wire < Sync,
        send: Wire => Unit < (Async & Abort[E]),
        receive: Stream[Wire, Async & Abort[E]],
        decode: Wire => Exchange.Message[Int, Resp, Event] < Sync
    )(using Frame, ConcreteTag[E], Tag[Emit[Chunk[Wire]]]): Exchange[Req, Resp, Event, E] < Sync =
        Sync.Unsafe.defer {
            val counter = AtomicInt.Unsafe.init(0)
            initUnscoped[Int, Req, Resp, Wire, Event, E](
                nextId = Sync.Unsafe.defer(counter.getAndIncrement()),
                encode = encode,
                send = send,
                receive = receive,
                decode = decode
            )
        }

    // ── InitUnscoped ──────────────────────────────────────────────────────────

    /** Creates an unscoped Exchange with unsolicited event support. Caller is responsible for calling `close`.
      *
      * For protocols with no unsolicited messages, set `Event = Nothing` and return only `Message.Response` or `Message.Skip` from
      * `decode`.
      */
    def initUnscoped[Id, Req, Resp, Wire, Event, E](
        nextId: => Id < Sync,
        encode: (Id, Req) => Wire < Sync,
        send: Wire => Unit < (Async & Abort[E]),
        receive: Stream[Wire, Async & Abort[E]],
        decode: Wire => Exchange.Message[Id, Resp, Event] < Sync,
        eventCapacity: Int = 16
    )(using frame: Frame, eTag: ConcreteTag[E], wireTag: Tag[Emit[Chunk[Wire]]]): Exchange[Req, Resp, Event, E] < Sync =
        Sync.Unsafe.defer {
            val eventChannel = Channel.Unsafe.init[Event](eventCapacity, Access.MultiProducerMultiConsumer)
            val donePromise  = Promise.Unsafe.init[Unit, Abort[E | Closed]]()
            val pendingMap   = new ConcurrentHashMap[Id, Promise.Unsafe[Resp, Abort[E | Closed]]]()
            Fiber.initUnscoped(readerLoop(pendingMap, eventChannel, donePromise, frame, receive, decode)).map { fiber =>
                new Unsafe[Id, Wire, Req, Resp, Event, E](
                    nextIdFn = bug("Called Unsafe.apply on safe-initialized Exchange"),
                    encodeFn = (_, _) => bug("Called Unsafe.apply on safe-initialized Exchange"),
                    decodeFn = _ => bug("Called Unsafe.feed on safe-initialized Exchange"),
                    safeNextId = () => nextId,
                    safeEncode = encode,
                    safeSend = send,
                    pending = pendingMap,
                    readerFiber = fiber,
                    eventChannel = eventChannel,
                    donePromise = donePromise,
                    initFrame = frame
                )
            }
        }

    /** Drains the receive stream in the background, routing each decoded message to the appropriate pending promise or event channel.
      * Completes the done promise when the stream ends or fails.
      */
    private def readerLoop[Id, Wire, Resp, Event, E](
        pending: ConcurrentHashMap[Id, Promise.Unsafe[Resp, Abort[E | Closed]]],
        eventChannel: Channel.Unsafe[Event],
        donePromise: Promise.Unsafe[Unit, Abort[E | Closed]],
        initFrame: Frame,
        receiveWire: Stream[Wire, Async & Abort[E]],
        decodeWire: Wire => Message[Id, Resp, Event] < Sync
    )(using Frame, ConcreteTag[E], Tag[Emit[Chunk[Wire]]]): Unit < Async =
        Abort.runWith[E] {
            receiveWire.foreach { wire =>
                decodeWire(wire).map {
                    case Exchange.Message.Response(id, resp) =>
                        Sync.Unsafe.defer {
                            Maybe(pending.remove(id)).foreach { p =>
                                p.completeDiscard(Result.succeed(resp))
                            }
                        }
                    case Exchange.Message.Push(event) =>
                        // Safe to discard Closed: the exchange may shut down while a put is in
                        // flight. Without this, a Closed from the event channel would bubble up
                        // into the outer Abort.runWith[E] handler, which only expects transport
                        // errors of type E — misrouting the error.
                        Abort.run[Closed](eventChannel.safe.put(event)).unit
                    case _ =>
                        ()
                }
            }
        } {
            case Result.Success(_) =>
                Sync.Unsafe.defer {
                    pending.forEach((_, p) => p.completeDiscard(Result.fail(Closed("Exchange", initFrame))))
                    pending.clear()
                    discard(donePromise.completeDiscard(Result.fail(Closed("Exchange", initFrame))))
                    discard(eventChannel.close())
                }
            case Result.Failure(e) =>
                Sync.Unsafe.defer {
                    pending.forEach((_, p) => p.completeDiscard(Result.fail(e)))
                    pending.clear()
                    discard(donePromise.completeDiscard(Result.fail(e)))
                    discard(eventChannel.close())
                }
            case Result.Panic(t) =>
                Sync.Unsafe.defer(discard(eventChannel.close())).andThen(Abort.panic(t))
        }

    // ── Extensions ────────────────────────────────────────────────────────────

    extension [Req, Resp, Event, E](self: Exchange[Req, Resp, Event, E])

        def unsafe: Unsafe[?, ?, Req, Resp, Event, E] = self

        /** Sends a request and awaits its response. The ID is auto-assigned.
          *
          * Fails with `Abort[E]` if the connection breaks, or `Abort[Closed]` if the Exchange is closed.
          */
        def apply(req: Req)(using Frame): Resp < (Async & Abort[E | Closed]) =
            self.safeNextId().map { id =>
                Sync.Unsafe.defer {
                    self.donePromise.poll() match
                        case Maybe.Present(Result.Failure(err)) => Abort.fail(err)
                        case Maybe.Absent =>
                            val promise = Promise.Unsafe.init[Resp, Abort[E | Closed]]()
                            self.addPending(id, promise)
                            // Double-check: close() may have drained the pending map
                            // between the poll() above and addPending(). Without this,
                            // the promise would never be completed → hang.
                            self.donePromise.poll() match
                                case Maybe.Present(Result.Failure(err)) =>
                                    self.removePending(id)
                                    Abort.fail(err)
                                case _ =>
                                    Sync.ensure(Sync.Unsafe.defer(self.removePending(id))) {
                                        given ConcreteTag[E] = self.concreteTag
                                        self.safeEncode(id, req).map { wire =>
                                            Abort.runWith[E](self.safeSend(wire)) {
                                                case Result.Success(_) =>
                                                    promise.safe.get
                                                case Result.Failure(e) =>
                                                    Sync.Unsafe.defer(self.shutdownWithError(e)).andThen(Abort.fail(e))
                                                case Result.Panic(t) =>
                                                    Abort.panic(t)
                                            }
                                        }
                                    }
                            end match
                    end match
                }
            }
        end apply

        /** Stream of unsolicited events. Ends when the Exchange closes. */
        def events(using Tag[Emit[Chunk[Event]]], Frame): Stream[Event, Async & Abort[E | Closed]] =
            self.eventChannel.safe.streamUntilClosed()

        /** Await the Exchange's termination.
          *
          * Returns when the Exchange closes. Fails with `Abort[E]` if a transport error caused the close, or `Abort[Closed]` if closed
          * explicitly or the receive stream ended normally.
          */
        def awaitDone(using Frame): Unit < (Async & Abort[E | Closed]) =
            self.donePromise.safe.get

        /** Close the Exchange, failing all pending requests with `Closed`. Idempotent. */
        def close(using Frame): Unit < Sync =
            Sync.Unsafe.defer(self.close())

    end extension

    // ── Unsafe Implementation ─────────────────────────────────────────────────

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    final private[kyo] class Unsafe[Id, Wire, Req, Resp, Event, E] private[Exchange] (
        // Plain callbacks used by Unsafe.apply and Unsafe.feed
        private val nextIdFn: AllowUnsafe ?=> Id,
        private val encodeFn: (Id, Req) => (AllowUnsafe ?=> Wire),
        private val decodeFn: Wire => (AllowUnsafe ?=> Message[Id, Resp, Event]),
        // Safe callbacks used by safe apply extension and readerLoop
        private[Exchange] val safeNextId: () => Id < Sync,
        private[Exchange] val safeEncode: (Id, Req) => Wire < Sync,
        private[Exchange] val safeSend: Wire => Unit < (Async & Abort[E]),
        // Shared state
        private val pending: ConcurrentHashMap[Id, Promise.Unsafe[Resp, Abort[E | Closed]]],
        private val readerFiber: Fiber[Unit, Any],
        private[Exchange] val eventChannel: Channel.Unsafe[Event],
        private[Exchange] val donePromise: Promise.Unsafe[Unit, Abort[E | Closed]],
        private[Exchange] val initFrame: Frame
    )(using eTag: ConcreteTag[E]):

        private[Exchange] def concreteTag: ConcreteTag[E] = eTag

        // ── Unsafe API ────────────────────────────────────────────────────────

        def safe: Exchange[Req, Resp, Event, E] = this

        /** Assigns an ID, encodes the request, registers a pending promise, and returns all three. The caller is responsible for sending
          * the wire message and awaiting the promise. This is the low-level entry point for the Unsafe API path.
          */
        def apply(req: Req)(using Frame, AllowUnsafe): (Id, Wire, Promise.Unsafe[Resp, Abort[E | Closed]]) =
            val id      = nextIdFn
            val wire    = encodeFn(id, req)
            val promise = Promise.Unsafe.init[Resp, Abort[E | Closed]]()
            donePromise.poll() match
                case Maybe.Present(failure: Result.Error[E | Closed] @unchecked) =>
                    promise.completeDiscard(failure)
                case _ =>
                    discard(pending.put(id, promise))
                    donePromise.poll() match
                        case Maybe.Present(failure: Result.Error[E | Closed] @unchecked) =>
                            discard(pending.remove(id))
                            promise.completeDiscard(failure)
                        case _ => ()
                    end match
            end match
            (id, wire, promise)
        end apply

        /** Decodes an incoming wire message and routes it to the appropriate pending promise or event channel. Call this from your I/O
          * receive loop.
          */
        def feed(wire: Wire)(using Frame, AllowUnsafe): Unit =
            decodeFn(wire) match
                case Message.Response(id, resp) => completeResponse(id, resp)
                case Message.Push(event)        =>
                    // Best-effort: offer is non-blocking and returns false when the channel is
                    // full or closed. Dropping push events on a saturated/closed channel is
                    // acceptable for the Unsafe API path.
                    discard(eventChannel.offer(event))
                case _ => ()
        end feed

        /** Adds a pending promise for the given ID. */
        def addPending(id: Id, promise: Promise.Unsafe[Resp, Abort[E | Closed]])(using AllowUnsafe): Unit =
            discard(pending.put(id, promise))

        /** Removes a pending promise for the given ID. */
        def removePending(id: Id)(using AllowUnsafe): Unit =
            discard(pending.remove(id))

        /** Shared shutdown logic: interrupts reader, completes done promise, closes event channel, fails all pending.
          */
        private[Exchange] def shutdown(closed: Closed)(using frame: Frame)(using AllowUnsafe): Unit =
            failAllPending(closed)
            discard(donePromise.completeDiscard(Result.fail(closed)))
            discard(readerFiber.unsafe.interruptDiscard(Result.Panic(Interrupted(frame))))
            discard(eventChannel.close())
        end shutdown

        /** Shared shutdown logic for transport errors: interrupts reader, completes done promise with E, closes event channel, fails all
          * pending with E.
          */
        private[Exchange] def shutdownWithError(error: E)(using frame: Frame)(using AllowUnsafe): Unit =
            failAllPending(error)
            discard(donePromise.completeDiscard(Result.fail(error)))
            discard(readerFiber.unsafe.interruptDiscard(Result.Panic(Interrupted(frame))))
            discard(eventChannel.close())
        end shutdownWithError

        /** Closes the exchange with a Closed error, failing all pending and completing the done promise. */
        def close()(using frame: Frame)(using AllowUnsafe): Unit =
            shutdown(Closed("Exchange", initFrame))

        /** Completes the pending promise for the given response ID. */
        def completeResponse(id: Id, resp: Resp)(using AllowUnsafe): Unit =
            Maybe(pending.remove(id)).foreach { p =>
                p.completeDiscard(Result.succeed(resp))
            }

        /** Fails all pending requests with the given error and clears the pending map. */
        def failAllPending(error: E | Closed)(using AllowUnsafe): Unit =
            pending.forEach { (_, p) =>
                p.completeDiscard(Result.fail(error))
            }
            pending.clear()
        end failAllPending

    end Unsafe

    object Unsafe:

        /** Creates an Exchange for use with the Unsafe API (no reader fiber, no send, no receive).
          *
          * The caller drives the I/O loop manually:
          *   - Call `apply(req)` to assign an ID, encode the request, and get a promise.
          *   - Send the returned wire message yourself.
          *   - Call `feed(wire)` for each incoming wire message to route responses to pending promises.
          *
          * @param nextId
          *   Produces the next request ID (plain function, no kyo effects). Must yield unique IDs per call.
          * @param encode
          *   Encodes (id, request) into a wire message.
          * @param decode
          *   Classifies an incoming wire message as Response, Push, or Skip.
          * @param eventCapacity
          *   Buffer size for the internal event channel. Default: 16.
          */
        def init[Id, Wire, Req, Resp, Event, E](
            nextId: AllowUnsafe ?=> Id,
            encode: (Id, Req) => (AllowUnsafe ?=> Wire),
            decode: Wire => (AllowUnsafe ?=> Message[Id, Resp, Event]),
            eventCapacity: Int = 16
        )(using frame: Frame, eTag: ConcreteTag[E], allow: AllowUnsafe): Exchange.Unsafe[Id, Wire, Req, Resp, Event, E] =
            val eventChannel = Channel.Unsafe.init[Event](eventCapacity, Access.MultiProducerMultiConsumer)
            val donePromise  = Promise.Unsafe.init[Unit, Abort[E | Closed]]()
            val pendingMap   = new ConcurrentHashMap[Id, Promise.Unsafe[Resp, Abort[E | Closed]]]()
            new Exchange.Unsafe[Id, Wire, Req, Resp, Event, E](
                nextIdFn = nextId,
                encodeFn = encode,
                decodeFn = decode,
                safeNextId = () => Sync.Unsafe.defer(bug("Called safe nextId on Unsafe-initialized Exchange")),
                safeEncode = (_, _) => Sync.Unsafe.defer(bug("Called safe encode on Unsafe-initialized Exchange")),
                safeSend = _ => Sync.Unsafe.defer(bug("Called safe send on Unsafe-initialized Exchange")),
                pending = pendingMap,
                readerFiber = Fiber.unit,
                eventChannel = eventChannel,
                donePromise = donePromise,
                initFrame = frame
            )
        end init

    end Unsafe

end Exchange
