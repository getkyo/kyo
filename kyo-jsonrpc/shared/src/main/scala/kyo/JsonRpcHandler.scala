package kyo

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Stream
import kyo.Structure
import kyo.Sync

/** A live JSON-RPC 2.0 handler managing bidirectional communication over a [[JsonRpcTransport]].
  *
  * Obtain an instance via [[JsonRpcHandler.init]], which starts the inbound dispatch loop and
  * attaches the outbound sender. Calling code interacts with the peer through the typed extension
  * methods on this type:
  *  - [[call]]: send a request and await the typed response.
  *  - [[notify]]: send a fire-and-forget notification.
  *  - [[callWithProgress]]: send a request and receive incremental progress notifications.
  *  - [[callPartialResults]]: send a request and stream partial results as a `Stream[T, ...]`.
  *  - [[cancel]]: send a cancellation notification for an in-flight request.
  *  - [[close]] / [[closeNow]]: tear down the handler.
  *
  * The handler is `Scope`-managed; it closes automatically when the enclosing `Scope` exits.
  *
  * @see [[JsonRpcHandler.init]]
  * @see [[JsonRpcTransport]]
  */
opaque type JsonRpcHandler = JsonRpcHandler.Unsafe

object JsonRpcHandler:

    extension (self: JsonRpcHandler)
        def call[In: Schema, Out: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.empty
        )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
            Sync.Unsafe.defer(self.call[In, Out](method, params, extras).safe.get)

        def notify[In: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.empty
        )(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.notify[In](method, params, extras).safe.get)

        def sendUnmatched[In: Schema](
            method: String,
            params: In,
            id: JsonRpcId,
            extras: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.empty
        )(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.sendUnmatched[In](method, params, id, extras).safe.get)

        def callWithProgress[In: Schema, Out: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.empty
        )(using Frame): JsonRpcHandler.Pending[Out] < (Async & Abort[JsonRpcError | Closed]) =
            Sync.Unsafe.defer(self.callWithProgress[In, Out](method, params, extras).safe.get)

        def callPartialResults[In: Schema, T: Schema: Tag](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.empty
        )(using Frame, Tag[Emit[Chunk[T]]]): Stream[T, Async & Abort[JsonRpcError | Closed]] =
            given AllowUnsafe = AllowUnsafe.embrace.danger
            self.callPartialResults[In, T](method, params, extras)
        end callPartialResults

        def subscribeProgress(token: Structure.Value)(using Frame): Stream[Structure.Value, Async & Abort[Closed]] < Sync =
            Sync.Unsafe.defer(self.subscribeProgress(token))

        def unsubscribeProgress(token: Structure.Value)(using Frame): Unit < Async =
            Sync.Unsafe.defer(self.unsubscribeProgress(token).safe.get)

        def cancel(id: JsonRpcId, reason: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[Closed]) =
            Sync.Unsafe.defer(self.cancel(id, reason).safe.get)

        def awaitDrain(using Frame): Unit < Async =
            Sync.Unsafe.defer(self.awaitDrain.safe.get)

        /** Closes the handler immediately without draining in-flight requests. */
        def close(using Frame): Unit < Async =
            Sync.Unsafe.defer(self.close(Duration.Zero).safe.get)

        /** Closes the handler, waiting up to `gracePeriod` for in-flight requests to drain before forcing. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async =
            Sync.Unsafe.defer(self.close(gracePeriod).safe.get)

        /** Closes the handler immediately without draining in-flight requests. Identical to `close(Duration.Zero)`. */
        def closeNow(using Frame): Unit < Async =
            Sync.Unsafe.defer(self.close(Duration.Zero).safe.get)

        /** Returns the underlying unsafe handler instance. */
        def unsafe: Unsafe = self
    end extension

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe:

        def call[In: Schema, Out: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder
        )(using AllowUnsafe, Frame): Fiber.Unsafe[Out, Abort[JsonRpcError | Closed]]

        def notify[In: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder
        )(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]

        def sendUnmatched[In: Schema](
            method: String,
            params: In,
            id: JsonRpcId,
            extras: JsonRpcExtrasEncoder
        )(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]

        def callWithProgress[In: Schema, Out: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder
        )(using AllowUnsafe, Frame): Fiber.Unsafe[JsonRpcHandler.Pending[Out], Abort[JsonRpcError | Closed]]

        def callPartialResults[In: Schema, T: Schema: Tag](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder
        )(using AllowUnsafe, Frame, Tag[Emit[Chunk[T]]]): Stream[T, Async & Abort[JsonRpcError | Closed]]

        def subscribeProgress(token: Structure.Value)(using AllowUnsafe, Frame): Stream[Structure.Value, Async & Abort[Closed]]

        def unsubscribeProgress(token: Structure.Value)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]

        def cancel(id: JsonRpcId, reason: Maybe[String])(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Abort[Closed]]

        def awaitDrain(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]

        /** Closes the handler, waiting up to `gracePeriod` for in-flight requests to drain before forcing. */
        def close(gracePeriod: Duration)(using AllowUnsafe, Frame): Fiber.Unsafe[Unit, Any]

        /** Dispatches `params` to the named route held by this handler. Returns Absent for unknown names.
          * Mirrors `JsonRpcRoute.dispatch` but operates on the handler's registered method map directly.
          * For Notification kind the inner result is `Structure.Value.Null` after the handler completes.
          * The effect row includes `Abort[JsonRpcResponse.Halt]` so callers can detect short-circuit responses.
          */
        def dispatch(
            name: String,
            params: Structure.Value,
            ctx: JsonRpcRoute.Context
        )(using Frame): Maybe[Structure.Value < (Async & Abort[JsonRpcError | JsonRpcResponse.Halt])]

        /** Returns the safe opaque-type wrapper for this unsafe handler instance. */
        final def safe: JsonRpcHandler = this
    end Unsafe

    /** Represents an in-flight request that supports progress reporting.
      *
      * Returned by [[JsonRpcHandler.callWithProgress]]. Provides:
      *  - `id`: the assigned request id, usable with [[JsonRpcHandler.cancel]].
      *  - `result`: the final typed response, available as `Out < (Async & Abort[...])`.
      *  - `progress`: a `Stream` of progress `Structure.Value` notifications from the peer.
      *  - `cancel`: cancels the in-flight request by sending a cancellation notification.
      */
    final class Pending[+Out] private[kyo] (
        val id: JsonRpcId,
        val result: Out < (Async & Abort[JsonRpcError | Closed]),
        val progress: Stream[Structure.Value, Async],
        val cancel: Unit < (Async & Abort[Closed])
    )

    /** Configuration for a [[JsonRpcHandler]].
      *
      * Start from [[Config.default]] and override individual fields using the fluent builder
      * methods. Pass the result to [[JsonRpcHandler.init]].
      *
      * Controls codec selection, cancellation protocol, progress reporting, unknown-method
      * handling, message gating, concurrency limits, request timeouts, and id-allocation
      * strategy.
      *
      * @see [[JsonRpcHandler.init]]
      * @see [[Config.default]]
      * @see [[Config.require]]
      */
    final case class Config(
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0,
        cancellation: Maybe[JsonRpcCancellationPolicy] = Absent,
        progress: Maybe[JsonRpcProgressPolicy] = Absent,
        unknownMethod: JsonRpcUnknownMethodPolicy = JsonRpcUnknownMethodPolicy.minimal,
        gate: Maybe[JsonRpcMessageGate] = Absent,
        maxInFlight: Maybe[Int] = Absent,
        requestTimeout: Duration = Duration.Infinity,
        idStrategy: JsonRpcIdStrategy = JsonRpcIdStrategy.SequentialLong,
        progressResetsTimeout: Boolean = false
    ) derives CanEqual:
        def codec(c: JsonRpcCodec): Config                       = copy(codec = c)
        def cancellation(p: JsonRpcCancellationPolicy): Config   = copy(cancellation = Present(p))
        def progress(p: JsonRpcProgressPolicy): Config           = copy(progress = Present(p))
        def unknownMethod(p: JsonRpcUnknownMethodPolicy): Config = copy(unknownMethod = p)
        def gate(g: JsonRpcMessageGate): Config                  = copy(gate = Present(g))
        def maxInFlight(n: Int): Config                          = copy(maxInFlight = Present(n))
        def requestTimeout(d: Duration): Config                  = copy(requestTimeout = d)
        def idStrategy(s: JsonRpcIdStrategy): Config             = copy(idStrategy = s)
        def progressResetsTimeout(b: Boolean): Config            = copy(progressResetsTimeout = b)
    end Config

    object Config:
        val default: Config = Config()

        def require(c: Config): Unit =
            c.maxInFlight match
                case Present(n) if n <= 0 =>
                    throw new IllegalArgumentException(s"maxInFlight must be > 0, got $n")
                case _ => ()
            end match
        end require
    end Config

    // --- Scoped init methods (Scope-managed; handler closes when Scope exits) ---

    /** Initialises a handler using `routes` and `Config.default`, releasing it when the `Scope` exits. */
    def init(transport: JsonRpcTransport, routes: JsonRpcRoute[?, ?, ?]*)(using Frame): JsonRpcHandler < (Async & Scope) =
        init(transport, routes, Config.default)

    /** Initialises a handler using `routes` and the supplied `config`, releasing it when the `Scope` exits. */
    def init(
        transport: JsonRpcTransport,
        config: Config
    )(routes: JsonRpcRoute[?, ?, ?]*)(using Frame): JsonRpcHandler < (Async & Scope) =
        init(transport, routes, config)

    /** Initialises a handler from a `Seq` of routes and optional config, releasing it when the `Scope` exits. */
    def init(
        transport: JsonRpcTransport,
        methods: Seq[JsonRpcRoute[?, ?, ?]],
        config: Config = Config.default
    )(using Frame): JsonRpcHandler < (Async & Scope) =
        Config.require(config)
        Scope.acquireRelease(
            internal.engine.JsonRpcEndpointImpl.initEngine(transport, methods, config).map(_.safe)
        )(_.closeNow)
    end init

    /** Initialises a handler and immediately applies `f` to it, releasing the handler when the `Scope` exits. */
    def initWith[A, S](transport: JsonRpcTransport, routes: JsonRpcRoute[?, ?, ?]*)(f: JsonRpcHandler => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(transport, routes*).map(f)

    /** Initialises a handler with `config` and immediately applies `f`, releasing the handler when the `Scope` exits. */
    def initWith[A, S](transport: JsonRpcTransport, config: Config)(routes: JsonRpcRoute[?, ?, ?]*)(f: JsonRpcHandler => A < S)(using
        Frame
    ): A < (S & Async & Scope) =
        init(transport, config)(routes*).map(f)

    // --- Unscoped init methods (caller is responsible for closing) ---

    /** Initialises a handler using `routes` and `Config.default` without a managed `Scope`. */
    def initUnscoped(transport: JsonRpcTransport, routes: JsonRpcRoute[?, ?, ?]*)(using Frame): JsonRpcHandler < Async =
        initUnscoped(transport, routes, Config.default)

    /** Initialises a handler using `routes` and the supplied `config` without a managed `Scope`. */
    def initUnscoped(
        transport: JsonRpcTransport,
        config: Config
    )(routes: JsonRpcRoute[?, ?, ?]*)(using Frame): JsonRpcHandler < Async =
        initUnscoped(transport, routes, config)

    /** Initialises a handler from a `Seq` of routes without a managed `Scope`. */
    def initUnscoped(
        transport: JsonRpcTransport,
        methods: Seq[JsonRpcRoute[?, ?, ?]],
        config: Config = Config.default
    )(using Frame): JsonRpcHandler < Async =
        Config.require(config)
        internal.engine.JsonRpcEndpointImpl.initEngine(transport, methods, config).map(_.safe)
    end initUnscoped

    /** Initialises an unscoped handler and immediately applies `f`. */
    def initUnscopedWith[A, S](transport: JsonRpcTransport, routes: JsonRpcRoute[?, ?, ?]*)(f: JsonRpcHandler => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(transport, routes*).map(f)

    /** Initialises an unscoped handler with `config` and immediately applies `f`. */
    def initUnscopedWith[A, S](
        transport: JsonRpcTransport,
        config: Config
    )(routes: JsonRpcRoute[?, ?, ?]*)(f: JsonRpcHandler => A < S)(using
        Frame
    ): A < (S & Async) =
        initUnscoped(transport, config)(routes*).map(f)

end JsonRpcHandler
