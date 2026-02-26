package kyo.internal

import io.netty.channel.Channel as NettyChannel
import io.netty.channel.ChannelFuture
import io.netty.handler.codec.http.HttpHeaders as NettyHeaders
import kyo.*
import kyo.discard

private[kyo] object NettyUtil:

    private val unitResult: Result[Nothing, Unit < Any] = Result.succeed(())

    private[kyo] def panicFromCause(cause: Throwable, mapError: Throwable => Throwable = identity): Result.Panic =
        Maybe(cause) match
            case Absent       => Result.Panic(mapError(new Exception("Netty future failed with unknown cause")))
            case Present(err) => Result.Panic(mapError(err))

    def continue[A, S](nettyFuture: ChannelFuture, mapError: Throwable => Throwable = identity)(f: NettyChannel => A < S)(using
        Frame
    ): A < (Async & S) =
        Sync.Unsafe.defer {
            val p = Promise.Unsafe.init[A, S]()
            p.onComplete(_ => discard(nettyFuture.cancel(true)))
            nettyFuture.addListener((future: ChannelFuture) =>
                discard {
                    if future.isSuccess then p.complete(Result.succeed(f(future.channel())))
                    else p.complete(panicFromCause(future.cause(), mapError))
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

    inline def awaitWith[A, S](nettyFuture: ChannelFuture)(inline f: => A < S)(using Frame): A < (Async & S) =
        Sync.Unsafe.defer {
            val p = Promise.Unsafe.init[A, S]()
            p.onComplete(_ => discard(nettyFuture.cancel(true)))
            nettyFuture.addListener((future: ChannelFuture) =>
                discard {
                    if future.isSuccess then p.complete(Result.succeed(f))
                    else p.complete(panicFromCause(future.cause()))
                }
            )
            p.safe.get
        }

    def await(nettyFuture: ChannelFuture)(using Frame): Unit < Async =
        awaitWith(nettyFuture)(())

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
            import scala.annotation.tailrec
            val arr  = new Array[String](headerCount * 2)
            val iter = nettyHeaders.iteratorAsString()
            @tailrec def loop(i: Int): Unit =
                if iter.hasNext then
                    val entry = iter.next()
                    arr(i) = entry.getKey
                    arr(i + 1) = entry.getValue
                    loop(i + 2)
            loop(0)
            HttpHeaders.fromChunk(Chunk.fromNoCopy(arr))
        end if
    end extractHeaders

    def launchFiber[A](v: => A < Async)(using AllowUnsafe, Frame): Fiber[A, Any] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(v))

end NettyUtil
