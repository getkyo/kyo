package kyo.server.internal

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import kyo.*

object KyoUtil:
    def nettyChannelFutureToScala(nettyFuture: ChannelFuture)(using Frame): Channel < Async =
        Promise.initWith[Nothing, Channel] { p =>
            p.onComplete(_ => Sync(discard(nettyFuture.cancel(true)))).andThen {
                nettyFuture.addListener((future: ChannelFuture) =>
                    discard {
                        import AllowUnsafe.embrace.danger
                        if future.isSuccess then p.unsafe.complete(Result.succeed(future.channel()))
                        else p.unsafe.complete(Result.panic(future.cause()))
                    }
                )
                p.get
            }
        }

    def nettyFutureToScala[A](f: io.netty.util.concurrent.Future[A])(using Frame): A < Async =
        Promise.initWith[Nothing, A] { p =>
            p.onComplete(_ => Sync(discard(f.cancel(true)))).andThen {
                f.addListener((future: io.netty.util.concurrent.Future[A]) =>
                    discard {
                        import AllowUnsafe.embrace.danger
                        if future.isSuccess then p.unsafe.complete(Result.succeed(future.getNow))
                        else p.unsafe.complete(Result.panic(future.cause()))
                    }
                )
                p.get
            }
        }
end KyoUtil
