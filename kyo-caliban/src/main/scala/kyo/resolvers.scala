package kyo

import caliban.*
import caliban.interop.tapir.*
import caliban.interop.tapir.TapirAdapter.*
import kyo.internal.KyoSttpMonad
import sttp.tapir.Codec.JsonCodec
import sttp.tapir.Endpoint
import sttp.tapir.capabilities.NoStreams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.*
import zio.*
import zio.stream.ZStream

type Resolvers >: Resolvers.Effects <: Resolvers.Effects

object Resolvers:

    type Effects = Sums[GraphQL[Any]]

    private given StreamConstructor[Nothing] =
        (_: ZStream[Any, Throwable, Byte]) => throw new Throwable("Streaming is not supported")

    def run[T, S](v: Unit < (Resolvers & S))(using
        requestCodec: JsonCodec[GraphQLRequest],
        responseValueCodec: JsonCodec[ResponseValue]
    ): NettyKyoServerBinding < (Fibers & ZIOs & Aborts[Throwable] & S) =
        run[T, S](NettyKyoServer())(v)

    def run[T, S](server: NettyKyoServer)(v: Unit < (Resolvers & S))(using
        requestCodec: JsonCodec[GraphQLRequest],
        responseValueCodec: JsonCodec[ResponseValue]
    ): NettyKyoServerBinding < (Fibers & ZIOs & Aborts[Throwable] & S) =
        for
            (resolvers, _) <- Sums.run[GraphQL[Any]].apply[Unit, Fibers & S](v)
            apis = resolvers.toIndexed
            api <- if apis.isEmpty then Aborts.fail(new Throwable("You need at least one resolver"))
            else apis.tail.foldLeft(apis.head)(_ |+| _)
            interpreter <- ZIOs.get(api.interpreter)
            runtime     <- ZIOs.get(ZIO.runtime[Any])
            httpInterpreter = HttpInterpreter(interpreter)
            endpoints       = httpInterpreter.serverEndpoints[Any, NoStreams](NoStreams)
            bindings <- IOs(server.addEndpoints(endpoints.map(e => convertEndpoint(e, runtime))).start())
        yield bindings

    end run

    def add(api: GraphQL[Any]): Unit < Resolvers =
        Sums.add(api)

    def collect(init: (Unit < Resolvers)*): Unit < Resolvers =
        Seqs.collect(init).unit

    private def convertEndpoint[I](
        endpoint: ServerEndpoint.Full[Unit, Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], NoStreams, [x] =>> RIO[
            Any,
            x
        ]],
        runtime: Runtime[Any]
    ): ServerEndpoint[Any, KyoSttpMonad.M] =
        ServerEndpoint[Unit, Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], Any, KyoSttpMonad.M](
            endpoint.endpoint.asInstanceOf[Endpoint[Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], Any]],
            _ => _ => Right(()),
            _ => _ => req => Unsafe.unsafe { implicit u => runtime.unsafe.run(endpoint.logic(zioMonadError)(())(req)).getOrThrow() }
        )

end Resolvers
