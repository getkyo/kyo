package kyo.server.internal

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import kyo.*

object KyoUtil:
    def nettyChannelFutureToScala(nettyFuture: ChannelFuture): Channel < Async =
        Promise.init[Nothing, Channel].map { p =>
            p.onComplete(_ => IO(nettyFuture.cancel(true)).unit).andThen {
                nettyFuture.addListener((future: ChannelFuture) =>
                    discard {
                        IO.run {
                            if future.isSuccess then p.unsafe.complete(Result.success(future.channel()))
                            else p.unsafe.complete(Result.panic(future.cause()))
                        }.eval
                    }
                )
                p.get
            }
        }

    def nettyFutureToScala[T](f: io.netty.util.concurrent.Future[T]): T < Async =
        Promise.init[Nothing, T].map { p =>
            p.onComplete(_ => IO(f.cancel(true)).unit).andThen {
                f.addListener((future: io.netty.util.concurrent.Future[T]) =>
                    discard {
                        IO.run {
                            if future.isSuccess then p.unsafe.complete(Result.success(future.getNow))
                            else p.unsafe.complete(Result.panic(future.cause()))
                        }.eval
                    }
                )
                p.get
            }
        }
end KyoUtil
