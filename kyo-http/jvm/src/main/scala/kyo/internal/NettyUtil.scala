package kyo.internal

import io.netty.channel.Channel as NettyChannel
import io.netty.channel.ChannelFuture
import io.netty.handler.codec.http.HttpHeaders as NettyHeaders
import kyo.*

private[kyo] object NettyUtil:

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
        continue(nettyFuture)(_ => f)

    def await(nettyFuture: ChannelFuture)(using Frame): Unit < Async =
        continue(nettyFuture)(_ => ())

    def await[A](f: io.netty.util.concurrent.Future[A])(using Frame): Unit < Async =
        continue(f)(_ => ())

    def nettyHeadersToKyo(headers: io.netty.handler.codec.http.HttpHeaders): HttpHeaders =
        headers match
            case flat: FlatNettyHttpHeaders => flat.toKyoHeaders
            case other =>
                val builder = ChunkBuilder.init[String]
                val iter    = other.iteratorAsString()
                while iter.hasNext do
                    val entry = iter.next()
                    discard(builder += entry.getKey)
                    discard(builder += entry.getValue)
                end while
                HttpHeaders.fromChunk(builder.result())

    def launchFiber[A](v: => A < Async)(using AllowUnsafe, Frame): Fiber[A, Any] =
        Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(v))

end NettyUtil
