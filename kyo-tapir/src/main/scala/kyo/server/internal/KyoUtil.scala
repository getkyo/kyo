package kyo.server.internal

import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import kyo._

import kyo.routes._

import scala.concurrent.CancellationException

object KyoUtil {
  def nettyChannelFutureToScala(nettyFuture: ChannelFuture): Channel < Fibers =
    Fibers.initPromise[Channel].map { p =>
      p.onComplete(_ => IOs(nettyFuture.cancel(true).unit)).andThen {
        nettyFuture.addListener((future: ChannelFuture) =>
          IOs.run {
            if (future.isSuccess) p.complete(future.channel())
            else if (future.isCancelled) p.complete(IOs.fail(new CancellationException))
            else p.complete(IOs.fail(future.cause()))
          }
        )
        p.get
      }
    }

  def nettyFutureToScala[T](f: io.netty.util.concurrent.Future[T]): T < Fibers =
    Fibers.initPromise[T].map { p =>
      p.onComplete(_ => IOs(f.cancel(true)).unit).andThen {
        f.addListener((future: io.netty.util.concurrent.Future[T]) =>
          IOs.run {
            if (future.isSuccess) p.complete(future.getNow)
            else if (future.isCancelled) p.complete(IOs.fail(new CancellationException))
            else p.complete(IOs.fail(future.cause()))
          }
        )
        p.get
      }
    }
}
