package kyo.internal

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import kyo.*

private[kyo] object NettyUtil:

    def channelFuture[A, S](nettyFuture: ChannelFuture)(f: Channel => A < S)(using Frame): A < (Async & S) =
        Promise.initWith[A, S] { p =>
            p.onComplete(_ => Sync.defer(discard(nettyFuture.cancel(true)))).andThen {
                nettyFuture.addListener((future: ChannelFuture) =>
                    discard {
                        import AllowUnsafe.embrace.danger
                        if future.isSuccess then p.unsafe.complete(Result.succeed(f(future.channel())))
                        else p.unsafe.complete(Result.panic(future.cause()))
                    }
                )
                p.get
            }
        }

    def future[A, B, S](f: io.netty.util.concurrent.Future[A])(cont: A => B < S)(using Frame): B < (Async & S) =
        Promise.initWith[B, S] { p =>
            p.onComplete(_ => Sync.defer(discard(f.cancel(true)))).andThen {
                f.addListener((future: io.netty.util.concurrent.Future[A]) =>
                    discard {
                        import AllowUnsafe.embrace.danger
                        if future.isSuccess then p.unsafe.complete(Result.succeed(cont(future.getNow)))
                        else p.unsafe.complete(Result.panic(future.cause()))
                    }
                )
                p.get
            }
        }

end NettyUtil
