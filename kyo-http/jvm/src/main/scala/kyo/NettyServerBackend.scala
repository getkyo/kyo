package kyo

import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.flush.FlushConsolidationHandler
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kyo.internal.HttpServerHandler
import kyo.internal.NettyTransport
import kyo.internal.NettyUtil

/** JVM server backend using Netty 4.2 for HTTP transport. */
class NettyServerBackend(
    tcpFastOpen: Boolean = false,
    flushConsolidationLimit: Int = 256
) extends Backend.Server:

    def server(
        port: Int,
        host: String,
        maxContentLength: Int,
        backlog: Int,
        keepAlive: Boolean,
        handler: Backend.ServerHandler
    )(using Frame): Backend.Server.Binding < Async =
        Sync.defer {
            // Boss group accepts connections (1 thread), worker group handles I/O
            val bossGroup   = new MultiThreadIoEventLoopGroup(1, NettyTransport.ioHandlerFactory)
            val workerGroup = new MultiThreadIoEventLoopGroup(NettyTransport.ioHandlerFactory)

            val bootstrap = new ServerBootstrap()
            discard {
                bootstrap
                    .group(bossGroup, workerGroup)
                    .channel(NettyTransport.serverSocketChannelClass)
                    // Pipeline per connection: flush consolidation → HTTP codec → our state-machine handler
                    .childHandler(new ChannelInitializer[SocketChannel]:
                        override def initChannel(ch: SocketChannel): Unit =
                            val pipeline = ch.pipeline()
                            discard(pipeline.addLast(new FlushConsolidationHandler(
                                flushConsolidationLimit,
                                true
                            )))
                            discard(pipeline.addLast(new HttpServerCodec()))
                            discard(pipeline.addLast(new HttpServerHandler(
                                handler,
                                maxContentLength
                            ))))
                    .option(ChannelOption.SO_BACKLOG, Integer.valueOf(backlog))
                    .childOption(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.valueOf(keepAlive))
                    .childOption(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
            }
            if tcpFastOpen then
                NettyTransport.applyTcpFastOpen(bootstrap, backlog)

            val bindFuture = bootstrap.bind(host, port)

            NettyUtil.continue(bindFuture) { channel =>
                val address = channel.localAddress().asInstanceOf[InetSocketAddress]
                new Backend.Server.Binding:
                    def port: Int    = address.getPort
                    def host: String = address.getHostString
                    // Shutdown order: stop accepting → drain boss → drain workers
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
    end server

end NettyServerBackend
