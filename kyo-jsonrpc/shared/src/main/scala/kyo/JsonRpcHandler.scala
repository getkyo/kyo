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
  * Mirrors `HttpServer` at kyo-http/shared/src/main/scala/kyo/HttpServer.scala:37.
  *
  * @see [[JsonRpcHandler.init]]
  * @see [[JsonRpcTransport]]
  */
// HttpServer.scala:37 opaque-type pattern; init through JsonRpcHandler.init
opaque type JsonRpcHandler = JsonRpcHandler.Unsafe

object JsonRpcHandler:

    extension (self: JsonRpcHandler)
        def call[In: Schema, Out: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.empty
        )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
            self.callUnsafe[In, Out](method, params, extras)

        def notify[In: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.empty
        )(using Frame): Unit < (Async & Abort[Closed]) =
            self.notifyUnsafe[In](method, params, extras)

        def sendUnmatched[In: Schema](
            method: String,
            params: In,
            id: JsonRpcId,
            extras: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.empty
        )(using Frame): Unit < (Async & Abort[Closed]) =
            self.sendUnmatchedUnsafe[In](method, params, id, extras)

        def callWithProgress[In: Schema, Out: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.empty
        )(using Frame): JsonRpcHandler.Pending[Out] < (Async & Abort[JsonRpcError | Closed]) =
            self.callWithProgressUnsafe[In, Out](method, params, extras)

        def callPartialResults[In: Schema, T: Schema: Tag](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder = JsonRpcExtrasEncoder.empty
        )(using Frame, Tag[Emit[Chunk[T]]]): Stream[T, Async & Abort[JsonRpcError | Closed]] =
            self.callPartialResultsUnsafe[In, T](method, params, extras)

        def subscribeProgress(token: Structure.Value)(using Frame): Stream[Structure.Value, Async & Abort[Closed]] < Sync =
            self.subscribeProgressUnsafe(token)

        def unsubscribeProgress(token: Structure.Value)(using Frame): Unit < Async =
            self.unsubscribeProgressUnsafe(token)

        def cancel(id: JsonRpcId, reason: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[Closed]) =
            self.cancelUnsafe(id, reason)

        def awaitDrain(using Frame): Unit < Async =
            self.awaitDrainUnsafe

        /** Closes the handler immediately without draining in-flight requests. */
        def close(using Frame): Unit < Async =
            self.closeUnsafe(Duration.Zero)

        /** Closes the handler, waiting up to `gracePeriod` for in-flight requests to drain before forcing. */
        def close(gracePeriod: Duration)(using Frame): Unit < Async =
            self.closeUnsafe(gracePeriod)

        /** Closes the handler immediately without draining in-flight requests. Identical to `close(Duration.Zero)`. */
        def closeNow(using Frame): Unit < Async =
            self.closeUnsafe(Duration.Zero)

        /** Returns the underlying unsafe handler instance. */
        def unsafe: Unsafe = self
    end extension

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe:

        def callUnsafe[In: Schema, Out: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder
        )(using Frame): Out < (Async & Abort[JsonRpcError | Closed])

        def notifyUnsafe[In: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder
        )(using Frame): Unit < (Async & Abort[Closed])

        def sendUnmatchedUnsafe[In: Schema](
            method: String,
            params: In,
            id: JsonRpcId,
            extras: JsonRpcExtrasEncoder
        )(using Frame): Unit < (Async & Abort[Closed])

        def callWithProgressUnsafe[In: Schema, Out: Schema](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder
        )(using Frame): JsonRpcHandler.Pending[Out] < (Async & Abort[JsonRpcError | Closed])

        def callPartialResultsUnsafe[In: Schema, T: Schema: Tag](
            method: String,
            params: In,
            extras: JsonRpcExtrasEncoder
        )(using Frame, Tag[Emit[Chunk[T]]]): Stream[T, Async & Abort[JsonRpcError | Closed]]

        def subscribeProgressUnsafe(token: Structure.Value)(using Frame): Stream[Structure.Value, Async & Abort[Closed]] < Sync

        def unsubscribeProgressUnsafe(token: Structure.Value)(using Frame): Unit < Async

        def cancelUnsafe(id: JsonRpcId, reason: Maybe[String])(using Frame): Unit < (Async & Abort[Closed])

        def awaitDrainUnsafe(using Frame): Unit < Async

        /** Closes the handler, waiting up to `gracePeriod` for in-flight requests to drain before forcing. */
        def closeUnsafe(gracePeriod: Duration)(using Frame): Unit < Async

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
    // Hub.scala:22 smart-constructor pattern; Pending built only by JsonRpcEndpointImpl.callWithProgress
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

        /** Preset for Language Server Protocol (LSP) sessions.
          *
          * Wires matched cancellation and progress policies for the LSP dialect in one step.
          * Equivalent to `Config.default.cancellation(JsonRpcCancellationPolicy.lsp).progress(JsonRpcProgressPolicy.lsp)`.
          */
        val lsp: Config = Config.default
            .cancellation(JsonRpcCancellationPolicy.lsp)
            .progress(JsonRpcProgressPolicy.lsp)

        /** Preset for Model Context Protocol (MCP) sessions.
          *
          * Wires matched cancellation and progress policies for the MCP dialect in one step, and
          * additionally sets [[JsonRpcUnknownMethodPolicy.minimal]] (the default).
          * Equivalent to:
          * {{{
          * Config.default
          *   .cancellation(JsonRpcCancellationPolicy.mcp)
          *   .progress(JsonRpcProgressPolicy.mcp)
          *   .unknownMethod(JsonRpcUnknownMethodPolicy.minimal)
          * }}}
          */
        val mcp: Config = Config.default
            .cancellation(JsonRpcCancellationPolicy.mcp)
            .progress(JsonRpcProgressPolicy.mcp)
            .unknownMethod(JsonRpcUnknownMethodPolicy.minimal)

        def require(c: Config): Unit =
            c.maxInFlight match
                case Present(n) if n <= 0 =>
                    throw new IllegalArgumentException(s"maxInFlight must be > 0, got $n")
                case _ => ()
            end match
        end require
    end Config

    // --- Scoped init methods (Scope-managed; handler closes when Scope exits) ---
    // Mirrors HttpServer.init at kyo-http/shared/src/main/scala/kyo/HttpServer.scala:71-91.

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

    /** Initialises a handler and immediately applies `f` to it, releasing the handler when the `Scope` exits.
      * Mirrors `HttpServer.initWith` at kyo-http/shared/src/main/scala/kyo/HttpServer.scala:80-91.
      */
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
    // Mirrors HttpServer.initUnscoped at kyo-http/shared/src/main/scala/kyo/HttpServer.scala:95-140.

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

    /** Initialises an unscoped handler and immediately applies `f`.
      * Mirrors `HttpServer.initUnscopedWith` at kyo-http/shared/src/main/scala/kyo/HttpServer.scala:129-140.
      */
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
