package kyo.server.internal

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import kyo.*
import kyo.internal.Trace

object KyoUtil:

    val interrupted = IOs.fail(Fibers.Interrupted(summon[Trace]))

    def nettyChannelFutureToScala(nettyFuture: ChannelFuture): Channel < Fibers =
        Fibers.initPromise[Channel].map { p =>
            p.onComplete(_ => IOs(nettyFuture.cancel(true).unit)).andThen {
                nettyFuture.addListener((future: ChannelFuture) =>
                    discard {
                        IOs.run {
                            if future.isSuccess then p.complete(future.channel())
                            else if future.isCancelled then
                                p.complete(interrupted)
                            else p.complete(IOs.fail(future.cause()))
                        }
                    }
                )
                p.get
            }
        }

    def nettyFutureToScala[T](f: io.netty.util.concurrent.Future[T]): T < Fibers =
        Fibers.initPromise[T].map { p =>
            p.onComplete(_ => IOs(f.cancel(true)).unit).andThen {
                f.addListener((future: io.netty.util.concurrent.Future[T]) =>
                    discard {
                        IOs.run {
                            if future.isSuccess then p.complete(future.getNow)
                            else if future.isCancelled then
                                p.complete(interrupted)
                            else p.complete(IOs.fail(future.cause()))
                        }
                    }
                )
                p.get
            }
        }
end KyoUtil
