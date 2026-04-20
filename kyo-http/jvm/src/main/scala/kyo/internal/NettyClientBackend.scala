package kyo.internal

import io.netty.bootstrap.Bootstrap
import io.netty.channel.{Channel as NettyChannel, *}
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpDecoderConfig
import io.netty.handler.ssl.SslContextBuilder
import io.netty.util.concurrent.DefaultThreadFactory
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.internal.NettyTransport

final class NettyClientBackend extends HttpBackend.Client:

    type Connection = NettyConnection

    private val threadFactory = DefaultThreadFactory("kyo-http", true)
    private val workerGroup   = MultiThreadIoEventLoopGroup(threadFactory, NettyTransport.ioHandlerFactory)
    private val bootstrap     = Bootstrap().group(workerGroup).channel(NettyTransport.socketChannelClass)
    private val sslContext    = SslContextBuilder.forClient().build()

    def connectWith[A](
        host: String,
        port: Int,
        ssl: Boolean,
        connectTimeout: Maybe[Duration]
    )(
        f: NettyConnection => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        Sync.Unsafe.defer {
            val b = bootstrap.clone()
                .remoteAddress(new InetSocketAddress(host, port))
                .handler(new ChannelInitializer[SocketChannel]:
                    override def initChannel(ch: SocketChannel): Unit =
                        val pipeline = ch.pipeline()
                        if ssl then
                            discard(pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(), host, port)))
                        discard(pipeline.addLast(
                            "codec",
                            new HttpClientCodec(
                                new HttpDecoderConfig()
                                    .setHeadersFactory(FlatNettyHttpHeaders.factory)
                                    .setTrailersFactory(FlatNettyHttpHeaders.factory),
                                false, // parseHttpAfterConnectRequest
                                false  // failOnMissingResponse
                            )
                        )))
            connectTimeout.foreach { timeout =>
                discard(b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Integer.valueOf(timeout.toMillis.toInt)))
            }
            val connectFuture = b.connect()
            // Using a raw Promise rather than NettyUtil.await so the connect future's
            // completion and listener attachment race is handled correctly by Promise.
            val p = Promise.Unsafe.init[NettyConnection, Abort[HttpException]]()
            connectFuture.addListener { (future: ChannelFuture) =>
                if future.isSuccess then
                    discard(p.complete(Result.succeed(
                        new NettyConnection(future.channel(), host, port)
                    )))
                else
                    discard(p.complete(Result.fail(HttpConnectException(
                        host,
                        port,
                        future.cause()
                    ))))
                end if
            }
            p.onComplete(_ => discard(connectFuture.cancel(true)))
            p.safe.use(f)
        }
    end connectWith

    def sendWith[In, Out, A](
        conn: NettyConnection,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In],
        onReleaseUnsafe: Maybe[Result.Error[Any]] => Unit
    )(
        f: HttpResponse[Out] => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[HttpException]) =
        conn.sendWith(route, request, onReleaseUnsafe)(f)

    def isAlive(conn: NettyConnection)(using AllowUnsafe): Boolean =
        conn.isAlive

    def closeNowUnsafe(conn: NettyConnection)(using AllowUnsafe): Unit =
        conn.closeNowUnsafe()

    def close(conn: NettyConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        conn.close(gracePeriod)

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        val graceMs = gracePeriod.toMillis
        NettyUtil.await(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS))
end NettyClientBackend
