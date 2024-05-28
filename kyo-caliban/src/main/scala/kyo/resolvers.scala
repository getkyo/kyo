package kyo

import caliban.*
import caliban.interop.tapir.*
import caliban.interop.tapir.TapirAdapter.*
import kyo.internal.KyoSttpMonad
import scala.concurrent.ExecutionContext
import sttp.monad.Canceler
import sttp.tapir.Codec.JsonCodec
import sttp.tapir.Endpoint
import sttp.tapir.capabilities.NoStreams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.*
import zio.*
import zio.stream.ZStream

opaque type Resolvers = Aborts[CalibanError] & ZIOs

object Resolvers:

    private given StreamConstructor[Nothing] =
        (_: ZStream[Any, Throwable, Byte]) => throw new Throwable("Streaming is not supported")

    def run[T, S](v: HttpInterpreter[Any, CalibanError] < (Resolvers & S)): NettyKyoServerBinding < (ZIOs & Aborts[CalibanError] & S) =
        run[T, S](NettyKyoServer())(v)

    def run[T, S](server: NettyKyoServer)(v: HttpInterpreter[Any, CalibanError] < (Resolvers & S))
        : NettyKyoServerBinding < (ZIOs & Aborts[CalibanError] & S) =
        ZIOs.get(ZIO.runtime[Any]).map(runtime => run(server, runtime)(v))

    def run[R, T, S](runner: Runner[R])(v: HttpInterpreter[Runner[R], CalibanError] < (Resolvers & S))(using
        tag: Tag[Runner[R]]
    ): NettyKyoServerBinding < (ZIOs & Aborts[CalibanError] & S) =
        run[R, T, S](NettyKyoServer(), runner)(v)

    def run[R, T, S](server: NettyKyoServer, runner: Runner[R])(v: HttpInterpreter[Runner[R], CalibanError] < (Resolvers & S))(using
        tag: Tag[Runner[R]]
    ): NettyKyoServerBinding < (ZIOs & Aborts[CalibanError] & S) =
        ZIOs.get(ZIO.runtime[Any]).map(runtime => run(server, runtime.withEnvironment(ZEnvironment(runner)))(v))

    def run[R, T, S](
        server: NettyKyoServer,
        runtime: Runtime[R]
    )(v: HttpInterpreter[R, CalibanError] < (Resolvers & S)): NettyKyoServerBinding < (ZIOs & Aborts[CalibanError] & S) =
        for
            interpreter <- v
            endpoints = interpreter.serverEndpoints[R, NoStreams](NoStreams).map(convertEndpoint(_, runtime))
            bindings <- IOs(server.addEndpoints(endpoints).start())
        yield bindings

    def get[R](api: GraphQL[R])(using
        requestCodec: JsonCodec[GraphQLRequest],
        responseValueCodec: JsonCodec[ResponseValue]
    ): HttpInterpreter[R, CalibanError] < Resolvers =
        ZIOs.get(api.interpreter.map(HttpInterpreter(_)))

    private def convertEndpoint[R, I](
        endpoint: ServerEndpoint.Full[Unit, Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], NoStreams, [x] =>> RIO[R, x]],
        runtime: Runtime[R]
    ): ServerEndpoint[Any, KyoSttpMonad.M] =
        ServerEndpoint[Unit, Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], Any, KyoSttpMonad.M](
            endpoint.endpoint.asInstanceOf[Endpoint[Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], Any]],
            _ => _ => Right(()),
            _ =>
                _ =>
                    req =>
                        val f = Unsafe.unsafe { implicit u => runtime.unsafe.runToFuture(endpoint.logic(zioMonadError)(())(req)) }
                        KyoSttpMonad.async[Either[TapirResponse, CalibanResponse[NoStreams.BinaryStream]]] { cb =>
                            f.onComplete(r => cb(r.toEither))(ExecutionContext.parasitic)
                            Canceler(() => f.cancel())
                        }
        )

end Resolvers
