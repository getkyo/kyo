package kyo

import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Stream
import kyo.Structure
import kyo.Sync

/** A live JSON-RPC 2.0 endpoint managing bidirectional communication over a [[JsonRpcTransport]].
  *
  * Obtain an instance via [[JsonRpcEndpoint.init]], which starts the inbound dispatch loop and
  * attaches the outbound sender. Calling code interacts with the peer through the typed methods
  * on this class:
  *  - [[call]]: send a request and await the typed response.
  *  - [[notify]]: send a fire-and-forget notification.
  *  - [[callWithProgress]]: send a request and receive incremental progress notifications.
  *  - [[callPartialResults]]: send a request and stream partial results as a `Stream[T, ...]`.
  *  - [[cancel]]: send a cancellation notification for an in-flight request.
  *  - [[close]] / [[closeNow]]: tear down the endpoint.
  *
  * The endpoint is `Scope`-managed; it closes automatically when the enclosing `Scope` exits.
  *
  * @see [[JsonRpcEndpoint.init]]
  * @see [[JsonRpcTransport]]
  */
// Hub.scala:22 smart-constructor pattern; init through JsonRpcEndpoint.init
final class JsonRpcEndpoint private[kyo] (private[kyo] val impl: internal.engine.JsonRpcEndpointImpl):

    def call[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcEndpoint.ExtrasEncoder = JsonRpcEndpoint.ExtrasEncoder.empty
    )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
        impl.call[In, Out](method, params, extras)

    def notify[In: Schema](
        method: String,
        params: In,
        extras: JsonRpcEndpoint.ExtrasEncoder = JsonRpcEndpoint.ExtrasEncoder.empty
    )(using Frame): Unit < (Async & Abort[Closed]) =
        impl.notify[In](method, params, extras)

    def sendUnmatched[In: Schema](
        method: String,
        params: In,
        id: JsonRpcEnvelope.Id,
        extras: JsonRpcEndpoint.ExtrasEncoder = JsonRpcEndpoint.ExtrasEncoder.empty
    )(using Frame): Unit < (Async & Abort[Closed]) =
        impl.sendUnmatched[In](method, params, id, extras)

    def callWithProgress[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: JsonRpcEndpoint.ExtrasEncoder = JsonRpcEndpoint.ExtrasEncoder.empty
    )(using Frame): JsonRpcEndpoint.Pending[Out] < (Async & Abort[JsonRpcError | Closed]) =
        impl.callWithProgress[In, Out](method, params, extras)

    def callPartialResults[In: Schema, T: Schema: Tag](
        method: String,
        params: In,
        extras: JsonRpcEndpoint.ExtrasEncoder = JsonRpcEndpoint.ExtrasEncoder.empty
    )(using Frame, Tag[Emit[Chunk[T]]]): Stream[T, Async & Abort[JsonRpcError | Closed]] =
        impl.callPartialResults[In, T](method, params, extras)

    def subscribeProgress(token: Structure.Value)(using Frame): Stream[Structure.Value, Async & Abort[Closed]] < Sync =
        impl.subscribeProgress(token)

    def unsubscribeProgress(token: Structure.Value)(using Frame): Unit < Async =
        impl.unsubscribeProgress(token)

    def cancel(id: JsonRpcEnvelope.Id, reason: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[Closed]) =
        impl.cancel(id, reason)

    def awaitDrain(using Frame): Unit < Async = impl.awaitDrain

    /** Closes the endpoint immediately without draining in-flight requests.
      *
      * Note: when chaining with `.andThen`, use `closeNow.andThen(...)` to avoid Scala 3
      * overload-resolution ambiguity between this no-arg form and `close(gracePeriod: Duration)`.
      */
    def close(using Frame): Unit < Async = impl.close(Duration.Zero)

    /** Closes the endpoint, waiting up to `gracePeriod` for in-flight requests to drain before forcing.
      *
      * Note: when chaining with `.andThen`, prefer `close(Duration.Zero).andThen(...)` or
      * `closeNow.andThen(...)` to avoid Scala 3 overload-resolution ambiguity on the no-arg `close`.
      */
    def close(gracePeriod: Duration)(using Frame): Unit < Async = impl.close(gracePeriod)

    /** Closes the endpoint immediately without draining in-flight requests.
      *
      * Identical to `close(Duration.Zero)`. Use this form when chaining with `.andThen` to avoid
      * Scala 3 overload-resolution ambiguity on the no-arg `close` overload.
      */
    def closeNow(using Frame): Unit < Async = impl.close(Duration.Zero)

