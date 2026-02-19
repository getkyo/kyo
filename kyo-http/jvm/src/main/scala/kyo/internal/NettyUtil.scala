package kyo.internal

import io.netty.channel.Channel as NettyChannel
import io.netty.channel.ChannelFuture
import kyo.*

private[kyo] object NettyUtil:

    def continue[A, S](nettyFuture: ChannelFuture)(f: NettyChannel => A < S)(using Frame): A < (Async & S) =
        Promise.initWith[A, S] { p =>
            p.onComplete(_ => Sync.defer(discard(nettyFuture.cancel(true)))).andThen {
                nettyFuture.addListener((future: ChannelFuture) =>
                    discard {
                        import AllowUnsafe.embrace.danger
                        if future.isSuccess then p.unsafe.complete(Result.succeed(f(future.channel())))
                        else
                            val cause = future.cause()
                            val msg   = if cause != null then cause.getMessage else "unknown error"
                            if cause.isInstanceOf[java.net.BindException] ||
                                (msg != null && msg.contains("bind"))
                            then
                                p.unsafe.complete(Result.panic(
                                    new java.net.BindException(s"Server bind failed: $msg")
                                ))
                            else p.unsafe.complete(Result.panic(cause))
                            end if
                        end if
                    }
                )
                p.get
            }
        }

    def continue[A, B, S](f: io.netty.util.concurrent.Future[A])(cont: A => B < S)(using Frame): B < (Async & S) =
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

    /** Await a ChannelFuture, discarding its result. */
    def await(nettyFuture: ChannelFuture)(using Frame): Unit < Async =
        continue(nettyFuture)(_ => ())

    /** Await a Netty Future, discarding its result. */
    def await[A](f: io.netty.util.concurrent.Future[A])(using Frame): Unit < Async =
        continue(f)(_ => ())

end NettyUtil
