package kyo.http2.internal

import io.netty.channel.Channel as NettyChannel
import io.netty.channel.ChannelFuture
import io.netty.handler.codec.http.HttpHeaders as NettyHeaders
import kyo.<
import kyo.Abort
import kyo.Absent
import kyo.AllowUnsafe
import kyo.Async
import kyo.Chunk
import kyo.Duration
import kyo.Fiber
import kyo.Frame
import kyo.Maybe
import kyo.Present
import kyo.Promise
import kyo.Result
import kyo.Sync
import kyo.discard
import kyo.http2.*

private[http2] object NettyUtil:

    private val unitResult: Result[Nothing, Unit < Any] = Result.succeed(())

    private[http2] def panicFromCause(cause: Throwable): Result.Panic =
        Maybe(cause) match
            case Absent => Result.Panic(new Exception("Netty future failed with unknown cause"))
            case Present(err) =>
                val msg = Maybe(err.getMessage)
                if err.isInstanceOf[java.net.BindException] ||
                    msg.exists(_.contains("bind"))
                then
                    Result.Panic(new java.net.BindException(s"Server bind failed: ${msg.getOrElse("unknown")}"))
                else Result.Panic(err)
                end if

    def continue[A, S](nettyFuture: ChannelFuture)(f: NettyChannel => A < S)(using Frame): A < (Async & S) =
        Sync.Unsafe.defer {
            val p = Promise.Unsafe.init[A, S]()
            p.onComplete(_ => discard(nettyFuture.cancel(true)))
            nettyFuture.addListener((future: ChannelFuture) =>
                discard {
                    if future.isSuccess then p.complete(Result.succeed(f(future.channel())))
                    else p.complete(panicFromCause(future.cause()))
                }
            )
            p.safe.get
        }

    def continue[A, B, S](f: io.netty.util.concurrent.Future[A])(cont: A => B < S)(using Frame): B < (Async & S) =
        Sync.Unsafe.defer {
            val p = Promise.Unsafe.init[B, S]()
            p.onComplete(_ => discard(f.cancel(true)))
            f.addListener((future: io.netty.util.concurrent.Future[A]) =>
                discard {
                    if future.isSuccess then p.complete(Result.succeed(cont(future.getNow)))
                    else p.complete(panicFromCause(future.cause()))
                }
            )
            p.safe.get
        }

    def await(nettyFuture: ChannelFuture)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            val p = Promise.Unsafe.init[Unit, Any]()
            p.onComplete(_ => discard(nettyFuture.cancel(true)))
            nettyFuture.addListener((future: ChannelFuture) =>
                discard {
                    if future.isSuccess then p.complete(unitResult)
                    else p.complete(panicFromCause(future.cause()))
                }
            )
            p.safe.get
        }

    def await[A](f: io.netty.util.concurrent.Future[A])(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            val p = Promise.Unsafe.init[Unit, Any]()
            p.onComplete(_ => discard(f.cancel(true)))
            f.addListener((future: io.netty.util.concurrent.Future[A]) =>
                discard {
                    if future.isSuccess then p.complete(unitResult)
                    else p.complete(panicFromCause(future.cause()))
                }
            )
            p.safe.get
        }

    def extractHeaders(nettyHeaders: NettyHeaders): HttpHeaders =
        val headerCount = nettyHeaders.size()
        if headerCount == 0 then HttpHeaders.empty
        else
            val arr  = new Array[String](headerCount * 2)
            val iter = nettyHeaders.iteratorAsString()
            var i    = 0
            while iter.hasNext do
                val entry = iter.next()
                arr(i) = entry.getKey
                arr(i + 1) = entry.getValue
                i += 2
            end while
            HttpHeaders.fromChunk(Chunk.fromNoCopy(arr))
        end if
    end extractHeaders

    def launchFiber[A](v: => A < Async)(using AllowUnsafe, Frame): Fiber[A, Any] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(v))

end NettyUtil
