package sttp.tapir.server.netty

import kyo.{Route as _, *}
import kyo.internal.KyoSttpMonad
import sttp.monad.MonadAsyncError
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.*
import sttp.tapir.server.netty.NettyKyoServerInterpreter.KyoRunAsync
import sttp.tapir.server.netty.internal.NettyKyoRequestBody
import sttp.tapir.server.netty.internal.NettyServerInterpreter
import sttp.tapir.server.netty.internal.NettyToResponseBody
import sttp.tapir.server.netty.internal.RunAsync

trait NettyKyoServerInterpreter:

    def nettyServerOptions: NettyKyoServerOptions

    def toRoute(se: ServerEndpoint[Any, KyoSttpMonad.M]): Route[KyoSttpMonad.M] =
        toRoute(List(se))

    def toRoute(
        ses: List[ServerEndpoint[Any, KyoSttpMonad.M]]
    ): Route[KyoSttpMonad.M] =
        given monad: MonadAsyncError[KyoSttpMonad.M] = KyoSttpMonad.instance
        NettyServerInterpreter.toRoute(
            ses,
            nettyServerOptions.interceptors,
            new NettyKyoRequestBody(nettyServerOptions.createFile),
            new NettyToResponseBody[KyoSttpMonad.M](),
            nettyServerOptions.deleteFile,
            KyoRunAsync(nettyServerOptions.forkExecution)
        )
    end toRoute
end NettyKyoServerInterpreter

object NettyKyoServerInterpreter:
    def apply(serverOptions: NettyKyoServerOptions = NettyKyoServerOptions.default())
        : NettyKyoServerInterpreter =
        new NettyKyoServerInterpreter:
            override def nettyServerOptions: NettyKyoServerOptions = serverOptions

    private class KyoRunAsync(forkExecution: Boolean) extends RunAsync[KyoSttpMonad.M]:
        override def apply(f: => KyoSttpMonad.M[Unit]): Unit =
            val exec =
                if forkExecution then
                    Fibers.init(f).map(_.get)
                else
                    f
            IOs.run(Fibers.run(exec).unit)
        end apply
    end KyoRunAsync
end NettyKyoServerInterpreter
