package kyo

import caliban.*
import caliban.interop.tapir.*
import caliban.interop.tapir.TapirAdapter.*
import kyo.internal.KyoSttpMonad
import scala.concurrent.ExecutionContext
import sttp.monad.Canceler
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

/** Effect for interacting with Caliban GraphQL resolvers. */
opaque type Resolvers <: (Abort[CalibanError] & Async) = Abort[CalibanError] & Async

object Resolvers:

    private given StreamConstructor[Nothing] =
        (_: ZStream[Any, Throwable, Byte]) => throw new Throwable("Streaming is not supported")

    /** Runs a GraphQL server with default NettyKyoServer configuration.
      *
      * @param v
      *   The HttpInterpreter to be used
      * @param Frame
      *   Implicit Frame parameter
      * @return
      *   A NettyKyoServerBinding wrapped in ZIOs and Abort effects
      */
    def run[A, S](v: HttpInterpreter[Any, CalibanError] < (Resolvers & S))(
        using Frame
    ): NettyKyoServerBinding < (Async & Abort[CalibanError] & S) =
        run[A, S](NettyKyoServer())(v)

    /** Runs a GraphQL server with a custom NettyKyoServer configuration.
      *
      * @param server
      *   The custom NettyKyoServer configuration
      * @param v
      *   The HttpInterpreter to be used
      * @param Frame
      *   Implicit Frame parameter
      * @return
      *   A NettyKyoServerBinding wrapped in ZIOs and Abort effects
      */
    def run[A, S](server: NettyKyoServer)(v: HttpInterpreter[Any, CalibanError] < (Resolvers & S))(
        using Frame
    ): NettyKyoServerBinding < (Async & Abort[CalibanError] & S) =
        ZIOs.get(ZIO.runtime[Any]).map(runtime => run(server, runtime)(v))

    /** Runs a GraphQL server with a custom Runner.
      *
      * @param runner
      *   The custom Runner to be used
      * @param v
      *   The HttpInterpreter to be used
      * @param tag
      *   Implicit Tag for Runner[R]
      * @param frame
      *   Implicit Frame parameter
      * @return
      *   A NettyKyoServerBinding wrapped in ZIOs and Abort effects
      */
    def run[R, A, S](runner: Runner[R])(v: HttpInterpreter[Runner[R], CalibanError] < (Resolvers & S))(
        using
        tag: Tag[Runner[R]],
        frame: Frame
    ): NettyKyoServerBinding < (Async & Abort[CalibanError] & S) =
        run[R, A, S](NettyKyoServer(), runner)(v)

    /** Runs a GraphQL server with a custom NettyKyoServer configuration and Runner.
      *
      * @param server
      *   The custom NettyKyoServer configuration
      * @param runner
      *   The custom Runner to be used
      * @param v
      *   The HttpInterpreter to be used
      * @param tag
      *   Implicit Tag for Runner[R]
      * @param frame
      *   Implicit Frame parameter
      * @return
      *   A NettyKyoServerBinding wrapped in ZIOs and Abort effects
      */
    def run[R, A, S](server: NettyKyoServer, runner: Runner[R])(v: HttpInterpreter[Runner[R], CalibanError] < (Resolvers & S))(
        using
        tag: Tag[Runner[R]],
        frame: Frame
    ): NettyKyoServerBinding < (Async & Abort[CalibanError] & S) =
        ZIOs.get(ZIO.runtime[Any]).map(runtime => run(server, runtime.withEnvironment(ZEnvironment(runner)))(v))

    /** Runs a GraphQL server with a custom NettyKyoServer configuration and Runtime.
      *
      * @param server
      *   The custom NettyKyoServer configuration
      * @param runtime
      *   The custom Runtime to be used
      * @param v
      *   The HttpInterpreter to be used
      * @param Frame
      *   Implicit Frame parameter
      * @return
      *   A NettyKyoServerBinding wrapped in ZIOs and Abort effects
      */
    def run[R, A, S](
        server: NettyKyoServer,
        runtime: Runtime[R]
    )(v: HttpInterpreter[R, CalibanError] < (Resolvers & S))(using Frame): NettyKyoServerBinding < (Async & Abort[CalibanError] & S) =
        for
            interpreter <- v
            endpoints = interpreter.serverEndpoints[R, NoStreams](NoStreams).map(convertEndpoint(_, runtime))
            bindings <- Sync(server.addEndpoints(endpoints).start())
        yield bindings

    /** Creates an HttpInterpreter from a GraphQL API.
      *
      * @param api
      *   The GraphQL API to be interpreted
      * @param requestCodec
      *   Implicit JsonCodec for GraphQLRequest
      * @param responseValueCodec
      *   Implicit JsonCodec for ResponseValue
      * @param Frame
      *   Implicit Frame parameter
      * @return
      *   An HttpInterpreter wrapped in Resolvers effect
      */
    def get[R](api: GraphQL[R])(using Frame): HttpInterpreter[R, CalibanError] < Resolvers =
        ZIOs.get(api.interpreter.map(HttpInterpreter(_)))

    private val rightUnit: Right[Nothing, Unit] = Right(())

    private def convertEndpoint[R, I](
        endpoint: ServerEndpoint.Full[Unit, Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], NoStreams, [x] =>> RIO[R, x]],
        runtime: Runtime[R]
    )(using Frame): ServerEndpoint[Any, KyoSttpMonad.M] =
        ServerEndpoint[Unit, Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], Any, KyoSttpMonad.M](
            endpoint.endpoint.asInstanceOf[Endpoint[Unit, I, TapirResponse, CalibanResponse[NoStreams.BinaryStream], Any]],
            _ => _ => rightUnit,
            _ =>
                _ =>
                    req =>
                        val f = Unsafe.unsafely { runtime.unsafe.runToFuture(endpoint.logic(zioMonadError)(())(req)) }
                        KyoSttpMonad.async { cb =>
                            f.onComplete(r => cb(r.toEither))(using ExecutionContext.parasitic)
                            Canceler { () =>
                                val _ = f.cancel()
                                ()
                            }
                        }
        )

    given isolate: Isolate.Contextual[Resolvers, Abort[CalibanError] & Async] =
        Isolate.Contextual.derive[Resolvers, Abort[CalibanError] & Async]

end Resolvers
