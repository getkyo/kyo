package kyo.http2.internal

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.flush.FlushConsolidationHandler
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kyo.<
import kyo.Abort
import kyo.AllowUnsafe
import kyo.Async
import kyo.Duration
import kyo.Frame
import kyo.Maybe
import kyo.Promise
import kyo.Result
import kyo.Sync
import kyo.discard
import kyo.http2.*

final class NettyServerBackend extends HttpBackend.Server:

    private val flushConsolidationLimit = 256

    def bind(
        handlers: Seq[HttpHandler[?, ?, ?]],
        port: Int,
        host: String
    )(using Frame): HttpBackend.Binding < Async =
        Sync.defer {
            val router      = HttpRouter(handlers)
            val bossGroup   = new MultiThreadIoEventLoopGroup(1, NettyTransport.ioHandlerFactory)
            val workerGroup = new MultiThreadIoEventLoopGroup(NettyTransport.ioHandlerFactory)

            val bootstrap = new ServerBootstrap()
            discard {
                bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NettyTransport.serverSocketChannelClass)
                    .childHandler(new ChannelInitializer[SocketChannel]:
                        override def initChannel(ch: SocketChannel): Unit =
                            val pipeline = ch.pipeline()
                            discard(pipeline.addLast(new FlushConsolidationHandler(
                                flushConsolidationLimit,
                                true
                            )))
                            discard(pipeline.addLast(new HttpServerCodec()))
                            discard(pipeline.addLast(new NettyServerHandler(router))))
                    .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
            }

            val bindFuture = bootstrap.bind(host, port)

            NettyUtil.continue(bindFuture) { channel =>
                val address = channel.localAddress().asInstanceOf[InetSocketAddress]
                new HttpBackend.Binding:
                    def port: Int    = address.getPort
                    def host: String = address.getHostString
                    def close(gracePeriod: Duration)(using Frame): Unit < Async =
                        val graceMs = gracePeriod.toMillis
                        NettyUtil.continue(channel.close()) { _ =>
                            NettyUtil.continue(bossGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS)) { _ =>
                                NettyUtil.await(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS))
                            }
                        }
                    end close
                    def await(using Frame): Unit < Async =
                        NettyUtil.await(channel.closeFuture())
                end new
            }
        }
    end bind

end NettyServerBackend
