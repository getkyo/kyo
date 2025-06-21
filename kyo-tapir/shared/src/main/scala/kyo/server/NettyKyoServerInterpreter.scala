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
        given monad: MonadAsyncError[KyoSttpMonad.M] = KyoSttpMonad
        val runAsync                                 = KyoRunAsync(nettyServerOptions.forkExecution)
        NettyServerInterpreter.toRoute(
            ses,
            nettyServerOptions.interceptors,
            new NettyKyoRequestBody(nettyServerOptions.createFile),
            new NettyToResponseBody[KyoSttpMonad.M](runAsync),
            nettyServerOptions.deleteFile,
            runAsync
        )
    end toRoute
end NettyKyoServerInterpreter

object NettyKyoServerInterpreter:
    def apply(serverOptions: NettyKyoServerOptions = NettyKyoServerOptions.default()): NettyKyoServerInterpreter =
        new NettyKyoServerInterpreter:
            override def nettyServerOptions: NettyKyoServerOptions = serverOptions

    private class KyoRunAsync(forkExecution: Boolean) extends RunAsync[KyoSttpMonad.M]:
        override def apply(f: => KyoSttpMonad.M[Unit]): Unit =
            val exec =
                if forkExecution then
                    Async.run(f).map(_.get)
                else
                    f
            import AllowUnsafe.embrace.danger
            val _ = Sync.Unsafe.evalOrThrow(Async.run(exec))
        end apply
    end KyoRunAsync
end NettyKyoServerInterpreter
