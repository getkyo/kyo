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
import zio.RIO
import zio.Runtime
import zio.Tag
import zio.Unsafe
import zio.ZEnvironment
import zio.ZIO
import zio.stream.ZStream

opaque type Resolver = Abort[CalibanError] & ZIOs

object Resolver:

    private given StreamConstructor[Nothing] =
        (_: ZStream[Any, Throwable, Byte]) => throw new Throwable("Streaming is not supported")

    def run[T, S](v: HttpInterpreter[Any, CalibanError] < (Resolver & S)): NettyKyoServerBinding < (ZIOs & Abort[CalibanError] & S) =
        run[T, S](NettyKyoServer())(v)

    def run[T, S](server: NettyKyoServer)(v: HttpInterpreter[Any, CalibanError] < (Resolver & S))
        : NettyKyoServerBinding < (ZIOs & Abort[CalibanError] & S) =
        ZIOs.get(ZIO.runtime[Any]).map(runtime => run(server, runtime)(v))

    def run[R, T, S](runner: Runner[R])(v: HttpInterpreter[Runner[R], CalibanError] < (Resolver & S))(using
        tag: Tag[Runner[R]]
    ): NettyKyoServerBinding < (ZIOs & Abort[CalibanError] & S) =
        run[R, T, S](NettyKyoServer(), runner)(v)

    def run[R, T, S](server: NettyKyoServer, runner: Runner[R])(v: HttpInterpreter[Runner[R], CalibanError] < (Resolver & S))(using
        tag: Tag[Runner[R]]
    ): NettyKyoServerBinding < (ZIOs & Abort[CalibanError] & S) =
        ZIOs.get(ZIO.runtime[Any]).map(runtime => run(server, runtime.withEnvironment(ZEnvironment(runner)))(v))

    def run[R, T, S](
        server: NettyKyoServer,
        runtime: Runtime[R]
    )(v: HttpInterpreter[R, CalibanError] < (Resolver & S)): NettyKyoServerBinding < (ZIOs & Abort[CalibanError] & S) =
        for
            interpreter <- v
            endpoints = interpreter.serverEndpoints[R, NoStreams](NoStreams).map(convertEndpoint(_, runtime))
            bindings <- IO(server.addEndpoints(endpoints).start())
        yield bindings

    def get[R](api: GraphQL[R])(using
        requestCodec: JsonCodec[GraphQLRequest],
        responseValueCodec: JsonCodec[ResponseValue]
    ): HttpInterpreter[R, CalibanError] < Resolver =
        ZIOs.get(api.interpreter.map(HttpInterpreter(_)))

    private val rightUnit: Right[Nothing, Unit] = Right(())

    private def convertEndpoint[R, I](
        endpoint: ServerEndpoint.Full[Unit, Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], NoStreams, [x] =>> RIO[R, x]],
        runtime: Runtime[R]
    ): ServerEndpoint[Any, KyoSttpMonad.M] =
        ServerEndpoint[Unit, Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], Any, KyoSttpMonad.M](
            endpoint.endpoint.asInstanceOf[Endpoint[Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], Any]],
            _ => _ => rightUnit,
            _ =>
                _ =>
                    req =>
                        val f = Unsafe.unsafely { runtime.unsafe.runToFuture(endpoint.logic(zioMonadError)(())(req)) }
                        KyoSttpMonad.async { cb =>
                            f.onComplete(r => cb(r.toEither))(ExecutionContext.parasitic)
                            Canceler { () =>
                                val _ = f.cancel()
                                ()
                            }
                        }
        )

end Resolver
