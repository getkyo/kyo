package kyo.server

import kyo.*

import kyo.routes.*
import kyo.server.internal.*

import sttp.monad.MonadError
import sttp.monad.syntax.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.RequestResult
import sttp.tapir.server.interceptor.reject.RejectInterceptor
import sttp.tapir.server.interpreter.BodyListener
import sttp.tapir.server.interpreter.FilterServerEndpoints
import sttp.tapir.server.interpreter.ServerInterpreter
import sttp.tapir.server.netty.NettyResponse
import sttp.tapir.server.netty.NettyServerRequest
import sttp.tapir.server.netty.Route
import sttp.tapir.server.netty.internal.NettyBodyListener
import sttp.tapir.server.netty.internal.RunAsync
import sttp.tapir.server.netty.internal.*
import kyo.internal.KyoSttpMonad.instance
import kyo.internal.KyoSttpMonad

trait NettyKyoServerInterpreter:
    def nettyServerOptions: NettyKyoServerOptions

    def toRoute(ses: List[ServerEndpoint[Any, KyoSttpMonad.M]]): Route[KyoSttpMonad.M] =

        given bodyListener: BodyListener[KyoSttpMonad.M, NettyResponse] =
            new NettyBodyListener(NettyKyoServer.runAsync)

        val interceptors = nettyServerOptions.interceptors
        val createFile   = nettyServerOptions.createFile
        val deleteFile   = nettyServerOptions.deleteFile

        val serverInterpreter = new ServerInterpreter[Any, KyoSttpMonad.M, NettyResponse, Any](
            FilterServerEndpoints(ses),
            new NettyKyoRequestBody(createFile),
            new NettyKyoToResponseBody(
                delegate = new NettyToResponseBody
            ),
            RejectInterceptor.disableWhenSingleEndpoint(interceptors, ses),
            deleteFile
        )

        val handler: Route[KyoSttpMonad.M] = (request: NettyServerRequest) =>
            serverInterpreter(request)
                .map {
                    case RequestResult.Response(response) => Some(response)
                    case RequestResult.Failure(_)         => None
                }

        handler
    end toRoute
end NettyKyoServerInterpreter

object NettyKyoServerInterpreter:
    def apply(): NettyKyoServerInterpreter =
        new NettyKyoServerInterpreter:
            override def nettyServerOptions: NettyKyoServerOptions =
                NettyKyoServerOptions.default()
    def apply(options: NettyKyoServerOptions): NettyKyoServerInterpreter =
        new NettyKyoServerInterpreter:
            override def nettyServerOptions: NettyKyoServerOptions = options
end NettyKyoServerInterpreter
