package kyo.server.internal

import kyo._
import kyo.ios._
import kyo.tries._
import kyo.routes._
import kyo.concurrent.fibers._
import io.netty.channel.{Channel, ChannelFuture}

import scala.concurrent.CancellationException

object KyoUtil {
  def nettyChannelFutureToScala(nettyFuture: ChannelFuture): Channel > (Fibers with IOs) =
    Fibers.initPromise[Channel].map { p =>
      p.onComplete(_ => IOs(nettyFuture.cancel(true))).andThen {
        IOs {
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
    }

  def nettyFutureToScala[T](f: io.netty.util.concurrent.Future[T]): T > (Fibers with IOs) = {
    Fibers.initPromise[T].map { p =>
      p.onComplete(_ => IOs(f.cancel(true))).andThen {
        IOs {
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
  }
}
