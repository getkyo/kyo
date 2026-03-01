package kyo.internal

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpDecoderConfig
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.flush.FlushConsolidationHandler
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kyo.*
import kyo.discard

final class NettyServerBackend extends HttpBackend.Server:

    def bind(
        handlers: Seq[HttpHandler[?, ?, ?]],
        config: HttpServer.Config
    )(using Frame): HttpBackend.Binding < Async =
        Sync.defer {
            val router      = HttpRouter(handlers, config.cors)
            val bossGroup   = new MultiThreadIoEventLoopGroup(1, NettyTransport.ioHandlerFactory)
            val workerGroup = new MultiThreadIoEventLoopGroup(NettyTransport.ioHandlerFactory)

            val bootstrap = new ServerBootstrap()
            discard {
                bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NettyTransport.serverSocketChannelClass)
                    .option(ChannelOption.SO_BACKLOG, Integer.valueOf(config.backlog))
                    .childHandler(new ChannelInitializer[SocketChannel]:
                        override def initChannel(ch: SocketChannel): Unit =
                            val pipeline = ch.pipeline()
                            discard(pipeline.addLast(new FlushConsolidationHandler(
                                config.flushConsolidationLimit,
                                true
                            )))
                            discard(pipeline.addLast(new HttpServerCodec(
                                new HttpDecoderConfig()
                                    .setHeadersFactory(FlatNettyHttpHeaders.factory)
                                    .setTrailersFactory(FlatNettyHttpHeaders.factory)
                            )))
                            discard(pipeline.addLast(new NettyServerHandler(router, config.maxContentLength))))
                    .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
                    .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.valueOf(config.keepAlive))
            }

            if config.tcpFastOpen then
                NettyTransport.applyTcpFastOpen(bootstrap, config.backlog)

            val bindFuture = bootstrap.bind(config.host, config.port)

            NettyUtil.continue(bindFuture, e => HttpError.BindError(config.host, config.port, e)) { channel =>
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
