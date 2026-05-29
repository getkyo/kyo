// flow-allow: PUBLIC primary user-facing endpoint surface
package kyo

import kyo.Stream

// flow-allow: Hub.scala:22 smart-constructor pattern; init through JsonRpcEndpoint.init
final class JsonRpcEndpoint private[kyo] (private[kyo] val impl: internal.JsonRpcEndpointImpl):

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

    def close(using Frame): Unit < Async = impl.close(Duration.Zero)

    def close(gracePeriod: Duration)(using Frame): Unit < Async = impl.close(gracePeriod)

    def closeNow(using Frame): Unit < Async = impl.close(Duration.Zero)

end JsonRpcEndpoint

object JsonRpcEndpoint:

    // flow-allow: Hub.scala:22 smart-constructor pattern; Pending built only by JsonRpcEndpointImpl.callWithProgress
    final class Pending[Out] private[kyo] (
        val id: JsonRpcId,
        val result: Out < (Async & Abort[JsonRpcError | Closed]),
        val progress: Stream[Structure.Value, Async],
        val cancel: Unit < (Async & Abort[Closed])
    )

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
        internal.JsonRpcEndpointImpl.init(transport, methods, config).map(new JsonRpcEndpoint(_))

end JsonRpcEndpoint
