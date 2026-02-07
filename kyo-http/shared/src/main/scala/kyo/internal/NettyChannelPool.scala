package kyo.internal

import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel as NettyChannel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.ssl.SslContext
import java.net.InetSocketAddress
import kyo.*
import scala.annotation.tailrec

/** Custom channel pool replacing Netty's FixedChannelPool/SimpleChannelPool.
  *
  * Eliminates event-loop round-trips for pool operations by using kyo Channel for idle connection storage and AtomicInt for connection
  * counting. Health checks (channel.isActive) run directly on the calling thread instead of being dispatched to the event loop.
  */
final private[kyo] class NettyChannelPool private (
    private val idleChannels: Channel.Unsafe[NettyChannel],
    private val totalCount: AtomicInt.Unsafe,
    private val maxConnections: Int,
    private val remoteBootstrap: Bootstrap,
    private val host: String,
    private val port: Int
):

    /** Acquire a channel from the pool.
      *
      * Hot path (idle reuse): poll from Channel + isActive check on calling thread. No event loop interaction.
      *
      * Cold path (new connection): bootstrap.connect() through event loop (unavoidable I/O).
      *
      * Wait path (pool exhausted, bounded only): suspends fiber via Channel.take until a connection is released.
      */
    def acquire()(using Frame): NettyChannel < (Async & Abort[HttpError]) =
        Sync.Unsafe {
            pollActive() match
                case Present(ch) => ch
                case Absent =>
                    if tryIncrementCount() then
                        connectNew()
                    else
                        waitForActive()
        }

    /** Release a channel back to the pool. Synchronous, suitable for Sync.ensure. */
    def release(ch: NettyChannel)(using AllowUnsafe, Frame): Unit =
        idleChannels.offer(ch) match
            case Result.Success(true) => ()
            case _                    =>
                // Pool full or closed — discard the connection
                discard(totalCount.decrementAndGet())
                discard(ch.close())

    /** Close the pool, closing all idle connections. Active connections will be closed on release. */
    def close()(using AllowUnsafe, Frame): Unit =
        idleChannels.close() match
            case Present(remaining) =>
                remaining.foreach { ch =>
                    discard(totalCount.decrementAndGet())
                    discard(ch.close())
                }
            case Absent => ()

    /** Poll idle channels, discarding any that are no longer active. */
    @tailrec
    private def pollActive()(using AllowUnsafe, Frame): Maybe[NettyChannel] =
        idleChannels.poll() match
            case Result.Success(Present(ch)) =>
                if ch.isActive() then Present(ch)
                else
                    discard(totalCount.decrementAndGet())
                    discard(ch.close())
                    pollActive()
            case _ => Absent

    /** CAS-increment totalCount if under maxConnections. Always succeeds for unlimited pools. */
    private def tryIncrementCount()(using AllowUnsafe): Boolean =
        @tailrec def loop(): Boolean =
            val current = totalCount.get()
            if current >= maxConnections then false
            else if totalCount.compareAndSet(current, current + 1) then true
            else loop()
        end loop
        loop()
    end tryIncrementCount

    /** Create a new connection via bootstrap.connect(). Decrements totalCount on failure. */
    private def connectNew()(using Frame, AllowUnsafe): NettyChannel < (Async & Abort[HttpError]) =
        val promise       = Promise.Unsafe.init[NettyChannel, Abort[HttpError]]()
        val connectFuture = remoteBootstrap.connect()
        discard {
            connectFuture.addListener { (future: ChannelFuture) =>
                import AllowUnsafe.embrace.danger
                if future.isSuccess then
                    discard(promise.complete(Result.succeed(future.channel())))
                else
                    discard(totalCount.decrementAndGet())
                    discard(promise.complete(Result.fail(
                        HttpError.ConnectionFailed(host, port, future.cause())
                    )))
                end if
            }
        }
        promise.safe.get
    end connectNew

    /** Wait for a channel to be released to the idle pool. Suspends the fiber via Channel.take. */
    private def waitForActive()(using Frame, AllowUnsafe): NettyChannel < (Async & Abort[HttpError]) =
        Abort.run[Closed](idleChannels.safe.take).map {
            case Result.Success(ch) =>
                if ch.isActive() then ch
                else
                    Sync.Unsafe {
                        discard(totalCount.decrementAndGet())
                        discard(ch.close())
                    }.andThen(acquire())
            case Result.Failure(_) =>
                Abort.fail(HttpError.ConnectionFailed(
                    host,
                    port,
                    new RuntimeException("Connection pool is closed")
                ))
            case Result.Panic(e) =>
                Abort.fail(HttpError.fromThrowable(e, host, port))
        }
    end waitForActive

end NettyChannelPool

private[kyo] object NettyChannelPool:

    /** Default idle channel capacity for unlimited pools. */
    private inline def DefaultIdleCapacity = 256

    def init(
        bootstrap: Bootstrap,
        sslContext: SslContext,
        host: String,
        port: Int,
        ssl: Boolean,
        maxConnections: Maybe[Int],
        maxResponseSizeBytes: Int,
        connectTimeout: Maybe[Duration]
    )(using AllowUnsafe, Frame): NettyChannelPool =
        val capacity     = maxConnections.getOrElse(DefaultIdleCapacity)
        val idleChannels = Channel.Unsafe.init[NettyChannel](capacity)
        val totalCount   = AtomicInt.Unsafe.init(0)
        val remoteBootstrap = bootstrap.clone()
            .remoteAddress(new InetSocketAddress(host, port))
        connectTimeout.foreach { timeout =>
            discard(remoteBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Integer.valueOf(timeout.toMillis.toInt)))
        }
        discard(remoteBootstrap.handler(new ChannelInitializer[SocketChannel]:
            override def initChannel(ch: SocketChannel): Unit =
                val pipeline = ch.pipeline()
                if ssl then
                    discard(pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(), host, port)))
                discard(pipeline.addLast("codec", new HttpClientCodec()))
                discard(pipeline.addLast("aggregator", new HttpObjectAggregator(maxResponseSizeBytes)))))
        new NettyChannelPool(
            idleChannels,
            totalCount,
            maxConnections.getOrElse(Int.MaxValue),
            remoteBootstrap,
            host,
            port
        )
    end init

end NettyChannelPool
