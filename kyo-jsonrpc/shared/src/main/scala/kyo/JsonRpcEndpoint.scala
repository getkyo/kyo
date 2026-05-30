package kyo

import kyo.Stream

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
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Out < (Async & Abort[JsonRpcError | Closed]) =
        impl.call[In, Out](method, params, extras)

    def notify[In: Schema](
        method: String,
        params: In,
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Unit < (Async & Abort[Closed]) =
        impl.notify[In](method, params, extras)

    def sendUnmatched[In: Schema](
        method: String,
        params: In,
        id: JsonRpcId,
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): Unit < (Async & Abort[Closed]) =
        impl.sendUnmatched[In](method, params, id, extras)

    def callWithProgress[In: Schema, Out: Schema](
        method: String,
        params: In,
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame): JsonRpcEndpoint.Pending[Out] < (Async & Abort[JsonRpcError | Closed]) =
        impl.callWithProgress[In, Out](method, params, extras)

    def callPartialResults[In: Schema, T: Schema: Tag](
        method: String,
        params: In,
        extras: ExtrasEncoder = ExtrasEncoder.empty
    )(using Frame, Tag[Emit[Chunk[T]]]): Stream[T, Async & Abort[JsonRpcError | Closed]] =
        impl.callPartialResults[In, T](method, params, extras)

    def subscribeProgress(token: Structure.Value)(using Frame): Stream[Structure.Value, Async & Abort[Closed]] < Sync =
        impl.subscribeProgress(token)

    def unsubscribeProgress(token: Structure.Value)(using Frame): Unit < Async =
        impl.unsubscribeProgress(token)

    def cancel(id: JsonRpcId, reason: Maybe[String] = Absent)(using Frame): Unit < (Async & Abort[Closed]) =
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
        val id: JsonRpcId,
        val result: Out < (Async & Abort[JsonRpcError | Closed]),
        val progress: Stream[Structure.Value, Async],
        val cancel: Unit < (Async & Abort[Closed])
    )

    /** Configuration for a [[JsonRpcEndpoint]].
      *
      * Pass to [[JsonRpcEndpoint.init]] to control codec, cancellation, progress reporting,
      * unknown-method handling, message gating, concurrency limits, request timeouts, and
      * id-allocation strategy.
      *
      * All fields have sensible defaults; construct with `Config()` for standard JSON-RPC 2.0
      * behaviour or override individual fields as needed.
      *
      * @see [[JsonRpcEndpoint.init]]
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
    )

    def init(
        transport: JsonRpcTransport,
        methods: Seq[JsonRpcMethod[Async & Abort[JsonRpcError]]],
        config: Config = Config()
    )(using Frame): JsonRpcEndpoint < (Sync & Async & Scope) =
        internal.engine.JsonRpcEndpointImpl.init(transport, methods, config).map(new JsonRpcEndpoint(_))

end JsonRpcEndpoint
