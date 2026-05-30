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
            extras: JsonRpcHandler.ExtrasEncoder = JsonRpcHandler.ExtrasEncoder.empty
        )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
            self.callUnsafe[In, Out](method, params, extras)

        def notify[In: Schema](
            method: String,
            params: In,
            extras: JsonRpcHandler.ExtrasEncoder = JsonRpcHandler.ExtrasEncoder.empty
        )(using Frame): Unit < (Async & Abort[Closed]) =
            self.notifyUnsafe[In](method, params, extras)

        def sendUnmatched[In: Schema](
            method: String,
            params: In,
            id: JsonRpcId,
            extras: JsonRpcHandler.ExtrasEncoder = JsonRpcHandler.ExtrasEncoder.empty
        )(using Frame): Unit < (Async & Abort[Closed]) =
            self.sendUnmatchedUnsafe[In](method, params, id, extras)

        def callWithProgress[In: Schema, Out: Schema](
            method: String,
            params: In,
            extras: JsonRpcHandler.ExtrasEncoder = JsonRpcHandler.ExtrasEncoder.empty
        )(using Frame): JsonRpcHandler.Pending[Out] < (Async & Abort[JsonRpcError | Closed]) =
            self.callWithProgressUnsafe[In, Out](method, params, extras)

        def callPartialResults[In: Schema, T: Schema: Tag](
            method: String,
            params: In,
            extras: JsonRpcHandler.ExtrasEncoder = JsonRpcHandler.ExtrasEncoder.empty
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
            extras: JsonRpcHandler.ExtrasEncoder
        )(using Frame): Out < (Async & Abort[JsonRpcError | Closed])

        def notifyUnsafe[In: Schema](
            method: String,
            params: In,
            extras: JsonRpcHandler.ExtrasEncoder
        )(using Frame): Unit < (Async & Abort[Closed])

        def sendUnmatchedUnsafe[In: Schema](
            method: String,
            params: In,
            id: JsonRpcId,
            extras: JsonRpcHandler.ExtrasEncoder
        )(using Frame): Unit < (Async & Abort[Closed])

        def callWithProgressUnsafe[In: Schema, Out: Schema](
            method: String,
            params: In,
            extras: JsonRpcHandler.ExtrasEncoder
        )(using Frame): JsonRpcHandler.Pending[Out] < (Async & Abort[JsonRpcError | Closed])

        def callPartialResultsUnsafe[In: Schema, T: Schema: Tag](
            method: String,
            params: In,
            extras: JsonRpcHandler.ExtrasEncoder
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
    final class Pending[Out] private[kyo] (
        val id: JsonRpcId,
        val result: Out < (Async & Abort[JsonRpcError | Closed]),
        val progress: Stream[Structure.Value, Async],
        val cancel: Unit < (Async & Abort[Closed])
    )

    /** Selects how the endpoint allocates [[JsonRpcId]] values for outbound requests.
      *
      * Three strategies are available:
      *  - [[IdStrategy.SequentialLong]]: monotonically increasing `Long` ids starting at 1.
      *  - [[IdStrategy.SequentialInt]]: monotonically increasing `Int` ids (wraps at `Int.MaxValue`).
      *  - [[IdStrategy.Custom]]: caller-supplied generator; use when interoperating with peers that
      *    require string ids or specific id formats.
      *
      * Set via [[JsonRpcHandler.Config.idStrategy]].
      *
      * @see [[JsonRpcHandler.Config]]
      */
    enum IdStrategy derives CanEqual:
        case SequentialLong
        case SequentialInt
        case Custom(next: () => JsonRpcId < Sync)
    end IdStrategy

    /** Controls how the endpoint responds to incoming requests and notifications for methods
      * that have no registered handler.
      *
      * Three preset values cover the most common cases:
      *  - [[UnknownMethodPolicy.minimal]]: reply `MethodNotFound` on requests, silently drop notifications.
      *  - [[UnknownMethodPolicy.lsp]]: same as `minimal` but also allows `$/`-prefixed LSP meta-methods.
      *  - [[UnknownMethodPolicy.strict]]: reply `MethodNotFound` on requests, reject unknown notifications.
      *
      * Set via [[JsonRpcHandler.Config.unknownMethod]].
      *
      * @see [[JsonRpcHandler.Config]]
      */
    // Hub.scala:22 smart-constructor pattern; users select .minimal / .lsp / .strict
    final case class UnknownMethodPolicy private[kyo] (
        onUnknownRequest: UnknownMethodPolicy.UnknownAction,
        onUnknownNotification: UnknownMethodPolicy.UnknownAction,
        dollarPrefixOverride: Boolean
    ) derives CanEqual

    object UnknownMethodPolicy:
        enum UnknownAction derives CanEqual:
            case ReplyMethodNotFound
            case Drop
            case Reject
        end UnknownAction

        val minimal: UnknownMethodPolicy = UnknownMethodPolicy(
            onUnknownRequest = UnknownAction.ReplyMethodNotFound,
            onUnknownNotification = UnknownAction.Drop,
            dollarPrefixOverride = false
        )

        val lsp: UnknownMethodPolicy = UnknownMethodPolicy(
            onUnknownRequest = UnknownAction.ReplyMethodNotFound,
            onUnknownNotification = UnknownAction.Drop,
            dollarPrefixOverride = true
        )

        val strict: UnknownMethodPolicy = UnknownMethodPolicy(
            onUnknownRequest = UnknownAction.ReplyMethodNotFound,
            onUnknownNotification = UnknownAction.Reject,
            dollarPrefixOverride = false
        )
    end UnknownMethodPolicy

    /** Configures how the endpoint sends and receives request cancellation notifications.
      *
      * A `CancellationPolicy` specifies the method name, parameter encoding/decoding, whether the
      * cancelled request still expects a reply, and which methods are protected from cancellation.
      *
      * Two preset policies cover the major protocols:
      *  - [[CancellationPolicy.lsp]]: LSP `$/cancelRequest` with `{"id": ...}` params; cancelled
      *    requests still produce a `MethodNotFound` reply.
      *  - [[CancellationPolicy.mcp]]: MCP `notifications/cancelled` with `{"requestId": ...}`; no
      *    reply is expected for cancelled requests; `initialize` is protected.
      *
      * Set via [[JsonRpcHandler.Config.cancellation]].
      *
      * @see [[JsonRpcHandler.Config]]
      */
    final case class CancellationPolicy(
        cancelMethod: String,
        encodeParams: CancellationPolicy.ParamsEncoder,
        decodeParams: CancellationPolicy.ParamsDecoder,
        expectReplyForCancelledRequest: Boolean,
        cancelledError: Maybe[JsonRpcError],
        protectedMethods: Set[String]
    ) derives CanEqual

    object CancellationPolicy:
        private type ParamsEncoder = (JsonRpcId, Maybe[String]) => Frame ?=> Structure.Value < Sync
        private type ParamsDecoder = Structure.Value => Frame ?=> Maybe[JsonRpcId] < Sync

        private case class LspCancelParams(id: JsonRpcId) derives Schema, CanEqual
        private case class McpCancelParams(requestId: JsonRpcId, reason: Maybe[String]) derives Schema, CanEqual

        // annotation pins the private ParamsEncoder type so the lambda matches the case-class constructor field type
        private val lspEncoder: ParamsEncoder =
            (id, _) =>
                f ?=>
                    Sync.defer(Structure.encode(LspCancelParams(id)))(using f)

        // annotation pins the private ParamsEncoder type so the lambda matches the case-class constructor field type
        private val mcpEncoder: ParamsEncoder =
            (id, reason) =>
                f ?=>
                    Sync.defer(Structure.encode(McpCancelParams(id, reason)))(using f)

        // annotation pins the private ParamsDecoder type so the lambda matches the case-class constructor field type
        private val lspDecoder: ParamsDecoder =
            sv =>
                f ?=>
                    Sync.defer {
                        Structure.decode[LspCancelParams](sv)(using summon[Schema[LspCancelParams]], f) match
                            case Result.Success(p) => Present(p.id)
                            case _                 => Absent
                    }(using f)

        // annotation pins the private ParamsDecoder type so the lambda matches the case-class constructor field type
        private val mcpDecoder: ParamsDecoder =
            sv =>
                f ?=>
                    Sync.defer {
                        Structure.decode[McpCancelParams](sv)(using summon[Schema[McpCancelParams]], f) match
                            case Result.Success(p) => Present(p.requestId)
                            case _                 => Absent
                    }(using f)

        val lsp: CancellationPolicy = CancellationPolicy(
            cancelMethod = "$/cancelRequest",
            encodeParams = lspEncoder,
            decodeParams = lspDecoder,
            expectReplyForCancelledRequest = true,
            cancelledError = Present(JsonRpcCustomError(-32800, "Request cancelled")(using Frame.internal)),
            protectedMethods = Set.empty
        )

        val mcp: CancellationPolicy = CancellationPolicy(
            cancelMethod = "notifications/cancelled",
            encodeParams = mcpEncoder,
            decodeParams = mcpDecoder,
            expectReplyForCancelledRequest = false,
            cancelledError = Absent,
            protectedMethods = Set("initialize")
        )
    end CancellationPolicy

    /** Configures how the endpoint reports and receives progress notifications during long-running
      * requests.
      *
      * A `ProgressPolicy` captures the method name and a set of token-extraction and parameter
      * encoding/decoding functions specific to each protocol dialect.
      *
      * Two preset policies are provided:
      *  - [[ProgressPolicy.lsp]]: LSP `$/progress` with `workDoneToken` in request params.
      *  - [[ProgressPolicy.mcp]]: MCP `notifications/progress` with `_meta.progressToken`; enforces
      *    monotonic progress values.
      *
      * Set via [[JsonRpcHandler.Config.progress]].
      *
      * @see [[JsonRpcHandler.Config]]
      * @see [[JsonRpcRoute.Context]]
      */
    final case class ProgressPolicy(
        progressMethod: String,
        extractInboundToken: Structure.Value => (Maybe[Structure.Value] < Sync),
        extractRequestToken: Structure.Value => (Maybe[Structure.Value] < Sync),
        stampOutboundToken: (Structure.Value, Structure.Value) => (Structure.Value < Sync),
        encodeProgressParams: (Structure.Value, Structure.Value) => (Structure.Value < Sync),
        extractProgressValue: Structure.Value => (Maybe[Structure.Value] < Sync),
        enforceMonotonic: Boolean
    ) derives CanEqual

    object ProgressPolicy:
        import Structure.Value.Record

        // Field lookup in a Record; Absent for non-records or missing keys.
        private inline def field(v: Structure.Value, name: String): Maybe[Structure.Value] =
            v match
                case Record(fields) =>
                    Maybe.fromOption(fields.iterator.collectFirst { case (k, x) if k == name => x })
                case _ => Absent

        // Merge two Records: b's keys win on collision (last-write-wins via Chunk concatenation).
        private inline def merge(a: Structure.Value, b: Structure.Value): Structure.Value =
            (a, b) match
                case (Record(af), Record(bf)) => Record(af ++ bf)
                case (Record(_), other)       => other
                case (_, Record(bf))          => Record(bf)
                case (_, other)               => other

        val lsp: ProgressPolicy = ProgressPolicy(
            progressMethod = "$/progress",
            extractInboundToken = p => field(p, "token"),
            extractRequestToken = p => field(p, "workDoneToken"),
            stampOutboundToken = (p, t) => merge(p, Record(Chunk("workDoneToken" -> t))),
            encodeProgressParams = (t, v) => Record(Chunk("token" -> t, "value" -> v)),
            extractProgressValue = p => field(p, "value"),
            enforceMonotonic = false
        )

        val mcp: ProgressPolicy = ProgressPolicy(
            progressMethod = "notifications/progress",
            extractInboundToken = p => field(p, "progressToken"),
            extractRequestToken = p =>
                field(p, "_meta").map(meta => field(meta, "progressToken")).getOrElse(Absent),
            stampOutboundToken = (p, t) =>
                val existingMeta = field(p, "_meta").getOrElse(Record(Chunk.empty))
                val newMeta      = merge(existingMeta, Record(Chunk("progressToken" -> t)))
                merge(p, Record(Chunk("_meta" -> newMeta)))
            ,
            encodeProgressParams = (t, v) =>
                merge(Record(Chunk("progressToken" -> t)), v),
            extractProgressValue = p => Present(p),
            enforceMonotonic = true
        )
    end ProgressPolicy

    /** Opaque function type that attaches protocol-specific extra fields to outbound envelopes.
      *
      * Passed to `JsonRpcHandler.call`, `notify`, and `sendUnmatched` as the `extras` parameter.
      * The function receives the assigned request id and returns an optional `Structure.Value` map
      * that is merged into the outgoing envelope's `extras` field.
      *
      * Use the companion factories:
      *  - [[ExtrasEncoder.empty]]: no extras on every call.
      *  - [[ExtrasEncoder.const]]: attach the same extras value to every call.
      *  - [[ExtrasEncoder.apply]]: full control with a per-id function.
      *
      * @see [[JsonRpcHandler]]
      */
    opaque type ExtrasEncoder = JsonRpcId => Maybe[Structure.Value] < Sync

    object ExtrasEncoder:
        def apply(f: JsonRpcId => Maybe[Structure.Value] < Sync): ExtrasEncoder = f

        val empty: ExtrasEncoder = (_: JsonRpcId) => Absent

        def const(extras: Structure.Value): ExtrasEncoder =
            (_: JsonRpcId) => Present(extras)

        // opaque-type companion carve-out (FLOW Decision #30 (b))
        extension (self: ExtrasEncoder)
            def resolve(id: JsonRpcId)(using Frame): Maybe[Structure.Value] < Sync = self(id)
    end ExtrasEncoder

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
        cancellation: Maybe[CancellationPolicy] = Absent,
        progress: Maybe[ProgressPolicy] = Absent,
        unknownMethod: UnknownMethodPolicy = UnknownMethodPolicy.minimal,
        gate: Maybe[JsonRpcMessageGate] = Absent,
        maxInFlight: Maybe[Int] = Absent,
        requestTimeout: Duration = Duration.Infinity,
        idStrategy: IdStrategy = IdStrategy.SequentialLong,
        progressResetsTimeout: Boolean = false
    ) derives CanEqual:
        def codec(c: JsonRpcCodec): Config                = copy(codec = c)
        def cancellation(p: CancellationPolicy): Config   = copy(cancellation = Present(p))
        def progress(p: ProgressPolicy): Config           = copy(progress = Present(p))
        def unknownMethod(p: UnknownMethodPolicy): Config = copy(unknownMethod = p)
        def gate(g: JsonRpcMessageGate): Config           = copy(gate = Present(g))
        def maxInFlight(n: Int): Config                   = copy(maxInFlight = Present(n))
        def requestTimeout(d: Duration): Config           = copy(requestTimeout = d)
        def idStrategy(s: IdStrategy): Config             = copy(idStrategy = s)
        def progressResetsTimeout(b: Boolean): Config     = copy(progressResetsTimeout = b)
    end Config

    object Config:
        val default: Config = Config()

        /** Preset for Language Server Protocol (LSP) sessions.
          *
          * Wires matched cancellation and progress policies for the LSP dialect in one step.
          * Equivalent to `Config.default.cancellation(CancellationPolicy.lsp).progress(ProgressPolicy.lsp)`.
          */
        val lsp: Config = Config.default
            .cancellation(CancellationPolicy.lsp)
            .progress(ProgressPolicy.lsp)

        /** Preset for Model Context Protocol (MCP) sessions.
          *
          * Wires matched cancellation and progress policies for the MCP dialect in one step, and
          * additionally sets [[UnknownMethodPolicy.minimal]] (the default).
          * Equivalent to:
          * {{{
          * Config.default
          *   .cancellation(CancellationPolicy.mcp)
          *   .progress(ProgressPolicy.mcp)
          *   .unknownMethod(UnknownMethodPolicy.minimal)
          * }}}
          */
        val mcp: Config = Config.default
            .cancellation(CancellationPolicy.mcp)
            .progress(ProgressPolicy.mcp)
            .unknownMethod(UnknownMethodPolicy.minimal)

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