end JsonRpcEndpoint

object JsonRpcEndpoint:

    /** Represents an in-flight request that supports progress reporting.
      *
      * Returned by [[JsonRpcEndpoint.callWithProgress]]. Provides:
      *  - `id`: the assigned request id, usable with [[JsonRpcEndpoint.cancel]].
      *  - `result`: the final typed response, available as `Out < (Async & Abort[...])`.
      *  - `progress`: a `Stream` of progress `Structure.Value` notifications from the peer.
      *  - `cancel`: cancels the in-flight request by sending a cancellation notification.
      */
    // Hub.scala:22 smart-constructor pattern; Pending built only by JsonRpcEndpointImpl.callWithProgress
    final class Pending[Out] private[kyo] (
        val id: JsonRpcEnvelope.Id,
        val result: Out < (Async & Abort[JsonRpcError | Closed]),
        val progress: Stream[Structure.Value, Async],
        val cancel: Unit < (Async & Abort[Closed])
    )

    /** Selects how the endpoint allocates [[JsonRpcEnvelope.Id]] values for outbound requests.
      *
      * Three strategies are available:
      *  - [[IdStrategy.SequentialLong]]: monotonically increasing `Long` ids starting at 1.
      *  - [[IdStrategy.SequentialInt]]: monotonically increasing `Int` ids (wraps at `Int.MaxValue`).
      *  - [[IdStrategy.Custom]]: caller-supplied generator; use when interoperating with peers that
      *    require string ids or specific id formats.
      *
      * Set via [[JsonRpcEndpoint.Config.idStrategy]].
      *
      * @see [[JsonRpcEndpoint.Config]]
      */
    enum IdStrategy derives CanEqual:
        case SequentialLong
        case SequentialInt
        case Custom(next: () => JsonRpcEnvelope.Id < Sync)
    end IdStrategy

    /** Controls how the endpoint responds to incoming requests and notifications for methods
      * that have no registered handler.
      *
      * Three preset values cover the most common cases:
      *  - [[UnknownMethodPolicy.minimal]]: reply `MethodNotFound` on requests, silently drop notifications.
      *  - [[UnknownMethodPolicy.lsp]]: same as `minimal` but also allows `$/`-prefixed LSP meta-methods.
      *  - [[UnknownMethodPolicy.strict]]: reply `MethodNotFound` on requests, reject unknown notifications.
      *
      * Set via [[JsonRpcEndpoint.Config.unknownMethod]].
      *
      * @see [[JsonRpcEndpoint.Config]]
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

    /** A pre-dispatch hook that can allow, reject, or drop incoming envelopes before routing.
      *
      * Implement `beforeDispatch` to apply cross-cutting concerns such as authentication, rate
      * limiting, or protocol-specific pre-validation. The three possible outcomes are modelled by
      * [[MessageGate.Decision]]:
      *  - `Allow`: pass the message to the handler.
      *  - `Reject`: reply with a `JsonRpcError` and discard the message.
      *  - `Drop`: silently discard the message.
      *
      * Set via [[JsonRpcEndpoint.Config.gate]].
      *
      * @see [[JsonRpcEndpoint.Config]]
      */
    trait MessageGate:
        def beforeDispatch(env: JsonRpcEnvelope)(using Frame): MessageGate.Decision < Sync

    object MessageGate:
        enum Decision derives CanEqual:
            case Allow
            case Reject(error: JsonRpcError)
            case Drop
        end Decision
    end MessageGate

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
      * Set via [[JsonRpcEndpoint.Config.cancellation]].
      *
      * @see [[JsonRpcEndpoint.Config]]
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
        private type ParamsEncoder = (JsonRpcEnvelope.Id, Maybe[String]) => Frame ?=> Structure.Value < Sync
        private type ParamsDecoder = Structure.Value => Frame ?=> Maybe[JsonRpcEnvelope.Id] < Sync

        private case class LspCancelParams(id: JsonRpcEnvelope.Id) derives Schema, CanEqual
        private case class McpCancelParams(requestId: JsonRpcEnvelope.Id, reason: Maybe[String]) derives Schema, CanEqual

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
            cancelledError = Present(JsonRpcError.RequestCancelled),
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
      * Set via [[JsonRpcEndpoint.Config.progress]].
      *
      * @see [[JsonRpcEndpoint.Config]]
      * @see [[JsonRpcMethod.Context]]
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
      * Passed to `JsonRpcEndpoint.call`, `notify`, and `sendUnmatched` as the `extras` parameter.
      * The function receives the assigned request id and returns an optional `Structure.Value` map
      * that is merged into the outgoing envelope's `extras` field.
      *
      * Use the companion factories:
      *  - [[ExtrasEncoder.empty]]: no extras on every call.
      *  - [[ExtrasEncoder.const]]: attach the same extras value to every call.
      *  - [[ExtrasEncoder.apply]]: full control with a per-id function.
      *
      * @see [[JsonRpcEndpoint]]
      */
    opaque type ExtrasEncoder = JsonRpcEnvelope.Id => Maybe[Structure.Value] < Sync

    object ExtrasEncoder:
        def apply(f: JsonRpcEnvelope.Id => Maybe[Structure.Value] < Sync): ExtrasEncoder = f

        val empty: ExtrasEncoder = (_: JsonRpcEnvelope.Id) => Absent

        def const(extras: Structure.Value): ExtrasEncoder =
            (_: JsonRpcEnvelope.Id) => Present(extras)

        // opaque-type companion carve-out (FLOW Decision #30 (b))
        extension (self: ExtrasEncoder)
            def resolve(id: JsonRpcEnvelope.Id)(using Frame): Maybe[Structure.Value] < Sync = self(id)
    end ExtrasEncoder

    /** Configuration for a [[JsonRpcEndpoint]].
      *
      * Start from [[Config.default]] and override individual fields using the fluent builder
      * methods. Pass the result to [[JsonRpcEndpoint.init]].
      *
      * Controls codec selection, cancellation protocol, progress reporting, unknown-method
      * handling, message gating, concurrency limits, request timeouts, and id-allocation
      * strategy.
      *
      * @see [[JsonRpcEndpoint.init]]
      * @see [[Config.default]]
      * @see [[Config.require]]
      */
    final case class Config(
        codec: JsonRpcCodec = JsonRpcCodec.Strict2_0,
        cancellation: Maybe[CancellationPolicy] = Absent,
        progress: Maybe[ProgressPolicy] = Absent,
        unknownMethod: UnknownMethodPolicy = UnknownMethodPolicy.minimal,
        gate: Maybe[MessageGate] = Absent,
        maxInFlight: Maybe[Int] = Absent,
        requestTimeout: Duration = Duration.Infinity,
        idStrategy: IdStrategy = IdStrategy.SequentialLong,
        progressResetsTimeout: Boolean = false
    ) derives CanEqual:
        def codec(c: JsonRpcCodec): Config                = copy(codec = c)
        def cancellation(p: CancellationPolicy): Config   = copy(cancellation = Present(p))
        def progress(p: ProgressPolicy): Config           = copy(progress = Present(p))
        def unknownMethod(p: UnknownMethodPolicy): Config = copy(unknownMethod = p)
        def gate(g: MessageGate): Config                  = copy(gate = Present(g))
        def maxInFlight(n: Int): Config                   = copy(maxInFlight = Present(n))
        def requestTimeout(d: Duration): Config           = copy(requestTimeout = d)
        def idStrategy(s: IdStrategy): Config             = copy(idStrategy = s)
        def progressResetsTimeout(b: Boolean): Config     = copy(progressResetsTimeout = b)
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

    def init(
        transport: JsonRpcTransport,
        methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
        config: Config = Config.default
    )(using Frame): JsonRpcEndpoint < (Async & Scope) =
        Config.require(config)
        internal.engine.JsonRpcEndpointImpl.init(transport, methods, config).map(new JsonRpcEndpoint(_))
    end init

end JsonRpcEndpoint
